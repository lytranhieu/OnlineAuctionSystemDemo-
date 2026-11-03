package com.auction.network;

import com.auction.service.*;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Start separately; do not expose plain TCP to the public internet. */
public final class AuctionServer {
    private AuctionServer() { }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5050;
        Path dataFile = Path.of(args.length > 1 ? args[1] : "data/state.json");
        AuctionManager manager = AuctionManager.getInstance();
        AuctionService service = manager.initialize(dataFile);
        service.bootstrapAdmin(System.getenv("AUCTION_ADMIN_PASSWORD"));
        ClientHub hub = new ClientHub();
        manager.events().subscribe(hub::broadcast);

        var scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try { service.advanceAll(); }
            catch (Exception e) { System.err.println("Auction scheduler: " + e.getMessage()); }
        }, 0, 500, TimeUnit.MILLISECONDS);

        var workers = Executors.newFixedThreadPool(64);
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("Auction Server ready on port " + port + " | data: " + dataFile);
            while (true) {
                var socket = server.accept();
                socket.setKeepAlive(true);
                workers.execute(new ClientHandler(socket, service, hub));
            }
        } finally {
            scheduler.shutdownNow();
            workers.shutdownNow();
            manager.events().close();
        }
    }
}
