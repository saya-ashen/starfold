package dev.starfold.mining;

/** Stable persisted/menu ordinals; append new entries rather than reordering. */
enum OilStatus {
    IDLE,
    SURVEYING,
    FOUND,
    DRY,
    BUILDING,
    WELL_READY,
    PUMPING,
    PAUSED,
    NO_ENERGY,
    FULL,
    UNLOADED,
    BLOCKED,
    PROTECTED,
    OWNER_AWAY,
    UNSUPPORTED,
    CDG_MISSING,
    CDG_ERROR,
    FUEL_OUTPUT_FULL;

    String key() {
        return "oil.starfold_mining.status." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
