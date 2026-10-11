package dev.starfold;

import java.util.BitSet;

/** Published fine coverage cannot outlive the actually available native/detail meshes. */
public final class NativeGroundSelection {
    public static BitSet retain(BitSet requested,BitSet available){var result=(BitSet)requested.clone();result.and(available);return result;}
    public static boolean detail(long key,BitSet selected,BitSet vanilla){return selected.get((int)key)&&!vanilla.get((int)key);}
    private NativeGroundSelection(){}
}
