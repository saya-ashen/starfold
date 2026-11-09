package dev.starfold.ground;

import java.util.BitSet;

/** Packet section indices, including the light engine's extra bottom/top sections. */
final class LightChanges {
    final BitSet sky = new BitSet();
    final BitSet block = new BitSet();

    void mark(boolean skylight, int section) {
        (skylight ? sky : block).set(section);
    }

    void merge(LightChanges other) {
        sky.or(other.sky);
        block.or(other.block);
    }

    BitSet layer(int layer) {
        return layer == 0 ? sky : block;
    }
}
