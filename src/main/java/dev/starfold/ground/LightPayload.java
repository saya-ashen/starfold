package dev.starfold.ground;

import io.netty.buffer.Unpooled;

import net.minecraft.network.FriendlyByteBuf;

import java.util.*;

/** Probe comparison by section nibble values: an allocated zero array equals an explicit empty layer,
 * but an absent update does not. Vanilla need not canonicalize its DataLayer representation. */
final class LightPayload {
    private record Layers(BitSet[] masks, List<List<byte[]>> values) {}

    private static Layers read(byte[] bytes) {
        var b = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            var masks =
                    new BitSet[] {b.readBitSet(), b.readBitSet(), b.readBitSet(), b.readBitSet()};
            return new Layers(
                    masks,
                    List.of(
                            b.readList(buf -> buf.readByteArray()),
                            b.readList(buf -> buf.readByteArray())));
        } finally {
            b.release();
        }
    }

    static boolean same(byte[] emitted, byte[] reference) {
        var a = read(emitted);
        var b = read(reference);
        var zero = new byte[2048];
        for (int layer = 0; layer < 2; layer++) {
            var knownA = (BitSet) a.masks[layer].clone();
            knownA.or(a.masks[layer + 2]);
            var knownB = (BitSet) b.masks[layer].clone();
            knownB.or(b.masks[layer + 2]);
            if (!knownA.equals(knownB)) return false;
            int ai = 0, bi = 0;
            for (int s = knownA.nextSetBit(0); s >= 0; s = knownA.nextSetBit(s + 1)) {
                var av = a.masks[layer].get(s) ? a.values.get(layer).get(ai++) : zero;
                var bv = b.masks[layer].get(s) ? b.values.get(layer).get(bi++) : zero;
                if (!Arrays.equals(av, bv)) return false;
            }
        }
        return true;
    }

    private LightPayload() {}
}
