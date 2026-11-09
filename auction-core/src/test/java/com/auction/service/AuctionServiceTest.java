package com.auction.service;

import com.auction.dto.AuctionView;
import com.auction.model.*;
import com.auction.repository.FileStateRepository;
import java.nio.file.Path;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AuctionServiceTest {
    @TempDir Path folder;
    private EventBus events;
    private AuctionService service;
    private MutableClock clock;
    private User seller, alice, bob, admin;

    @BeforeEach void setUp() throws Exception {
        clock = new MutableClock(1_800_000_000_000L);
        events = new EventBus();
        service = new AuctionService(new FileStateRepository(folder.resolve("state.json")), events, clock);
        seller = service.register("seller", "secret123", Role.SELLER);
        alice = service.register("alice", "secret123", Role.BIDDER);
        bob = service.register("bob", "secret123", Role.BIDDER);
        service.bootstrapAdmin("secretAdmin123");
        admin = service.login("admin", "secretAdmin123");
    }

    @AfterEach void tearDown() { events.close(); }

    private String auction(long endOffset) {
        return createApproved(seller, "Laptop", "16GB RAM", Category.ELECTRONICS,
                100, clock.millis() - 60_000, clock.millis() + endOffset, 1).id();
    }

    private AuctionView createApproved(User owner, String name, String description, Category category,
            long price, long startsAt, long endsAt, long increment) {
        AuctionView pending = service.create(owner, name, description, category, price,
                startsAt, endsAt, increment);
        assertEquals(Status.PENDING, pending.status());
        return service.approve(admin, pending.id());
    }

    @Test void validatesBidAndPermissions() {
        String id = auction(600_000);
        assertThrows(IllegalArgumentException.class, () -> service.bid(alice, id, 100));
        assertThrows(SecurityException.class, () -> service.bid(seller, id, 120));
        service.bid(alice, id, 120);
        assertEquals("alice", service.get(id).highestBidder());
        assertThrows(IllegalArgumentException.class, () -> service.bid(alice, id, 130));
        assertThrows(IllegalArgumentException.class, () -> service.bid(bob, id, 119));
        assertEquals(120, service.get(id).currentPrice());
    }

    @Test void concurrentBidsCannotRollback() throws Exception {
        String id = auction(600_000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> tasks = List.of(
                () -> { start.await(); try { service.bid(alice, id, 101); } catch (IllegalArgumentException ignored) { } return null; },
                () -> { start.await(); service.bid(bob, id, 102); return null; });
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) futures.add(pool.submit(task));
            start.countDown();
            for (Future<Void> future : futures) future.get(10, TimeUnit.SECONDS);
            AuctionView view = service.get(id);
            assertEquals(102, view.currentPrice());
            assertEquals("bob", view.highestBidder());
            long previous = 100;
            for (BidTransaction bid : view.history()) {
                assertTrue(bid.amount() > previous);
                previous = bid.amount();
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void closingAndWinnerAreAutomatic() {
        String id = auction(30_000);
        service.bid(alice, id, 150);
        clock.forward(100_000); // Includes an anti-sniping 60s extension.
        service.advanceAll();
        assertEquals(Status.FINISHED, service.get(id).status());
        assertEquals("alice", service.get(id).highestBidder());
        assertThrows(IllegalStateException.class, () -> service.bid(bob, id, 200));
    }

    @Test void antiSnipingExtendsDeadline() {
        String id = auction(10_000);
        long original = service.get(id).endsAt();
        service.bid(alice, id, 150);
        assertEquals(original + 60_000, service.get(id).endsAt());
    }

    @Test void autoBidRespondsWithinCeiling() {
        String id = auction(600_000);
        service.registerAuto(alice, id, 500);
        assertEquals(101, service.get(id).currentPrice());
        service.bid(bob, id, 200);
        assertEquals(201, service.get(id).currentPrice());
        assertEquals("alice", service.get(id).highestBidder());
        assertTrue(service.get(id).history().getLast().automatic());
    }

    @Test void persistenceAndPasswordHashing() throws Exception {
        String id = auction(600_000);
        service.bid(alice, id, 220);
        AuctionService restored = new AuctionService(
                new FileStateRepository(folder.resolve("state.json")), new EventBus(), clock);
        assertEquals(220, restored.get(id).currentPrice());
        assertEquals(Role.BIDDER, restored.login("ALICE", "secret123").getRole());
        assertThrows(SecurityException.class, () -> restored.login("alice", "wrong"));
    }

    @Test void sellerCanEditUpcomingAndAdminCanMarkPaid() {
        String id = createApproved(seller, "Art", "Painting", Category.ART,
                100, clock.millis() + 60_000, clock.millis() + 600_000, 1).id();
        service.edit(seller, id, "Art 2", "New", Category.ART, 200,
                clock.millis() + 50_000, clock.millis() + 700_000, 1);
        service.approve(admin, id);
        assertEquals("Art 2", service.get(id).name());
        assertThrows(SecurityException.class, () -> service.edit(alice, id, "X", "Y",
                Category.ART, 1, clock.millis() + 1, clock.millis() + 5, 1));
        clock.forward(60_000);
        service.advanceAll();
        service.bid(alice, id, 250);
        clock.forward(700_001);
        service.advanceAll();
        service.bootstrapAdmin("admin-secret-123");
        User admin = service.login("admin", "secretAdmin123");
        service.pay(admin, id);
        assertEquals(Status.PAID, service.get(id).status());
    }


    @Test void auctionCannotAcceptBidsBeforeStartOrAfterFinish() {
        String id = createApproved(seller, "Camera", "test", Category.ELECTRONICS,
                1_000, clock.millis() + 60_000, clock.millis() + 120_000, 100).id();
        assertEquals(Status.OPEN, service.get(id).status());
        assertNull(service.get(id).winner());
        assertThrows(IllegalStateException.class, () -> service.bid(alice, id, 1_100));
        clock.forward(60_000);
        service.advanceAll();
        assertEquals(Status.RUNNING, service.get(id).status());
        service.bid(alice, id, 1_500);
        assertEquals("alice", service.get(id).highestBidder());
        assertNull(service.get(id).winner()); // leader is NOT yet the winner
        clock.forward(120_000);
        service.advanceAll();
        assertEquals(Status.FINISHED, service.get(id).status());
        assertEquals("alice", service.get(id).winner());
        assertThrows(IllegalStateException.class, () -> service.bid(bob, id, 2_000));
    }

    @Test void auctionWithoutAnyBidsHasNoWinnerAndCannotBePaid() {
        String id = auction(60_000);
        clock.forward(60_001);
        service.advanceAll();
        assertEquals(Status.FINISHED, service.get(id).status());
        assertNull(service.get(id).winner());
        service.bootstrapAdmin("secretAdmin123");
        User admin = service.login("admin", "secretAdmin123");
        assertThrows(IllegalStateException.class, () -> service.pay(admin, id));
    }

    @Test void cancelledAuctionHasNoWinnerEvenIfItUsedToHaveLeader() {
        String id = auction(600_000);
        service.bid(alice, id, 150);
        service.cancel(seller, id);
        assertEquals(Status.CANCELED, service.get(id).status());
        assertEquals("alice", service.get(id).highestBidder()); // historic data preserved
        assertNull(service.get(id).winner());
        assertThrows(IllegalStateException.class, () -> service.bid(bob, id, 200));
    }

    @Test void adminUserManagementIsRestrictedAndSuspensionPersists() throws Exception {
        service.bootstrapAdmin("secretAdmin123");
        User admin = service.login("admin", "secretAdmin123");
        assertTrue(service.listUsers(admin).stream().anyMatch(u ->
                u.username().equals("alice") && u.role() == Role.BIDDER && !u.suspended()));
        assertThrows(SecurityException.class, () -> service.listUsers(seller));
        assertThrows(SecurityException.class, () -> service.setUserSuspended(alice, "bob", true));
        assertThrows(SecurityException.class, () -> service.setUserSuspended(admin, "admin", true));
        service.setUserSuspended(admin, "alice", true);
        assertThrows(SecurityException.class, () -> service.bid(alice, auction(600_000), 120));
        assertThrows(SecurityException.class, () -> service.login("alice", "secret123"));
        AuctionService restored = new AuctionService(
                new FileStateRepository(folder.resolve("state.json")), new EventBus(), clock);
        assertThrows(SecurityException.class, () -> restored.login("alice", "secret123"));
        assertTrue(restored.listUsers(restored.login("admin", "secretAdmin123")).stream()
                .anyMatch(u -> u.username().equals("alice") && u.suspended()));
        service.setUserSuspended(admin, "alice", false);
        assertEquals("alice", service.login("alice", "secret123").getUsername());
    }

    @Test void suspendingAutoBidderStopsFutureAutomaticBidsButKeepsBidHistory() {
        String id = auction(600_000);
        service.bootstrapAdmin("secretAdmin123");
        User admin = service.login("admin", "secretAdmin123");
        service.registerAuto(alice, id, 500);
        assertEquals("alice", service.get(id).highestBidder());
        service.setUserSuspended(admin, "alice", true);
        assertFalse(service.get(id).history().isEmpty());
        service.bid(bob, id, 200);
        assertEquals("bob", service.get(id).highestBidder());
        assertEquals(200, service.get(id).currentPrice());
    }

    @Test void earlierRegisteredAutoBidWinsEqualCeilings() {
        String id = auction(600_000);
        service.registerAuto(alice, id, 500);
        service.registerAuto(bob, id, 500);
        var result = service.get(id);
        assertEquals("alice", result.highestBidder());
        assertEquals(500, result.currentPrice());
        for (BidTransaction bid : result.history()) assertTrue(bid.amount() <= 500);
    }

    @Test void higherCeilingBeatsExistingProxyWithoutExceedingLimit() {
        String id = auction(600_000);
        service.registerAuto(alice, id, 500);
        service.registerAuto(bob, id, 700);
        var result = service.get(id);
        assertEquals("bob", result.highestBidder());
        assertEquals(501, result.currentPrice());
        assertTrue(result.history().stream().mapToLong(BidTransaction::amount).max().orElse(0L) <= 700);
    }

    @Test void manyConcurrentBiddersProduceOneLeaderAndMonotonicHistory() throws Exception {
        String id = auction(600_000);
        int count = 32;
        var actors = new ArrayList<User>();
        for (int i = 0; i < count; i++)
            actors.add(service.register("bidder" + i, "securePass123", Role.BIDDER));
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch startingGun = new CountDownLatch(1);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                User actor = actors.get(i);
                long amount = 101 + i;
                jobs.add(pool.submit(() -> {
                    try {
                        startingGun.await();
                        service.bid(actor, id, amount);
                    } catch (IllegalArgumentException ignored) {
                        // Bid lost the race and was correctly rejected.
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ex);
                    }
                }));
            }
            startingGun.countDown();
            for (Future<?> job : jobs) job.get(15, TimeUnit.SECONDS);
            AuctionView view = service.get(id);
            assertEquals(100 + count, view.currentPrice());
            assertEquals("bidder" + (count - 1), view.highestBidder());
            long previous = 100;
            for (BidTransaction tx : view.history()) {
                assertTrue(tx.amount() > previous);
                previous = tx.amount();
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void autoBidNeverCrossesItsMaximum() {
        String id = auction(600_000);
        service.registerAuto(alice, id, 150);
        service.bid(bob, id, 140);
        assertEquals(141, service.get(id).currentPrice());
        assertEquals("alice", service.get(id).highestBidder());
        User third = service.register("third", "securePass123", Role.BIDDER);
        service.bid(third, id, 151);
        assertEquals("third", service.get(id).highestBidder());
        assertEquals(151, service.get(id).currentPrice());
    }

    @Test void sellerSetsMinimumIncrementForManualBids() {
        String id = createApproved(seller, "Camera", "Mirrorless", Category.ELECTRONICS,
                1_000_000, clock.millis() - 1_000, clock.millis() + 600_000, 100_000).id();
        assertEquals(100_000, service.get(id).minimumIncrement());
        assertThrows(IllegalArgumentException.class, () -> service.bid(alice, id, 1_050_000));
        service.bid(alice, id, 1_100_000);
        assertThrows(IllegalArgumentException.class, () -> service.bid(bob, id, 1_199_999));
        service.bid(bob, id, 1_200_000);
        assertEquals("bob", service.get(id).highestBidder());
    }

    @Test void bidderMayRaiseMoreThanSellersMinimum() {
        String id = createApproved(seller, "Tablet", "test", Category.ELECTRONICS,
                1_000_000, clock.millis() - 1_000, clock.millis() + 600_000, 100_000).id();
        service.bid(alice, id, 1_500_000); // manual step = 500k (seller minimum = 100k)
        assertEquals(1_500_000, service.get(id).currentPrice());
        assertThrows(IllegalArgumentException.class, () -> service.bid(bob, id, 1_550_000));
        service.bid(bob, id, 1_900_000); // manual step = 400k
        assertEquals(1_900_000, service.get(id).currentPrice());
    }

    @Test void bidderChosenAutoStepCannotBeBelowSellerMinimum() {
        String id = createApproved(seller, "Phone", "test", Category.ELECTRONICS,
                1_000, clock.millis() - 1_000, clock.millis() + 600_000, 100).id();
        assertThrows(IllegalArgumentException.class, () -> service.registerAuto(alice, id, 2_000, 50));
        assertThrows(IllegalArgumentException.class, () -> service.registerAuto(alice, id, 1_200, 300));
        service.registerAuto(alice, id, 2_000, 300);
        assertEquals(1_300, service.get(id).currentPrice());
    }

    @Test void differentAutoStepsRespectCeilingsAndPriority() {
        String id = createApproved(seller, "Laptop", "test", Category.ELECTRONICS,
                1_000, clock.millis() - 1_000, clock.millis() + 600_000, 100).id();
        service.registerAuto(alice, id, 2_000, 300);
        assertEquals(1_300, service.get(id).currentPrice());
        service.registerAuto(bob, id, 2_500, 200);
        assertEquals("bob", service.get(id).highestBidder());
        assertEquals(2_200, service.get(id).currentPrice());
        for (BidTransaction tx : service.get(id).history()) assertTrue(tx.amount() <= 2_500);
    }

    @Test void equalCeilingEarlierAutoBidWinsEvenNearPreferredStepLimit() {
        String id = createApproved(seller, "Camera", "test", Category.ELECTRONICS,
                1_000, clock.millis() - 1_000, clock.millis() + 600_000, 100).id();
        service.registerAuto(alice, id, 2_000, 600);
        service.registerAuto(bob, id, 2_000, 100);
        assertEquals("alice", service.get(id).highestBidder());
        assertEquals(2_000, service.get(id).currentPrice());
    }

    @Test void chosenAutoStepPersistsAcrossRestart() throws Exception {
        String id = createApproved(seller, "Monitor", "test", Category.ELECTRONICS,
                1_000, clock.millis() - 1_000, clock.millis() + 600_000, 100).id();
        service.registerAuto(alice, id, 3_000, 400);
        AuctionService restored = new AuctionService(
                new FileStateRepository(folder.resolve("state.json")), new EventBus(), clock);
        assertEquals(400, restored.get(id).history().getFirst().amount() - 1_000);
        service.bid(bob, id, 1_600);
        assertEquals(2_000, service.get(id).currentPrice());
        restored.bid(restored.login("bob", "secret123"), id, 1_600);
        assertEquals(2_000, restored.get(id).currentPrice());
    }

    @Test void legacyAutoBidWithoutStepUsesSellerMinimum() {
        var old = new com.auction.dto.State.AuctionData("old", "Laptop", "", Category.ELECTRONICS,
                "seller", 1_000, 1_100, clock.millis() - 1_000, clock.millis() + 600_000,
                Status.RUNNING, "alice", List.of(new BidTransaction("alice", 1_100, clock.millis(), true)),
                List.of(new AutoBid("alice", 2_000, 0, clock.millis())), 100);
        Auction restored = Auction.restore(old);
        assertEquals(100, restored.getAutoBids().getFirst().increment());
    }

    @Test void sellerIncrementAppliesToAllAutoBidsAndTies() {
        String id = createApproved(seller, "Laptop", "test", Category.ELECTRONICS,
                79_000_000, clock.millis() - 1_000, clock.millis() + 600_000, 100_000).id();
        assertThrows(IllegalArgumentException.class,
                () -> service.registerAuto(alice, id, 79_099_999));
        service.registerAuto(alice, id, 80_000_000);
        assertEquals(79_100_000, service.get(id).currentPrice());
        service.registerAuto(bob, id, 80_000_000);
        assertEquals(80_000_000, service.get(id).currentPrice());
        assertEquals("alice", service.get(id).highestBidder());
    }

    @Test void higherAutoBidRespectsSellerIncrement() {
        String id = createApproved(seller, "Phone", "test", Category.ELECTRONICS,
                1000, clock.millis() - 1_000, clock.millis() + 600_000, 100).id();
        service.registerAuto(alice, id, 1500);
        service.registerAuto(bob, id, 1900);
        assertEquals("bob", service.get(id).highestBidder());
        assertEquals(1600, service.get(id).currentPrice());
    }

    @Test void incrementEditableOnlyBeforeStartAndPersists() throws Exception {
        String id = createApproved(seller, "Painting", "test", Category.ART,
                100, clock.millis() + 60_000, clock.millis() + 600_000, 10).id();
        service.edit(seller, id, "Painting", "test", Category.ART, 200,
                clock.millis() + 60_000, clock.millis() + 600_000, 50);
        service.approve(admin, id);
        assertEquals(50, service.get(id).minimumIncrement());
        AuctionService restored = new AuctionService(
                new FileStateRepository(folder.resolve("state.json")), new EventBus(), clock);
        assertEquals(50, restored.get(id).minimumIncrement());
        clock.forward(70_000);
        service.advanceAll();
        assertThrows(IllegalStateException.class, () -> service.edit(seller, id,
                "Painting", "test", Category.ART, 200,
                clock.millis() + 60_000, clock.millis() + 600_000, 100));
        assertThrows(SecurityException.class, () -> createApproved(alice,
                "X", "Test", Category.ART, 100, clock.millis(), clock.millis() + 100_000, 10));
    }

    @Test void legacySnapshotWithoutIncrementDefaultsToOne() {
        String id = "legacy";
        var legacy = new com.auction.dto.State.AuctionData(id, "Old", "", Category.ART,
                "seller", 100, 100, clock.millis() - 1_000, clock.millis() + 600_000,
                Status.RUNNING, null, List.of(), List.of(), 0);
        Auction restored = Auction.restore(legacy);
        assertEquals(1, restored.view().minimumIncrement());
    }

    @Test void concurrentBidsRespectSellerIncrement() throws Exception {
        String id = createApproved(seller, "Laptop", "test", Category.ELECTRONICS,
                100, clock.millis() - 1_000, clock.millis() + 600_000, 10).id();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> {
                try { latch.await(); service.bid(alice, id, 110); }
                catch (IllegalArgumentException ignored) { }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            Future<?> second = pool.submit(() -> {
                try { latch.await(); service.bid(bob, id, 120); }
                catch (IllegalArgumentException ignored) { }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            latch.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            var view = service.get(id);
            assertEquals(120, view.currentPrice());
            long previous = 100;
            for (var bid : view.history()) {
                assertTrue(bid.amount() - previous >= 10);
                previous = bid.amount();
            }
        } finally { pool.shutdownNow(); }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong value;
        MutableClock(long millis) { value = new AtomicLong(millis); }
        void forward(long millis) { value.addAndGet(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(value.get()); }
        @Override public long millis() { return value.get(); }
    }
}
