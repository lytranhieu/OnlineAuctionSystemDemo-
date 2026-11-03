package com.auction.network;

import com.google.gson.JsonElement;

/** One JSON object per line. id correlates asynchronous request/response pairs. */
public final class Packet {
    public String id;
    public String type;
    public boolean success;
    public String error;
    public JsonElement data;

    public static Packet result(String id, Object data, com.google.gson.Gson gson) {
        Packet p = new Packet();
        p.id = id; p.type = "RESPONSE"; p.success = true;
        p.data = gson.toJsonTree(data);
        return p;
    }

    public static Packet error(String id, String message) {
        Packet p = new Packet();
        p.id = id; p.type = "RESPONSE"; p.error = message;
        return p;
    }

    public static Packet event(Object data, com.google.gson.Gson gson) {
        Packet p = new Packet();
        p.type = "AUCTION_UPDATED"; p.success = true;
        p.data = gson.toJsonTree(data);
        return p;
    }
}
