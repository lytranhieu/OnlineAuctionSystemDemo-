package com.auction.model;

/** Factory Method: creators override item construction, callers use the base type. */
public abstract class ItemFactory {
    public abstract Item create(String id, String name, String description);

    public static Item create(Category category, String id, String name, String description) {
        ItemFactory factory = switch (category) {
            case ELECTRONICS -> new ElectronicsFactory();
            case ART -> new ArtFactory();
            case VEHICLE -> new VehicleFactory();
        };
        return factory.create(id, name, description);
    }

    private static final class ElectronicsFactory extends ItemFactory {
        @Override public Item create(String id, String name, String description) {
            return new Electronics(id, name, description);
        }
    }

    private static final class ArtFactory extends ItemFactory {
        @Override public Item create(String id, String name, String description) {
            return new Art(id, name, description);
        }
    }

    private static final class VehicleFactory extends ItemFactory {
        @Override public Item create(String id, String name, String description) {
            return new Vehicle(id, name, description);
        }
    }
}
