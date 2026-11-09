package com.auction.network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LanDiscoveryResponderTest {
    @Test void returnsActualTcpPortAndEchoesNonce() throws Exception {
        try (DatagramSocket portReservation = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            int discoveryPort = portReservation.getLocalPort();
            portReservation.close();
            try (LanDiscoveryResponder responder = new LanDiscoveryResponder("127.0.0.1", 61234,
                    discoveryPort); DatagramSocket client = new DatagramSocket()) {
                client.setSoTimeout(1500);
                String nonce = UUID.randomUUID().toString();
                byte[] sent = (LanDiscoveryProtocol.QUERY + ":" + nonce)
                        .getBytes(StandardCharsets.UTF_8);
                client.send(new DatagramPacket(sent, sent.length,
                        InetAddress.getLoopbackAddress(), discoveryPort));
                byte[] data = new byte[256];
                DatagramPacket answer = new DatagramPacket(data, data.length);
                client.receive(answer);
                String response = new String(answer.getData(), answer.getOffset(),
                        answer.getLength(), StandardCharsets.UTF_8);
                assertTrue(response.startsWith(LanDiscoveryProtocol.ANSWER + ":" + nonce
                        + ":61234:"));
            }
        }
    }
}
