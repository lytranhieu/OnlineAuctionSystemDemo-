package com.auction.service;

import com.auction.model.AutoBid;
import com.auction.model.Auction;
import java.util.Comparator;
import java.util.PriorityQueue;

/** Server-only auto-bid strategy; called under AuctionService's mutation monitor. */
public final class AutoBidEngine {
    private static final Comparator<AutoBid> ORDER =
            Comparator.comparingLong(AutoBid::maxBid).reversed()
                    .thenComparingLong(AutoBid::createdAt)
                    .thenComparing(AutoBid::bidder);

    /** Determine a single price adjustment that respects ceilings and registration order. */
    public boolean settle(Auction auction, long now) {
        PriorityQueue<AutoBid> eligible = new PriorityQueue<>(ORDER);
        for (AutoBid auto : auction.getAutoBids()) {
            // A preferred step may be shortened at the ceiling, but never
            // below the seller's required minimum. Keep tie priority stable.
            if (auto.maxBid() > auction.getCurrentPrice()
                    && auto.maxBid() - auction.getCurrentPrice() >= auction.getMinimumIncrement())
                eligible.offer(auto);
        }
        AutoBid first = eligible.poll();
        if (first == null) return false;
        String leader = auction.getHighestBidder();
        AutoBid rival = eligible.poll();
        if (first.bidder().equals(leader)) {
            // The leader should only raise its price when an opposing auto-bid
            // actually supplies competition; do not charge the leader for itself.
            if (rival == null || rival.maxBid() <= auction.getCurrentPrice()) return false;
        }
        long current = auction.getCurrentPrice();
        long remaining = first.maxBid() - current; // positive, no overflow if current >= 0
        long step = Math.max(auction.getMinimumIncrement(), first.increment());
        if (remaining < auction.getMinimumIncrement()) return false;
        // Respect the ceiling even when the preferred step would exceed it.
        long amount = current + Math.min(step, remaining);
        if (rival != null && !rival.bidder().equals(first.bidder())) {
            // Proxy uses the WINNER'S preferred step, but the final step may
            // be smaller at the ceiling as long as it satisfies seller minimum.
            long nextAboveRival = rival.maxBid() > Long.MAX_VALUE - step
                    ? Long.MAX_VALUE : rival.maxBid() + step;
            // Equal ceilings use earlier registration as the tie-breaker.
            amount = Math.min(first.maxBid(), Math.max(amount, nextAboveRival));
        }
        auction.bid(first.bidder(), amount, now, true);
        return true;
    }
}
