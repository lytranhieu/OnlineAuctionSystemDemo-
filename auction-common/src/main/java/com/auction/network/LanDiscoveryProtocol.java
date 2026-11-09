package com.auction.network;

/** Shared constants for discovering the TCP auction server on the local network. */
public final class LanDiscoveryProtocol {
    public static final int UDP_PORT = 5051;
    public static final String QUERY = "AUCTIVA_DISCOVER_V1";
    public static final String ANSWER = "AUCTIVA_SERVER_V1";

    private LanDiscoveryProtocol() { }
}
