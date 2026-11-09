package dev.starfold.render;

import dev.starfold.chart.PlanetShape;

import java.util.BitSet;

/** R retains native/detail ownership; G masks only coarse cover with complete resident tiles. */
final class GroundLodAtlas {
    static byte[] pack(PlanetShape shape, byte[] owners, BitSet complete) {
        var out = new byte[owners.length * 2];
        for (int i = 0; i < owners.length; i++) out[i * 2] = owners[i];
        int side = GroundOwnership.SIDE;
        for (int key = complete.nextSetBit(0); key >= 0; key = complete.nextSetBit(key + 1)) {
            int f = key / 16384, x = key % 128, z = key / 128 % 128;
            out[2 * (x + 1 + side * (z + 1 + side * f)) + 1] = (byte) 255;
        }
        var halo = GroundOwnership.halo(shape);
        for (int i = 0; i < halo.index().length; i++)
            if (complete.get((int) halo.key()[i])) out[2 * halo.index()[i] + 1] = (byte) 255;
        return out;
    }

    private GroundLodAtlas() {}
}
