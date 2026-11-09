package com.auction.client;

import com.auction.network.JsonLineFraming;
import com.auction.network.Packet;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** TCP JSON-line client; network I/O is never performed on the JavaFX thread. */
public final class AuctionClient implements AutoCloseable {
    private static final int CONNECTION_TIMEOUT_MS = 5_000;
    private static final int RESPONSE_TIMEOUT_SECONDS = 12;
    private static final int MAX_RESPONSE_CHARS = 2_000_000;
    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final Gson gson = new Gson();
    private final ConcurrentHashMap<String, CompletableFuture<Packet>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closedByUser = new AtomicBoolean();
    // Bounded asynchronous writer: FX event handlers never perform TCP writes.
    private final ThreadPoolExecutor outbound = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
            Thread.ofVirtual().name("auction-client-writer-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private volatile Consumer<Packet> eventListener = event -> { };
    private volatile Consumer<IOException> disconnectListener = error -> { };

    public AuctionClient(String host, int port) throws IOException {
        Socket connected = new Socket();
        try {
            connected.connect(new InetSocketAddress(host, port), CONNECTION_TIMEOUT_MS);
            connected.setKeepAlive(true);
        } catch (IOException | RuntimeException error) {
            try { connected.close(); } catch (IOException ignored) { }
            throw error;
        }
        socket = connected;
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        Thread.ofVirtual().name("auction-client-reader").start(this::readLoop);
    }

    public void onEvent(Consumer<Packet> listener) { eventListener = listener; }
    public void onDisconnect(Consumer<IOException> listener) { disconnectListener = listener; }

    public CompletableFuture<Packet> request(String type, JsonObject data) {
        CompletableFuture<Packet> future = new CompletableFuture<>();
        if (socket.isClosed()) {
            future.completeExceptionally(new IOException("Connection is closed"));
            return future;
        }
        Packet packet = new Packet();
        packet.id = UUID.randomUUID().toString();
        packet.type = type;
        packet.data = data;
        pending.put(packet.id, future);
        future.orTimeout(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((reply, error) -> {
                    pending.remove(packet.id, future);
                    if (error instanceof TimeoutException && !closedByUser.get()) {
                        // A black-holed Wi-Fi connection may never reach readLoop's EOF.
                        // Closing the socket wakes the reader and notifies the GUI.
                        try { socket.close(); }
                        catch (IOException e) { System.err.println("Close timed-out socket: " + e); }
                    }
                });
        try {
            outbound.execute(() -> {
                writer.println(gson.toJson(packet));
                if (writer.checkError()) {
                    pending.remove(packet.id, future);
                    future.completeExceptionally(new IOException("Cannot send request to server"));
                    try { socket.close(); }
                    catch (IOException e) { System.err.println("Close after send error: " + e); }
                }
            });
        } catch (RejectedExecutionException error) {
            pending.remove(packet.id, future);
            future.completeExceptionally(new IOException("Outgoing request queue full or disconnected", error));
        }
        return future;
    }

    private void readLoop() {
        IOException failure = new IOException("Server closed the connection");
        try {
            String line;
            while ((line = JsonLineFraming.readLine(reader, MAX_RESPONSE_CHARS)) != null) {
                Packet packet = gson.fromJson(line, Packet.class);
                if (packet == null || packet.type == null) continue;
                if ("AUCTION_UPDATED".equals(packet.type)) {
                    try { eventListener.accept(packet); }
                    catch (RuntimeException e) { System.err.println("Invalid push event: " + e); }
                } else if (packet.id != null) {
                    CompletableFuture<Packet> future = pending.remove(packet.id);
                    if (future != null) future.complete(packet);
                }
            }
        } catch (IOException e) {
            failure = e;
        } catch (RuntimeException e) {
            failure = new IOException("Malformed JSON received from Server", e);
        } finally {
            final IOException disconnected = failure;
            pending.forEach((id, future) -> future.completeExceptionally(disconnected));
            pending.clear();
            outbound.shutdownNow();
            try { socket.close(); }
            catch (IOException e) { System.err.println("Close client socket: " + e); }
            if (!closedByUser.get()) {
                System.err.println("Connection lost: " + disconnected);
                try { disconnectListener.accept(disconnected); }
                catch (RuntimeException e) { System.err.println("Disconnect callback failed: " + e); }
            }
        }
    }

    @Override public void close() throws IOException {
        closedByUser.set(true);
        outbound.shutdownNow();
        socket.close();
    }
}
