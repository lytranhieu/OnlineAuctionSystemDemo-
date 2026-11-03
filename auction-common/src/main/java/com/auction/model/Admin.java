package com.auction.model;

public final class Admin extends User {
    public Admin(String username, String salt, String passwordHash) {
        super(username, salt, passwordHash);
    }

    @Override public Role getRole() { return Role.ADMIN; }
}
