package com.auction.model;

public final class Electronics extends Item {
    public Electronics(String id, String name, String description) {
        super(id, name, description);
    }

    @Override public Category getCategory() { return Category.ELECTRONICS; }
}
