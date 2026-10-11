package dev.starfold;

import java.util.BitSet;

/** Readiness for handing a complete snapshot volume to Minecraft, never just a heightfield. */
public final class NativeGroundHandoff {
    public static boolean volumeReady(BitSet occupied, BitSet compiled) {
        var missing = (BitSet) occupied.clone();
        missing.andNot(compiled);
        return missing.isEmpty();
    }
    /** Reject a stale batch that would temporarily withdraw an already displayed fine tile. */
    public static boolean preservesSelected(NativeGroundOwnership.Snapshot current,
            NativeGroundOwnership.Snapshot requested, NativeGroundOwnership.Snapshot available) {
        for (long key : current.keys())
            if (available.keys().contains(key) && !requested.keys().contains(key)) return false;
        return true;
    }
    private NativeGroundHandoff() {}
}
