package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.junit.jupiter.api.Test;
import java.util.*;

class IncrementalLightTest {
    private static byte[] packet(DataLayer[][] layers, LightChanges selected, boolean identity) {
        var masks = new BitSet[] {new BitSet(), new BitSet(), new BitSet(), new BitSet()};
        var updates = List.of(new ArrayList<byte[]>(), new ArrayList<byte[]>());
        var pos = new BlockPos.MutableBlockPos();
        for (int layer = 0; layer < 2; layer++)
            for (int s = 0; s < layers[layer].length; s++) {
                if (selected != null && !selected.layer(layer).get(s)) continue;
                var source = layers[layer][s];
                if (identity && source != null) {
                    ImageStream.identitySection(source, s, masks[layer], masks[layer + 2], updates.get(layer));
                } else {
                    var out = new DataLayer();
                    boolean lit = false;
                    for (int i = 0; i < 256; i++)
                        lit |= ImageStream.sampleColumn(out, i, source, i & 15, i >> 4,
                                new Fallback(layer == 0 ? 15 : 0), pos, i & 15, (s - 1) * 16, i >> 4);
                    ImageStream.lightSection(out, lit, s, masks[layer], masks[layer + 2], updates.get(layer));
                }
            }
        var b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (var m : masks) b.writeBitSet(m);
            for (var u : updates) b.writeCollection(u, (buf, a) -> buf.writeByteArray(a));
            var bytes = new byte[b.writerIndex()];
            b.getBytes(0, bytes);
            return bytes;
        } finally { b.release(); }
    }

    @Test
    void identityMatchesFullSamplerForEveryNibbleIncludingNullAndAllocatedZero() {
        var sky = new DataLayer[5];
        var block = new DataLayer[5];
        sky[0] = new DataLayer(15);
        sky[2] = new DataLayer(new byte[2048]);
        block[0] = new DataLayer();
        block[2] = new DataLayer(7);
        sky[4] = new DataLayer();
        block[4] = new DataLayer();
        var random = new Random(20261009);
        for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            sky[4].set(x, y, z, random.nextInt(16));
            block[4].set(x, y, z, random.nextInt(16));
        }
        var layers = new DataLayer[][] {sky, block};
        assertTrue(LightPayload.same(packet(layers, null, false), packet(layers, null, true)));
        var selected = new LightChanges();
        selected.mark(true, 1);
        selected.mark(false, 4);
        assertTrue(LightPayload.same(packet(layers, selected, false), packet(layers, selected, true)));
    }

    @Test
    void applyingIncrementalSectionsAndZeroClearsEqualsFullState() {
        var layers = new DataLayer[][] {new DataLayer[5], new DataLayer[5]};
        layers[1][2] = new DataLayer(14);
        byte[] old = packet(layers, null, false);
        layers[1][2] = null; // absent BLOCK owner means fallback zero, not absent packet update
        layers[0][0] = new DataLayer();
        layers[1][4] = new DataLayer(9);
        var selected = new LightChanges();
        selected.mark(false, 2);
        selected.mark(true, 0);
        selected.mark(false, 4);
        byte[] delta = packet(layers, selected, true);
        assertFalse(LightPayload.same(delta, packet(layers, null, false)));
        assertTrue(LightPayload.same(LightPayload.apply(old, delta), packet(layers, null, false)));
        var empty = new LightChanges();
        assertTrue(LightPayload.same(old, LightPayload.apply(old, packet(layers, empty, true))));
    }

    @Test
    void rotatedMixedOwnerColumnsUseTheSameScalarLightAndFallback() {
        var source = new DataLayer();
        for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            source.set(x, y, z, (x + 3 * z + y) & 15);
        var pos = new BlockPos.MutableBlockPos();
        for (int turn = 0; turn < 4; turn++) {
            var out = new DataLayer();
            for (int i = 0; i < 256; i++) {
                int x = i & 15, z = i >> 4;
                for (int t = 0; t < turn; t++) { int tmp = x; x = 15 - z; z = tmp; }
                ImageStream.sampleColumn(out, i, i < 128 ? source : null, x, z,
                        new Fallback(15), pos, 4096 + x, -16, z);
                for (int y = 0; y < 16; y++)
                    assertEquals(i < 128 ? source.get(x, y, z) : 15, out.get(i & 15, y, i >> 4));
            }
        }
    }

    private record Fallback(int value) implements LayerLightEventListener {
        public DataLayer getDataLayerData(SectionPos pos) { return null; }
        public int getLightValue(BlockPos pos) { return value; }
        public void checkBlock(BlockPos pos) {}
        public boolean hasLightWork() { return false; }
        public int runLightUpdates() { return 0; }
        public void updateSectionStatus(SectionPos pos, boolean empty) {}
        public void setLightEnabled(ChunkPos pos, boolean enabled) {}
        public void propagateLightSources(ChunkPos pos) {}
    }
}
