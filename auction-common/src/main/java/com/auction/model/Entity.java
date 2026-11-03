package com.auction.model;

import java.util.Objects;

/** Base class for domain objects with a stable identifier. */
public abstract class Entity {
    private final String id;

    protected Entity(String id) {
        this.id = Objects.requireNonNull(id);
    }

    public final String getId() { return id; }
}
