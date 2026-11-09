package com.auction.model;

import com.auction.dto.AuctionView;
import com.auction.dto.State;
import com.auction.service.exception.AuctionClosedException;
import com.auction.service.exception.InvalidBidException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** One auction owns the lock protecting price, winner and status together. */
public final class Auction extends Entity {
    private final ReentrantLock lock = new ReentrantLock();
    private Item item;
    private final String seller;
    private long startingPrice;
    private long minimumIncrement;
    private long currentPrice;
    private long startsAt;
    private long endsAt;
    private Status status;
    private String highestBidder;
    private String reviewNote;
    private String reviewedBy;
    private long reviewedAt;
    private final List<BidTransaction> history;
    private final List<AutoBid> autoBids;

    public Auction(String id, Item item, String seller, long price, long startsAt, long endsAt,
            long minimumIncrement) {
        super(id);
        if (item == null || seller == null || seller.isBlank() || startsAt < 1 || endsAt <= startsAt)
            throw new IllegalArgumentException("Invalid item, seller or auction time range");
        this.item = item;
        this.seller = seller;
        if (minimumIncrement < 1 || price < 1 || minimumIncrement > Long.MAX_VALUE - price)
            throw new IllegalArgumentException("Invalid minimum bid increment");
        this.minimumIncrement = minimumIncrement;
        this.startingPrice = price;
        this.currentPrice = price;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.status = Status.PENDING;
        this.history = new ArrayList<>();
        this.autoBids = new ArrayList<>();
    }

    public static Auction restore(State.AuctionData data) {
        Auction auction = new Auction(data.id(),
                ItemFactory.create(data.category(), data.id(), data.name(), data.description()),
                data.seller(), data.startingPrice(), data.startsAt(), data.endsAt(),
                // Old JSON snapshots have no increment: keep their prior 1-VND rule.
                Math.max(1L, data.minimumIncrement()));
        auction.currentPrice = data.currentPrice();
        auction.status = data.status();
        auction.highestBidder = data.highestBidder();
        auction.reviewNote = data.reviewNote();
        auction.reviewedBy = data.reviewedBy();
        auction.reviewedAt = data.reviewedAt();
        if (data.history() != null) auction.history.addAll(data.history());
        if (data.autoBids() != null) {
            // Legacy snapshots (before optional bidder steps) deserialize increment as zero.
            for (AutoBid bid : data.autoBids()) {
                if (bid != null) auction.autoBids.add(new AutoBid(bid.bidder(), bid.maxBid(),
                        Math.max(auction.minimumIncrement, bid.increment()), bid.createdAt()));
            }
        }
        return auction;
    }

    public State.AuctionData snapshot() {
        lock.lock();
        try {
            return new State.AuctionData(getId(), item.getName(), item.getDescription(),
                    item.getCategory(), seller, startingPrice, currentPrice, startsAt, endsAt,
                    status, highestBidder, List.copyOf(history), List.copyOf(autoBids),
                    minimumIncrement, reviewNote, reviewedBy, reviewedAt);
        } finally { lock.unlock(); }
    }

    public AuctionView view() {
        State.AuctionData a = snapshot();
        return new AuctionView(a.id(), a.name(), a.description(), a.category(), a.seller(),
                a.startingPrice(), a.currentPrice(), a.startsAt(), a.endsAt(),
                a.status(), a.highestBidder(), a.history(), a.minimumIncrement(),
                a.reviewNote(), a.reviewedBy(), a.reviewedAt());
    }

    public boolean advance(long now) {
        lock.lock();
        try {
            Status old = status;
            if (status == Status.OPEN && now >= startsAt) status = Status.RUNNING;
            if ((status == Status.OPEN || status == Status.RUNNING) && now >= endsAt)
                status = Status.FINISHED;
            return old != status;
        } finally { lock.unlock(); }
    }

    /** Atomic compare-and-set of leading price and bidder. No I/O under this lock. */
    public void bid(String bidder, long amount, long now, boolean automatic) {
        lock.lock();
        try {
            advance(now);
            if (status != Status.RUNNING) throw new AuctionClosedException("Auction is not running");
            if (amount <= currentPrice || amount - currentPrice < minimumIncrement)
                throw new InvalidBidException("Bid must be at least current price + "
                        + minimumIncrement + " VND (seller minimum increment)");
            if (bidder.equals(seller)) throw new IllegalArgumentException("Seller cannot bid on own item");
            if (!automatic && bidder.equals(highestBidder))
                throw new IllegalArgumentException("Already the highest bidder");
            currentPrice = amount;
            highestBidder = bidder;
            history.add(new BidTransaction(bidder, amount, now, automatic));
            // A late bid extends the current deadline (anti-sniping).
            if (endsAt - now <= 30_000L) endsAt = Math.addExact(endsAt, 60_000L);
        } finally { lock.unlock(); }
    }

    public void putAutoBid(AutoBid bid) {
        lock.lock();
        try {
            autoBids.removeIf(a -> a.bidder().equals(bid.bidder()));
            autoBids.add(bid);
        } finally { lock.unlock(); }
    }

    public boolean removeAutoBid(String username) {
        lock.lock();
        try { return autoBids.removeIf(a -> a.bidder().equals(username)); }
        finally { lock.unlock(); }
    }

    /** A cancelled auction has no winner, even if it had a former leader. */
    public String getWinner() {
        lock.lock();
        try { return (status == Status.FINISHED || status == Status.PAID) ? highestBidder : null; }
        finally { lock.unlock(); }
    }

    public List<AutoBid> getAutoBids() { return snapshot().autoBids(); }
    public String getSeller() { return seller; }
    public Status getStatus() { return snapshot().status(); }
    public String getHighestBidder() { return snapshot().highestBidder(); }
    public long getCurrentPrice() { return snapshot().currentPrice(); }
    public long getMinimumIncrement() { return snapshot().minimumIncrement(); }

    public void edit(Item item, long price, long start, long end, long minimumIncrement) {
        lock.lock();
        try {
            if ((status != Status.OPEN && status != Status.PENDING && status != Status.REJECTED)
                    || !history.isEmpty())
                throw new IllegalStateException("Only upcoming/rejected auctions without bids can be edited");
            if (end <= start) throw new IllegalArgumentException("End must be after start");
            if (minimumIncrement < 1 || price < 1 || minimumIncrement > Long.MAX_VALUE - price)
                throw new IllegalArgumentException("Invalid minimum bid increment");
            this.item = item;
            this.minimumIncrement = minimumIncrement;
            this.startingPrice = price;
            this.currentPrice = price;
            this.startsAt = start;
            this.endsAt = end;
            // Any edit invalidates a previous approval: Admin must review the new details.
            this.status = Status.PENDING;
            this.reviewNote = null;
            this.reviewedBy = null;
            this.reviewedAt = 0L;
        } finally { lock.unlock(); }
    }

    /** Only an authorized admin can invoke this through AuctionService. */
    public void approve(String admin, long now) {
        lock.lock();
        try {
            if (status != Status.PENDING)
                throw new IllegalStateException("Only pending auctions can be approved");
            if (now >= endsAt)
                throw new IllegalStateException("Auction end time has passed; seller must edit and resubmit");
            status = Status.OPEN;
            reviewNote = null;
            reviewedBy = admin;
            reviewedAt = now;
            advance(now);  // If the seller's start time already passed, start immediately.
        } finally { lock.unlock(); }
    }

    /** Keep the rejected submission visible to its seller for correction. */
    public void reject(String admin, String reason, long now) {
        lock.lock();
        try {
            if (status != Status.PENDING)
                throw new IllegalStateException("Only pending auctions can be rejected");
            if (reason == null || reason.isBlank() || reason.length() > 500)
                throw new IllegalArgumentException("Reason is required (1-500 characters)");
            status = Status.REJECTED;
            reviewNote = reason.strip();
            reviewedBy = admin;
            reviewedAt = now;
        } finally { lock.unlock(); }
    }

    public void cancel() {
        lock.lock();
        try {
            if (status == Status.PAID || status == Status.CANCELED)
                throw new IllegalStateException("Auction already closed");
            status = Status.CANCELED;
        } finally { lock.unlock(); }
    }

    public void markPaid() {
        lock.lock();
        try {
            if (status != Status.FINISHED || highestBidder == null)
                throw new IllegalStateException("Only finished auctions with a winner can be paid");
            status = Status.PAID;
        } finally { lock.unlock(); }
    }
}
