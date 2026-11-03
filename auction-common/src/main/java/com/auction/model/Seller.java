package com.auction.model;

public final class Seller extends User {
    public Seller(String username, String salt, String passwordHash) {
        super(username, salt, passwordHash);
    }

    @Override public Role getRole() { return Role.SELLER; }
}
