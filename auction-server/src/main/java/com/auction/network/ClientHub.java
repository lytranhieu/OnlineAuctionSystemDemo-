package com.auction.network;

import com.auction.dto.AuctionView;
import com.google.gson.Gson;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Thread-safe subscription registry; slow/disconnected peers are detached. */
public final class ClientHub {
    private final Set<ClientHandler> clients = new CopyOnWriteArraySet<>();
    private final Gson gson = new Gson();

    public void add(ClientHandler client) { clients.add(client); }
    public void remove(ClientHandler client) { clients.remove(client); }

    public void broadcast(AuctionView auction) {
        Packet event = Packet.event(auction, gson);
        for (ClientHandler client : clients) if (client.authenticated()) client.send(event);
    }

    public void removed(String id) {
        Packet event = Packet.event(java.util.Map.of("removedId", id), gson);
        for (ClientHandler client : clients) if (client.authenticated()) client.send(event);
    }
}
