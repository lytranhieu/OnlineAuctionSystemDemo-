package com.auction.service;

import com.auction.dto.AuctionView;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Observer pattern: send ordered updates outside business locks. */
public final class EventBus implements AutoCloseable {
    private final CopyOnWriteArrayList<Consumer<AuctionView>> observers = new CopyOnWriteArrayList<>();
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "auction-events");
        thread.setDaemon(true);
        return thread;
    });

    public void subscribe(Consumer<AuctionView> observer) { observers.add(observer); }

    public void publish(AuctionView auction) {
        dispatcher.execute(() -> {
            for (var observer : observers) {
                try { observer.accept(auction); }
                catch (RuntimeException e) { System.err.println("Observer failed: " + e.getMessage()); }
            }
        });
    }

    @Override public void close() { dispatcher.shutdown(); }
}
