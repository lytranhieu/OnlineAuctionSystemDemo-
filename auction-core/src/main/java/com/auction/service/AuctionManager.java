package com.auction.service;

import com.auction.repository.FileStateRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;

/** Singleton composition root for the running server. */
public final class AuctionManager {
    private static final AuctionManager INSTANCE = new AuctionManager();
    private final EventBus events = new EventBus();
    private AuctionService service;

    private AuctionManager() { }

    public static AuctionManager getInstance() { return INSTANCE; }

    public synchronized AuctionService initialize(Path path) throws IOException {
        if (service == null) service = new AuctionService(new FileStateRepository(path), events, Clock.systemUTC());
        return service;
    }

    public EventBus events() { return events; }
}
