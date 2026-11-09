package dev.starfold.render;

/** Readiness for handing a complete snapshot volume to Minecraft, never just a heightfield. */
public final class GroundHandoff {
    /** Reject a stale batch that would temporarily withdraw an already displayed fine tile. */
    public static boolean preservesSelected(
            GroundOwnership.Snapshot current,
            GroundOwnership.Snapshot requested,
            GroundOwnership.Snapshot available) {
        for (long key : current.keys())
            if (available.keys().contains(key) && !requested.keys().contains(key)) return false;
        return true;
    }

    private GroundHandoff() {}
}
