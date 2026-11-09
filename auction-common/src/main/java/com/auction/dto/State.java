package com.auction.dto;

import com.auction.model.*;
import java.util.ArrayList;
import java.util.List;

/** Simple persistence DTO avoids serializing abstract classes and locks. */
public final class State {
    public List<UserData> users = new ArrayList<>();
    public List<AuctionData> auctions = new ArrayList<>();
    /** Usernames blocked by administrator. Null in old JSON backups means no blocks. */
    public List<String> suspendedUsers = new ArrayList<>();

    public record UserData(String username, String salt, String passwordHash, Role role) { }
    public record AuctionData(String id, String name, String description, Category category,
            String seller, long startingPrice, long currentPrice, long startsAt, long endsAt,
            Status status, String highestBidder, List<BidTransaction> history,
            List<AutoBid> autoBids, long minimumIncrement,
            String reviewNote, String reviewedBy, long reviewedAt) {
        /** Constructor retained for legacy test fixtures and saved models. */
        public AuctionData(String id, String name, String description, Category category,
                String seller, long startingPrice, long currentPrice, long startsAt, long endsAt,
                Status status, String highestBidder, List<BidTransaction> history,
                List<AutoBid> autoBids, long minimumIncrement) {
            this(id, name, description, category, seller, startingPrice, currentPrice,
                    startsAt, endsAt, status, highestBidder, history, autoBids,
                    minimumIncrement, null, null, 0L);
        }
    }
}
