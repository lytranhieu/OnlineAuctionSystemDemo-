package com.auction.model;

public final class Art extends Item {
    public Art(String id, String name, String description) {
        super(id, name, description);
    }

    @Override public Category getCategory() { return Category.ART; }
}
