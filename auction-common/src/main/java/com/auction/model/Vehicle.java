package com.auction.model;

public final class Vehicle extends Item {
    public Vehicle(String id, String name, String description) {
        super(id, name, description);
    }

    @Override public Category getCategory() { return Category.VEHICLE; }
}
