package com.auction.client;

import static org.junit.jupiter.api.Assertions.*;

import com.auction.network.Packet;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AuctionClientNetworkingTest {
    @Test void correlatesJsonLinesAndNotifiesWhenServerDisconnects() throws Exception {
        var gson = new Gson();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch serverDone = new CountDownLatch(1);
            Thread peer = Thread.ofVirtual().start(() -> {
                try (var socket = server.accept();
                     var input = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     var output = new PrintWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String requestLine = input.readLine();
                    Packet request = gson.fromJson(requestLine, Packet.class);
                    output.println(gson.toJson(Packet.result(request.id, Map.of("message", "PONG"), gson)));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally { serverDone.countDown(); }
            });
            CountDownLatch disconnected = new CountDownLatch(1);
            try (AuctionClient client = new AuctionClient("127.0.0.1", server.getLocalPort())) {
                client.onDisconnect(error -> disconnected.countDown());
                Packet response = client.request("PING", new JsonObject()).get(5, TimeUnit.SECONDS);
                assertTrue(response.success);
                assertEquals("PONG", response.data.getAsJsonObject().get("message").getAsString());
                assertTrue(disconnected.await(5, TimeUnit.SECONDS));
                assertTrue(serverDone.await(5, TimeUnit.SECONDS));
            }
            peer.join(1000);
        }
    }
}
