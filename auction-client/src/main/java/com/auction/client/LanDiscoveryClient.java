package com.auction.client;

import com.auction.network.LanDiscoveryProtocol;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Finds one auction server on the same broadcast LAN without asking for an IP address. */
public final class LanDiscoveryClient {
    private static final int DISCOVERY_DURATION_MS = 2_000;
    private static final int RECEIVE_TIMEOUT_MS = 250;
    private static final int MAX_REPLY_BYTES = 256;

    private LanDiscoveryClient() { }

    public record Endpoint(String host, int port) { }

    public static Endpoint discover() throws IOException {
        return discover(LanDiscoveryProtocol.UDP_PORT, DISCOVERY_DURATION_MS);
    }

    /** Package-private for repeatable localhost integration tests. */
    static Endpoint discover(int discoveryPort, int timeoutMillis) throws IOException {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Invalid timeout");
        String nonce = UUID.randomUUID().toString();
        byte[] query = (LanDiscoveryProtocol.QUERY + ":" + nonce).getBytes(StandardCharsets.UTF_8);
        Set<InetAddress> destinations = broadcastAddresses();
        Map<String, Endpoint> servers = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(RECEIVE_TIMEOUT_MS);
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            long lastProbe = 0;
            byte[] buffer = new byte[MAX_REPLY_BYTES];
            while (System.nanoTime() < deadline) {
                long now = System.nanoTime();
                if (lastProbe == 0 || now - lastProbe >= 550_000_000L) {
                    for (InetAddress address : destinations) {
                        try {
                            socket.send(new DatagramPacket(query, query.length,
                                    address, discoveryPort));
                        } catch (IOException error) {
                            // One inactive interface must not block a working Wi-Fi adapter.
                        }
                    }
                    lastProbe = now;
                }
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException expected) {
                    continue;
                }
                String answer = new String(reply.getData(), reply.getOffset(),
                        reply.getLength(), StandardCharsets.UTF_8);
                String[] parts = answer.split(":", -1);
                if (parts.length != 4 || !LanDiscoveryProtocol.ANSWER.equals(parts[0])
                        || !nonce.equals(parts[1])) continue;
                int port;
                try {
                    port = Integer.parseInt(parts[2]);
                    if (port < 1 || port > 65535) continue;
                    UUID.fromString(parts[3]);
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
                Endpoint candidate = new Endpoint(reply.getAddress().getHostAddress(), port);
                Endpoint previous = servers.get(parts[3]);
                if (previous == null || (isLoopback(previous.host()) && !isLoopback(candidate.host()))) {
                    servers.put(parts[3], candidate);
                }
            }
        }
        if (servers.isEmpty()) throw new IOException("Không tìm thấy Auction Server trên mạng LAN. "
                + "Hãy mở Server và cho phép UDP 5051/TCP 5050 qua Firewall.");
        if (servers.size() > 1) throw new IOException("Phát hiện nhiều Auction Server trong mạng. "
                + "Hãy chỉ chạy một Server để tránh kết nối nhầm.");
        return servers.values().iterator().next();
    }

    private static boolean isLoopback(String host) {
        return host.equals("127.0.0.1") || host.equals("0:0:0:0:0:0:0:1") || host.equals("::1");
    }

    private static Set<InetAddress> broadcastAddresses() throws IOException {
        Set<InetAddress> addresses = new LinkedHashSet<>();
        addresses.add(InetAddress.getByName("127.0.0.1")); // Client running on Server machine.
        addresses.add(InetAddress.getByName("255.255.255.255"));
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces != null) {
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                try {
                    if (!network.isUp() || network.isLoopback()) continue;
                    for (InterfaceAddress address : network.getInterfaceAddresses()) {
                        if (address.getBroadcast() != null) addresses.add(address.getBroadcast());
                    }
                } catch (SocketException unavailable) {
                    // Ignore stale VPN / virtual network interfaces.
                }
            }
        }
        return addresses;
    }
}
