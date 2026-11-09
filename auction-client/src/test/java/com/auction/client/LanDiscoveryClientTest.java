package com.auction.client;

import com.auction.network.LanDiscoveryProtocol;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LanDiscoveryClientTest {
    @Test void discoversServerOverLoopbackWithoutTypingIp() throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(1800);
            Thread responder = Thread.ofVirtual().start(() -> {
                try {
                    byte[] receive = new byte[160];
                    DatagramPacket probe = new DatagramPacket(receive, receive.length);
                    server.receive(probe);
                    String request = new String(probe.getData(), probe.getOffset(),
                            probe.getLength(), StandardCharsets.UTF_8);
                    String prefix = LanDiscoveryProtocol.QUERY + ":";
                    if (!request.startsWith(prefix)) throw new IOException("Unexpected probe");
                    String nonce = request.substring(prefix.length());
                    String reply = LanDiscoveryProtocol.ANSWER + ":" + nonce + ":5050:"
                            + UUID.randomUUID();
                    byte[] payload = reply.getBytes(StandardCharsets.UTF_8);
                    server.send(new DatagramPacket(payload, payload.length,
                            probe.getAddress(), probe.getPort()));
                } catch (Exception error) { failed.set(error); }
            });
            LanDiscoveryClient.Endpoint endpoint = LanDiscoveryClient.discover(server.getLocalPort(), 700);
            responder.join(1500);
            assertNull(failed.get());
            assertEquals(5050, endpoint.port());
            assertEquals("127.0.0.1", endpoint.host());
        }
    }

    @Test void missingServerReportsAUsefulError() throws Exception {
        try (DatagramSocket unused = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            IOException error = assertThrows(IOException.class,
                    () -> LanDiscoveryClient.discover(unused.getLocalPort(), 400));
            assertTrue(error.getMessage().contains("Không tìm thấy"));
        }
    }
}
