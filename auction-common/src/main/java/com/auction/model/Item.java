package com.auction.model;

/** Polymorphic item: each subtype provides its category. */
public abstract class Item extends Entity {
    private String name;
    private String description;

    protected Item(String id, String name, String description) {
        super(id);
        this.name = name;
        this.description = description;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public abstract Category getCategory();

    public void update(String name, String description) {
        this.name = name;
        this.description = description;
    }
}
