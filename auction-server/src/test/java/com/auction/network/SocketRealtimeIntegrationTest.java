package com.auction.network;

import com.auction.dto.State;
import com.auction.repository.StateRepository;
import com.auction.service.AuctionService;
import com.auction.service.EventBus;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Full TCP JSON round-trip: the peer receives a pushed price, without polling LIST. */
class SocketRealtimeIntegrationTest {
    @Test void pushesAcceptedBidsToOtherLoggedInClients() throws Exception {
        StateRepository memory = new StateRepository() {
            State data = new State();
            @Override public State load() { return data; }
            @Override public void save(State next) { data = next; }
        };
        try (EventBus bus = new EventBus();
             ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            AuctionService service = new AuctionService(memory, bus, Clock.systemUTC());
            ClientHub hub = new ClientHub();
            bus.subscribe(hub::broadcast);
            Thread listener = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        Socket socket = server.accept();
                        Thread.ofVirtual().start(new ClientHandler(socket, service, hub));
                    }
                } catch (IOException ignored) { }
            });
            try (Peer seller = new Peer(server.getLocalPort()); Peer bidder = new Peer(server.getLocalPort())) {
                assertTrue(seller.request("REGISTER", Map.of("username", "seller", "password", "secure123", "role", "SELLER")).get("success").getAsBoolean());
                assertTrue(seller.request("LOGIN", Map.of("username", "seller", "password", "secure123")).get("success").getAsBoolean());
                assertTrue(bidder.request("REGISTER", Map.of("username", "bidder", "password", "secure123", "role", "BIDDER")).get("success").getAsBoolean());
                assertTrue(bidder.request("LOGIN", Map.of("username", "bidder", "password", "secure123")).get("success").getAsBoolean());
                long now = System.currentTimeMillis();
                JsonObject created = seller.request("CREATE", Map.of(
                        "name", "Laptop", "description", "Test auction", "category", "ELECTRONICS",
                        "price", 100L, "startsAt", now - 5000, "endsAt", now + 180_000,
                        "minimumIncrement", 10L));
                assertTrue(created.get("success").getAsBoolean());
                String id = created.getAsJsonObject("data").get("id").getAsString();
                assertEquals(100, bidder.nextPriceEvent(id));
                assertEquals(100, seller.nextPriceEvent(id));
                JsonObject receipt = bidder.request("BID", Map.of("id", id, "amount", 135L));
                assertTrue(receipt.get("success").getAsBoolean());
                assertEquals(135, seller.nextPriceEvent(id));
                assertEquals("bidder", receipt.getAsJsonObject("data").get("highestBidder").getAsString());
            }
            listener.join(1000);
        }
    }

    @Test void adminCanSuspendAndRestoreAConnectedBidder() throws Exception {
        StateRepository memory = new StateRepository() {
            State data = new State();
            @Override public State load() { return data; }
            @Override public void save(State next) { data = next; }
        };
        try (EventBus bus = new EventBus();
             ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            AuctionService service = new AuctionService(memory, bus, Clock.systemUTC());
            service.bootstrapAdmin("strongAdminPassword");
            ClientHub hub = new ClientHub();
            Thread listener = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 2; i++)
                        Thread.ofVirtual().start(new ClientHandler(server.accept(), service, hub));
                } catch (IOException ignored) { }
            });
            try (Peer admin = new Peer(server.getLocalPort());
                 Peer bidder = new Peer(server.getLocalPort())) {
                assertTrue(admin.request("LOGIN", Map.of("username", "admin",
                        "password", "strongAdminPassword")).get("success").getAsBoolean());
                assertTrue(bidder.request("REGISTER", Map.of("username", "alice",
                        "password", "secure123", "role", "BIDDER")).get("success").getAsBoolean());
                assertTrue(bidder.request("LOGIN", Map.of("username", "alice",
                        "password", "secure123")).get("success").getAsBoolean());
                assertFalse(bidder.request("USERS", Map.of()).get("success").getAsBoolean());
                assertTrue(admin.request("USERS", Map.of()).get("success").getAsBoolean());
                assertTrue(admin.request("SUSPEND_USER", Map.of("username", "alice"))
                        .get("success").getAsBoolean());
                assertFalse(bidder.request("LIST", Map.of()).get("success").getAsBoolean());
                assertTrue(admin.request("RESTORE_USER", Map.of("username", "alice"))
                        .get("success").getAsBoolean());
                assertTrue(bidder.request("LIST", Map.of()).get("success").getAsBoolean());
            }
            listener.join(1000);
        }
    }

    private static final class Peer implements AutoCloseable {
        private final Gson gson = new Gson();
        private final Socket socket;
        private final BufferedReader input;
        private final PrintWriter output;
        private final Queue<JsonObject> pushed = new ArrayDeque<>();

        Peer(int port) throws IOException {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(6000);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }
        JsonObject request(String type, Map<String, ?> values) throws IOException {
            String id = UUID.randomUUID().toString();
            JsonObject request = new JsonObject();
            request.addProperty("id", id);
            request.addProperty("type", type);
            request.add("data", gson.toJsonTree(values));
            output.println(gson.toJson(request));
            while (true) {
                JsonObject packet = readPacket();
                if ("AUCTION_UPDATED".equals(packet.get("type").getAsString())) {
                    pushed.add(packet.getAsJsonObject("data"));
                } else if (id.equals(packet.get("id").getAsString())) return packet;
            }
        }
        long nextPriceEvent(String auctionId) throws IOException {
            while (true) {
                JsonObject payload = pushed.poll();
                if (payload == null) {
                    JsonObject packet = readPacket();
                    if ("AUCTION_UPDATED".equals(packet.get("type").getAsString()))
                        payload = packet.getAsJsonObject("data");
                }
                if (payload != null && payload.has("id")
                        && auctionId.equals(payload.get("id").getAsString()))
                    return payload.get("currentPrice").getAsLong();
            }
        }
        JsonObject readPacket() throws IOException {
            String line = input.readLine();
            if (line == null) throw new IOException("Disconnected");
            return gson.fromJson(line, JsonObject.class);
        }
        @Override public void close() throws IOException { socket.close(); }
    }
}
