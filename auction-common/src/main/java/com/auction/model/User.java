package com.auction.model;

public abstract class User extends Entity {
    private final String username;
    private final String salt;
    private final String passwordHash;

    protected User(String username, String salt, String passwordHash) {
        super(username);
        this.username = username;
        this.salt = salt;
        this.passwordHash = passwordHash;
    }

    public String getUsername() { return username; }
    public String getSalt() { return salt; }
    public String getPasswordHash() { return passwordHash; }
    public abstract Role getRole();
}
