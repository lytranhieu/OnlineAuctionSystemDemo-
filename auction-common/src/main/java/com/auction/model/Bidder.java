package com.auction.model;

public final class Bidder extends User {
    public Bidder(String username, String salt, String passwordHash) {
        super(username, salt, passwordHash);
    }

    @Override public Role getRole() { return Role.BIDDER; }
}
