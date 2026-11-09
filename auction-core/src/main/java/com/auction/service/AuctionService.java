package com.auction.service;

import com.auction.dto.AuctionView;
import com.auction.dto.State;
import com.auction.dto.UserSummary;
import com.auction.model.*;
import com.auction.repository.StateRepository;
import com.auction.service.exception.AuthenticationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.*;

/** Business boundary. Synchronized mutations make state+disk snapshots consistent. */
public final class AuctionService {
    private final Map<String, User> users = new HashMap<>();
    private final Set<String> suspendedUsers = new HashSet<>();
    private final Map<String, Auction> auctions = new LinkedHashMap<>();
    private final StateRepository repository;
    private final EventBus events;
    private final Clock clock;
    private final AutoBidEngine autoBidEngine = new AutoBidEngine();

    public AuctionService(StateRepository repository, EventBus events, Clock clock) throws IOException {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
        State state = repository.load();
        for (State.UserData record : state.users) {
            User user = switch (record.role()) {
                case BIDDER -> new Bidder(record.username(), record.salt(), record.passwordHash());
                case SELLER -> new Seller(record.username(), record.salt(), record.passwordHash());
                case ADMIN -> new Admin(record.username(), record.salt(), record.passwordHash());
            };
            users.put(user.getUsername(), user);
        }
        if (state.suspendedUsers != null) {
            for (String username : state.suspendedUsers)
                if (username != null) suspendedUsers.add(username.toLowerCase(Locale.ROOT));
        }
        for (State.AuctionData record : state.auctions) {
            Auction auction = Auction.restore(record);
            auctions.put(auction.getId(), auction);
        }
        advanceAll();
    }

    public synchronized User register(String username, String password, Role role) {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,24}"))
            throw new IllegalArgumentException("Username: 3-24 letters, digits or underscores");
        if (password == null || password.length() < 6 || password.length() > 128)
            throw new IllegalArgumentException("Password must contain 6-128 characters");
        if (role != Role.BIDDER && role != Role.SELLER)
            throw new SecurityException("Cannot register an admin account");
        String key = username.toLowerCase(Locale.ROOT);
        if (users.containsKey(key)) throw new IllegalArgumentException("Username already exists");
        String salt = PasswordHasher.salt();
        String hash = PasswordHasher.hash(password.toCharArray(), salt);
        User user = role == Role.BIDDER ? new Bidder(key, salt, hash) : new Seller(key, salt, hash);
        users.put(key, user);
        save();
        return user;
    }

    /** Optional admin bootstrap: set AUCTION_ADMIN_PASSWORD before first server launch. */
    public synchronized void bootstrapAdmin(String password) {
        if (password == null || password.length() < 8 || users.containsKey("admin")) return;
        String salt = PasswordHasher.salt();
        users.put("admin", new Admin("admin", salt, PasswordHasher.hash(password.toCharArray(), salt)));
        save();
    }

    public synchronized User login(String username, String password) {
        User user = username == null ? null : users.get(username.toLowerCase(Locale.ROOT));
        if (user == null || password == null ||
                !PasswordHasher.matches(password.toCharArray(), user.getSalt(), user.getPasswordHash()))
            throw new AuthenticationException("Invalid username or password");
        if (suspendedUsers.contains(user.getUsername()))
            throw new AuthenticationException("Account suspended by administrator");
        return user;
    }

    /** Check an existing socket session on every request; locking a user takes effect immediately. */
    public synchronized void requireActive(User actor) {
        if (actor == null || users.get(actor.getUsername()) != actor)
            throw new SecurityException("Please login first");
        if (suspendedUsers.contains(actor.getUsername()))
            throw new SecurityException("Account suspended by administrator");
    }

    public synchronized List<UserSummary> listUsers(User actor) {
        requireRole(actor, Role.ADMIN);
        return users.values().stream()
                .map(u -> new UserSummary(u.getUsername(), u.getRole(),
                        suspendedUsers.contains(u.getUsername())))
                .sorted(Comparator.comparing(UserSummary::username))
                .toList();
    }

    /** Suspension blocks logins, active sessions and pending automatic bids. */
    public synchronized List<UserSummary> setUserSuspended(User actor, String username, boolean suspended) {
        requireRole(actor, Role.ADMIN);
        if (username == null) throw new IllegalArgumentException("Missing username");
        String key = username.toLowerCase(Locale.ROOT);
        User target = users.get(key);
        if (target == null) throw new IllegalArgumentException("User not found");
        if (target.getRole() == Role.ADMIN)
            throw new SecurityException("Administrator accounts cannot be suspended");
        if (suspended) {
            suspendedUsers.add(key);
            List<Auction> modified = new ArrayList<>();
            for (Auction auction : auctions.values()) {
                if (auction.removeAutoBid(key)) modified.add(auction);
            }
            save();
            modified.forEach(a -> events.publish(a.view()));
        } else {
            suspendedUsers.remove(key);
            save();
        }
        return listUsers(actor);
    }

    public synchronized List<AuctionView> list() {
        advanceAll();
        return auctions.values().stream().map(Auction::view).toList();
    }

    /** Show pending and rejected submissions only to their owner and the admin. */
    public synchronized List<AuctionView> list(User actor) {
        requireActive(actor);
        advanceAll();
        return auctions.values().stream().filter(a -> canSee(actor, a)).map(Auction::view).toList();
    }

    public synchronized AuctionView get(User actor, String id) {
        requireActive(actor);
        advanceAll();
        Auction auction = required(id);
        if (!canSee(actor, auction)) throw new SecurityException("Auction not available");
        return auction.view();
    }

    private static boolean canSee(User actor, Auction auction) {
        Status status = auction.getStatus();
        return (status != Status.PENDING && status != Status.REJECTED)
                || actor.getRole() == Role.ADMIN || actor.getUsername().equals(auction.getSeller());
    }

    public synchronized AuctionView get(String id) {
        advanceAll();
        return required(id).view();
    }

    public synchronized AuctionView create(User actor, String name, String description,
            Category category, long price, long startsAt, long endsAt,
            long minimumIncrement) {
        requireRole(actor, Role.SELLER);
        validateItem(name, description, price, startsAt, endsAt, minimumIncrement);
        String id = UUID.randomUUID().toString().substring(0, 8);
        Item item = ItemFactory.create(category, id, name.strip(), description.strip());
        Auction auction = new Auction(id, item, actor.getUsername(), price, startsAt, endsAt, minimumIncrement);
        auction.advance(clock.millis());
        auctions.put(id, auction);
        changed(auction);
        return auction.view();
    }

    public synchronized AuctionView edit(User actor, String id, String name, String description,
            Category category, long price, long startsAt, long endsAt,
            long minimumIncrement) {
        requireRole(actor, Role.SELLER);
        Auction auction = required(id);
        ensureOwner(actor, auction);
        advanceAll();
        validateItem(name, description, price, startsAt, endsAt, minimumIncrement);
        auction.edit(ItemFactory.create(category, id, name.strip(), description.strip()),
                price, startsAt, endsAt, minimumIncrement);
        changed(auction);
        return auction.view();
    }

    public synchronized void delete(User actor, String id) {
        requireRole(actor, Role.SELLER);
        Auction auction = required(id);
        ensureOwner(actor, auction);
        advanceAll();
        if ((auction.getStatus() != Status.OPEN && auction.getStatus() != Status.PENDING
                && auction.getStatus() != Status.REJECTED) || !auction.view().history().isEmpty())
            throw new IllegalStateException("Only upcoming or unapproved auctions without bids can be deleted");
        auctions.remove(id);
        save();
        // ClientHub sends the removal to all connected authorized clients.
    }

    public synchronized AuctionView bid(User actor, String id, long amount) {
        requireRole(actor, Role.BIDDER);
        Auction auction = required(id);
        long now = clock.millis();
        if (auction.advance(now)) changed(auction);
        auction.bid(actor.getUsername(), amount, now, false);
        applyAutoBids(auction, now);
        changed(auction);
        return auction.view();
    }

    /** Compatibility path for older clients: use the seller-defined minimum step. */
    public synchronized AuctionView registerAuto(User actor, String id, long maxBid) {
        return registerAuto(actor, id, maxBid, required(id).getMinimumIncrement());
    }

    public synchronized AuctionView registerAuto(User actor, String id, long maxBid, long increment) {
        requireRole(actor, Role.BIDDER);
        Auction auction = required(id);
        long now = clock.millis();
        if (auction.advance(now)) changed(auction);
        if (auction.getStatus() != Status.RUNNING) throw new IllegalStateException("Auction is not running");
        if (actor.getUsername().equals(auction.getSeller()))
            throw new IllegalArgumentException("Seller cannot bid on own item");
        if (increment < auction.getMinimumIncrement())
            throw new IllegalArgumentException("Bước giá Auto-Bid phải ≥ "
                    + auction.getMinimumIncrement() + " VND (Seller quy định)");
        if (maxBid <= auction.getCurrentPrice() ||
                maxBid - auction.getCurrentPrice() < increment)
            throw new IllegalArgumentException("Giá tối đa phải đủ cho ít nhất một bước giá "
                    + increment + " VND tính từ giá hiện tại");
        long registeredAt = auction.getAutoBids().stream()
                .filter(a -> a.bidder().equals(actor.getUsername()))
                .mapToLong(AutoBid::createdAt).findFirst().orElseGet(() ->
                        Math.max(now, auction.getAutoBids().stream()
                                .mapToLong(AutoBid::createdAt).max().orElse(0L) + 1L));
        auction.putAutoBid(new AutoBid(actor.getUsername(), maxBid, increment, registeredAt));
        applyAutoBids(auction, now);
        changed(auction);
        return auction.view();
    }

    private void applyAutoBids(Auction auction, long now) {
        autoBidEngine.settle(auction, now);
    }

    /** Approval is a server-side authorization, never controlled by the client UI. */
    public synchronized AuctionView approve(User actor, String id) {
        requireRole(actor, Role.ADMIN);
        Auction auction = required(id);
        auction.approve(actor.getUsername(), clock.millis());
        changed(auction);
        return auction.view();
    }

    public synchronized AuctionView reject(User actor, String id, String reason) {
        requireRole(actor, Role.ADMIN);
        Auction auction = required(id);
        auction.reject(actor.getUsername(), reason, clock.millis());
        changed(auction);
        return auction.view();
    }

    public synchronized AuctionView cancel(User actor, String id) {
        Auction auction = required(id);
        requireActive(actor);
        if (actor.getRole() != Role.ADMIN &&
                !(actor.getRole() == Role.SELLER && actor.getUsername().equals(auction.getSeller())))
            throw new SecurityException("Only seller or admin may cancel");
        auction.cancel();
        changed(auction);
        return auction.view();
    }

    public synchronized AuctionView pay(User actor, String id) {
        requireRole(actor, Role.ADMIN);
        Auction auction = required(id);
        advanceAll();
        auction.markPaid();
        changed(auction);
        return auction.view();
    }

    /** Scheduled maintenance: OPEN -> RUNNING -> FINISHED, including after restart. */
    public synchronized void advanceAll() {
        List<Auction> modified = new ArrayList<>();
        long now = clock.millis();
        for (Auction auction : auctions.values()) if (auction.advance(now)) modified.add(auction);
        if (!modified.isEmpty()) {
            save();
            modified.forEach(a -> events.publish(a.view()));
        }
    }

    private void changed(Auction auction) {
        save();
        events.publish(auction.view());
    }

    private void save() {
        State state = new State();
        for (User user : users.values()) state.users.add(new State.UserData(user.getUsername(),
                user.getSalt(), user.getPasswordHash(), user.getRole()));
        state.suspendedUsers.addAll(suspendedUsers.stream().sorted().toList());
        for (Auction auction : auctions.values()) state.auctions.add(auction.snapshot());
        try { repository.save(state); }
        catch (IOException e) { throw new UncheckedIOException("Could not save state", e); }
    }

    private Auction required(String id) {
        Auction auction = auctions.get(id);
        if (auction == null) throw new IllegalArgumentException("Auction not found");
        return auction;
    }

    private void validateItem(String name, String description, long price, long start,
            long end, long minimumIncrement) {
        if (name == null || name.isBlank() || name.length() > 100 ||
                description == null || description.length() > 1000)
            throw new IllegalArgumentException("Name or description invalid");
        if (price < 1 || minimumIncrement < 1 || minimumIncrement > Long.MAX_VALUE - price
                || start < 1 || end <= start || end <= clock.millis())
            throw new IllegalArgumentException("Invalid price or auction time range (end must be in the future)");
    }

    private void requireRole(User actor, Role role) {
        requireActive(actor);
        if (actor.getRole() != role)
            throw new SecurityException("Requires " + role + " role");
    }

    private static void ensureOwner(User actor, Auction auction) {
        if (!actor.getUsername().equals(auction.getSeller()))
            throw new SecurityException("You do not own this auction");
    }
}
