package com.auction.network;

import com.auction.model.*;
import com.auction.service.AuctionService;
import com.google.gson.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** Per-connection controller. Identity is bound to this socket after login. */
public final class ClientHandler implements Runnable {
    private static final int MAX_LINE_LENGTH = 64_000;
    private final Socket socket;
    private final AuctionService service;
    private final ClientHub hub;
    private final Gson gson = new Gson();
    private final BlockingQueue<Packet> outgoing = new ArrayBlockingQueue<>(256);
    private PrintWriter output;
    private User session;

    public ClientHandler(Socket socket, AuctionService service, ClientHub hub) {
        this.socket = socket; this.service = service; this.hub = hub;
    }

    @Override public void run() {
        Thread sender = null;
        try (socket;
             var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             var writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            output = writer;
            sender = Thread.ofVirtual().start(this::writeLoop);
            hub.add(this);
            String line;
            while ((line = input.readLine()) != null) {
                if (line.length() > MAX_LINE_LENGTH) break;
                Packet request = null;
                try {
                    request = gson.fromJson(line, Packet.class);
                    if (request == null || request.type == null || request.id == null)
                        throw new IllegalArgumentException("Invalid request");
                    send(Packet.result(request.id, handle(request), gson));
                } catch (RuntimeException e) {
                    send(Packet.error(request == null ? "" : request.id, e.getMessage()));
                }
            }
        } catch (IOException e) {
            System.err.println("Client disconnected: " + e.getMessage());
        } finally {
            hub.remove(this);
            if (sender != null) sender.interrupt();
        }
    }

    /** Bounded queue isolates the event dispatcher from a slow network client. */
    public boolean authenticated() {
        if (session == null) return false;
        try { service.requireActive(session); return true; }
        catch (SecurityException ignored) { return false; }
    }

    public void send(Packet packet) {
        if (!outgoing.offer(packet)) disconnect();
    }

    private void writeLoop() {
        try {
            while (!socket.isClosed()) {
                output.println(gson.toJson(outgoing.take()));
                if (output.checkError()) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally { disconnect(); }
    }

    private void disconnect() {
        hub.remove(this);
        try { socket.close(); } catch (IOException ignored) { }
    }

    private Object handle(Packet request) {
        JsonObject data = request.data != null && request.data.isJsonObject()
                ? request.data.getAsJsonObject() : new JsonObject();
        return switch (request.type) {
            case "REGISTER" -> {
                User user = service.register(str(data, "username"), str(data, "password"),
                        Role.valueOf(str(data, "role")));
                yield Map.of("username", user.getUsername(), "role", user.getRole());
            }
            case "LOGIN" -> {
                session = service.login(str(data, "username"), str(data, "password"));
                yield Map.of("username", session.getUsername(), "role", session.getRole());
            }
            case "USERS" -> service.listUsers(authorized());
            case "SUSPEND_USER" -> service.setUserSuspended(authorized(), str(data, "username"), true);
            case "RESTORE_USER" -> service.setUserSuspended(authorized(), str(data, "username"), false);
            case "LIST" -> { authorized(); yield service.list(); }
            case "DETAIL" -> { authorized(); yield service.get(str(data, "id")); }
            case "CREATE" -> service.create(authorized(), str(data, "name"), str(data, "description"),
                    Category.valueOf(str(data, "category")), num(data, "price"),
                    num(data, "startsAt"), num(data, "endsAt"), num(data, "minimumIncrement"));
            case "EDIT" -> service.edit(authorized(), str(data, "id"), str(data, "name"),
                    str(data, "description"), Category.valueOf(str(data, "category")),
                    num(data, "price"), num(data, "startsAt"), num(data, "endsAt"), num(data, "minimumIncrement"));
            case "DELETE" -> {
                String id = str(data, "id");
                service.delete(authorized(), id);
                hub.removed(id);
                yield Map.of("deleted", id);
            }
            case "BID" -> service.bid(authorized(), str(data, "id"), num(data, "amount"));
            case "AUTO_BID" -> {
                User bidder = authorized();
                String id = str(data, "id");
                // Old clients omitted increment; retain their seller-minimum behavior.
                long increment = data.has("increment") ? num(data, "increment")
                        : service.get(id).minimumIncrement();
                yield service.registerAuto(bidder, id, num(data, "maxBid"), increment);
            }
            case "CANCEL" -> service.cancel(authorized(), str(data, "id"));
            case "PAY" -> service.pay(authorized(), str(data, "id"));
            default -> throw new IllegalArgumentException("Unknown command: " + request.type);
        };
    }

    private User authorized() {
        if (session == null) throw new SecurityException("Please login first");
        service.requireActive(session);
        return session;
    }

    private static String str(JsonObject data, String key) {
        if (!data.has(key) || data.get(key).isJsonNull())
            throw new IllegalArgumentException("Missing " + key);
        String value = data.get(key).getAsString();
        if (value.length() > 2000) throw new IllegalArgumentException("Field too long: " + key);
        return value;
    }

    private static long num(JsonObject data, String key) {
        try { return Long.parseLong(str(data, key)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid integer: " + key); }
    }
}
