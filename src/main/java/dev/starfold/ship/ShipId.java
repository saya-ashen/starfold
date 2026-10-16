package dev.starfold.ship;

/** A world's ship number. Removed ships never give their number to another hull. */
public record ShipId(int region, int index) {
    public ShipId {
        if (region != 0 || index < 0) throw new IllegalArgumentException("Ship id");
    }
}
