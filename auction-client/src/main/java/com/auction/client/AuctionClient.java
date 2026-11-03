package com.auction.client;

import com.auction.network.Packet;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.*;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Persistent TCP connection. A background reader demultiplexes replies and events. */
public final class AuctionClient implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final Gson gson = new Gson();
    private final ConcurrentHashMap<String, CompletableFuture<Packet>> pending = new ConcurrentHashMap<>();
    private volatile Consumer<Packet> eventListener = event -> { };

    public AuctionClient(String host, int port) throws IOException {
        Socket connected = new Socket();
        try {
            connected.connect(new InetSocketAddress(host, port), 5_000);
            connected.setKeepAlive(true);
        } catch (IOException | RuntimeException error) {
            try { connected.close(); } catch (IOException ignored) { }
            throw error;
        }
        socket = connected;
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        Thread thread = new Thread(this::readLoop, "auction-client-reader");
        thread.setDaemon(true);
        thread.start();
    }

    public void onEvent(Consumer<Packet> listener) { eventListener = listener; }

    public CompletableFuture<Packet> request(String type, JsonObject data) {
        Packet packet = new Packet();
        packet.id = UUID.randomUUID().toString();
        packet.type = type;
        packet.data = data;
        CompletableFuture<Packet> future = new CompletableFuture<>();
        pending.put(packet.id, future);
        synchronized (writer) {
            writer.println(gson.toJson(packet));
            if (writer.checkError()) {
                pending.remove(packet.id);
                future.completeExceptionally(new IOException("Cannot send request"));
            }
        }
        return future;
    }

    private void readLoop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                Packet packet = gson.fromJson(line, Packet.class);
                if (packet == null) continue;
                if ("AUCTION_UPDATED".equals(packet.type)) {
                    try { eventListener.accept(packet); }
                    catch (RuntimeException e) { e.printStackTrace(System.err); }
                }
                else {
                    CompletableFuture<Packet> future = pending.remove(packet.id);
                    if (future != null) future.complete(packet);
                }
            }
        } catch (IOException | RuntimeException e) {
            // All outstanding requests below receive the disconnect exception.
        } finally {
            IOException failure = new IOException("Disconnected from server");
            pending.forEach((id, future) -> future.completeExceptionally(failure));
            pending.clear();
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    @Override public void close() throws IOException { socket.close(); }
}
