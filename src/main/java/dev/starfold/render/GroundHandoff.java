package dev.starfold.render;

import java.util.BitSet;

/** Readiness for handing a complete snapshot volume to Minecraft, never just a heightfield. */
public final class GroundHandoff {
    public static boolean volumeReady(BitSet occupied, BitSet compiled) {
        var missing = (BitSet) occupied.clone();
        missing.andNot(compiled);
        return missing.isEmpty();
    }

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
