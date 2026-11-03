package com.auction.dto;

import com.auction.model.*;
import java.util.List;

/** Immutable data transferred to clients (no mutable domain entities). */
public record AuctionView(
        String id, String name, String description, Category category, String seller,
        long startingPrice, long currentPrice, long startsAt, long endsAt,
        Status status, String highestBidder, List<BidTransaction> history,
        long minimumIncrement) {
    /** The winning bidder exists only when a non-cancelled auction has ended with bids. */
    public String winner() {
        return (status == Status.FINISHED || status == Status.PAID) ? highestBidder : null;
    }
}
