package com.auction.service;

import com.auction.dto.AuctionView;
import com.auction.dto.State;
import com.auction.model.*;
import com.auction.repository.StateRepository;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Approval workflow, access checks, resubmission, and deadline behavior. */
class AuctionApprovalTest {
    private final AtomicLong timestamp = new AtomicLong(1_800_000_000_000L);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(timestamp.get()); }
        @Override public long millis() { return timestamp.get(); }
    };
    private State saved = new State();
    private final StateRepository repository = new StateRepository() {
        @Override public State load() { return saved; }
        @Override public void save(State next) { saved = next; }
    };
    private AuctionService service;
    private User admin, seller, bidder, stranger;

    @BeforeEach void setup() throws Exception {
        service = new AuctionService(repository, new EventBus(), clock);
        service.bootstrapAdmin("adminPassword123");
        admin = service.login("admin", "adminPassword123");
        seller = service.register("seller", "secret123", Role.SELLER);
        bidder = service.register("bidder", "secret123", Role.BIDDER);
        stranger = service.register("otherseller", "secret123", Role.SELLER);
    }

    private String submit(long start, long end) {
        return service.create(seller, "Laptop", "New Laptop", Category.ELECTRONICS,
                1000, start, end, 100).id();
    }

    @Test void pendingIsPrivateAndCannotBeBidOnOrAutoBidOrSelfApproved() {
        String id = submit(clock.millis() - 100, clock.millis() + 900_000);
        service.advanceAll();
        assertEquals(Status.PENDING, service.get(id).status());
        assertEquals(0, service.list(bidder).size());
        assertEquals(0, service.list(stranger).size());
        assertEquals(1, service.list(seller).size());
        assertEquals(1, service.list(admin).size());
        assertThrows(SecurityException.class, () -> service.get(bidder, id));
        assertThrows(IllegalStateException.class, () -> service.bid(bidder, id, 1100));
        assertThrows(IllegalStateException.class, () -> service.registerAuto(bidder, id, 3000, 100));
        assertThrows(SecurityException.class, () -> service.approve(seller, id));
        assertThrows(SecurityException.class, () -> service.reject(bidder, id, "No"));
    }

    @Test void approvedBecomesVisibleRunningAndCanHaveWinner() {
        String id = submit(clock.millis() - 100, clock.millis() + 60_000);
        assertEquals(Status.RUNNING, service.approve(admin, id).status());
        assertEquals(1, service.list(bidder).size());
        assertNotNull(service.get(bidder, id));
        service.bid(bidder, id, 1200);
        timestamp.addAndGet(120_000);
        service.advanceAll();
        assertEquals(Status.FINISHED, service.get(id).status());
        assertEquals("bidder", service.get(id).winner());
        assertThrows(IllegalStateException.class, () -> service.approve(admin, id));
    }

    @Test void rejectingRequiresReasonAndSellerCanFixAndResubmit() {
        String id = submit(clock.millis() + 1000, clock.millis() + 90_000);
        assertThrows(IllegalArgumentException.class, () -> service.reject(admin, id, " "));
        service.reject(admin, id, "Thiếu thông tin sản phẩm");
        assertEquals(Status.REJECTED, service.get(id).status());
        assertEquals("Thiếu thông tin sản phẩm", service.get(id).reviewNote());
        assertEquals(0, service.list(bidder).size());
        assertThrows(IllegalStateException.class, () -> service.approve(admin, id));
        service.edit(seller, id, "Laptop fixed", "Detailed", Category.ELECTRONICS,
                1000, clock.millis() + 1000, clock.millis() + 90_000, 100);
        assertEquals(Status.PENDING, service.get(id).status());
        assertNull(service.get(id).reviewNote());
        service.approve(admin, id);
        assertEquals(Status.OPEN, service.get(id).status());
        assertEquals(1, service.list(bidder).size());
    }

    @Test void editingApprovedUpcomingAuctionRevokesPriorApproval() {
        String id = submit(clock.millis() + 20_000, clock.millis() + 120_000);
        service.approve(admin, id);
        assertEquals(1, service.list(bidder).size());
        service.edit(seller, id, "Updated item", "Corrected", Category.ART,
                2000, clock.millis() + 30_000, clock.millis() + 150_000, 100);
        assertEquals(Status.PENDING, service.get(id).status());
        assertEquals(0, service.list(bidder).size());
    }

    @Test void cannotApproveExpiredSubmissionAndPendingDoesNotAutoClose() {
        String id = submit(clock.millis() - 10_000, clock.millis() + 2000);
        timestamp.addAndGet(3000);
        service.advanceAll();
        assertEquals(Status.PENDING, service.get(id).status());
        assertThrows(IllegalStateException.class, () -> service.approve(admin, id));
        service.edit(seller, id, "Fixed", "New time", Category.ART,
                1000, clock.millis() + 5000, clock.millis() + 50_000, 100);
        assertEquals(Status.OPEN, service.approve(admin, id).status());
    }

    @Test void persistenceRestoresReviewStatusAndReason() throws Exception {
        String id = submit(clock.millis(), clock.millis() + 600_000);
        service.reject(admin, id, "Ảnh chưa rõ");
        AuctionService restored = new AuctionService(repository, new EventBus(), clock);
        assertEquals(Status.REJECTED, restored.get(id).status());
        assertEquals("Ảnh chưa rõ", restored.get(id).reviewNote());
        assertEquals("admin", restored.get(id).reviewedBy());
    }

    @Test void oldSnapshotsRemainApprovedAndReadOnlyAfterClosing() {
        var legacy = new State.AuctionData("legacy", "Legacy", "", Category.ART, "seller", 100,
                100, clock.millis() - 60_000, clock.millis() + 60_000, Status.OPEN,
                null, List.of(), List.of(), 1);
        Auction auction = Auction.restore(legacy);
        assertEquals(Status.OPEN, auction.getStatus());
        auction.advance(clock.millis());
        assertEquals(Status.RUNNING, auction.getStatus());
    }
}
