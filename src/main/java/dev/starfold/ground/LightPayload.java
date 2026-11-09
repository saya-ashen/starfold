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

    /** Probe client state: absent leaves the old section alone; empty explicitly clears it. */
    static byte[] apply(byte[] previous, byte[] delta) {
        var old = read(previous);
        var change = read(delta);
        var masks = new BitSet[] {new BitSet(), new BitSet(), new BitSet(), new BitSet()};
        var values = List.of(new ArrayList<byte[]>(), new ArrayList<byte[]>());
        for (int layer = 0; layer < 2; layer++) {
            int oi = 0, ci = 0;
            int count = Math.max(Math.max(old.masks[layer].length(), old.masks[layer + 2].length()),
                    Math.max(change.masks[layer].length(), change.masks[layer + 2].length()));
            for (int s = 0; s < count; s++) {
                byte[] ov = old.masks[layer].get(s) ? old.values.get(layer).get(oi++) : null;
                byte[] cv = change.masks[layer].get(s) ? change.values.get(layer).get(ci++) : null;
                boolean updated = cv != null || change.masks[layer + 2].get(s);
                byte[] value = updated ? cv : ov;
                if (value != null) {
                    masks[layer].set(s);
                    values.get(layer).add(value);
                } else if (updated || old.masks[layer + 2].get(s)) masks[layer + 2].set(s);
            }
        }
        var b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (var mask : masks) b.writeBitSet(mask);
            for (var layer : values) b.writeCollection(layer, (buf, bytes) -> buf.writeByteArray(bytes));
            var bytes = new byte[b.writerIndex()];
            b.getBytes(0, bytes);
            return bytes;
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
