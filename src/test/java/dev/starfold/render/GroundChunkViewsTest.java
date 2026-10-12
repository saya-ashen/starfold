package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.*;

import java.util.*;

/** Snapshot/cache proofs, not live world streaming or renderer acceptance. */
class GroundChunkViewsTest {
    @BeforeAll
    static void registry() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    int[] states() {
        var states = new int[GroundChunkSnapshot.VALUES];
        Arrays.fill(states, Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState()));
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++)
                states[x + 16 * (z + 16 * (127 + 64))] =
                        Block.BLOCK_STATE_REGISTRY.getId(Blocks.GRASS_BLOCK.defaultBlockState());
        return states;
    }

    @Test
    void registeredStatePropertiesAndNativeHeightSurvivePackedWire() {
        var states = states();
        var stairs =
                Blocks.OAK_STAIRS
                        .defaultBlockState()
                        .setValue(
                                net.minecraft.world.level.block.StairBlock.FACING,
                                net.minecraft.core.Direction.EAST);
        int i = 2 + 16 * (3 + 16 * (300 + 64));
        states[i] = Block.BLOCK_STATE_REGISTRY.getId(stairs);
        var a = GroundChunkSnapshot.fromStates(4, 127, 1, 42, states);
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var buffer =
                    new net.minecraft.network.RegistryFriendlyByteBuf(
                            raw, net.minecraft.core.RegistryAccess.EMPTY);
            a.write(buffer);
            assertTrue(buffer.readableBytes() < 1024 * 1024);
            var b = GroundChunkSnapshot.read(buffer);
            assertEquals(a.key(), b.key());
            assertEquals(a.revision(), b.revision());
            assertEquals(stairs, b.state(2, 300, 3));
            assertEquals(0, buffer.readableBytes());
            assertEquals(stairs, b.state(new BlockPos(4 * 4096 + 127 * 16 + 2, 300, 16 + 3)));
        } finally {
            raw.release();
        }
    }

    @Test
    void snapshotsCannotChangeCanonicalOwnerOrSmuggleInvalidStateIds() {
        var states = states();
        var a = GroundChunkSnapshot.fromStates(0, 0, 0, 1, states);
        assertThrows(IllegalArgumentException.class, () -> a.state(new BlockPos(4096, 127, 0)));
        var bad = states.clone();
        bad[0] = Integer.MAX_VALUE;
        assertThrows(
                IllegalArgumentException.class,
                () -> GroundChunkSnapshot.fromStates(0, 0, 0, 1, bad));
        assertThrows(
                IllegalArgumentException.class,
                () -> GroundChunkSnapshot.fromStates(6, 0, 0, 1, states));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundChunkSnapshot(0, 0, 0, 1, new int[] {-1}, a.packed()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundChunkSnapshot(0, 0, 0, 1, a.palette(), new long[1]));
    }

    @Test
    void copiesAreImmutableAndChangesInvalidateExactlyTheirOwner() {
        var invalidated = new ArrayList<Long>();
        var cache = new GroundViewCache(invalidated::add);
        var s = states();
        s[16 * (16 * (130 + 64))] =
                Block.BLOCK_STATE_REGISTRY.getId(Blocks.GLASS.defaultBlockState());
        var a = GroundChunkSnapshot.fromStates(2, 4, 5, 2, s);
        Arrays.fill(s, 0);
        var packed = a.packed();
        Arrays.fill(packed, -1);
        assertTrue(cache.accept(a));
        assertEquals(List.of(a.key()), invalidated);
        assertFalse(cache.accept(a));
        assertEquals(130, cache.surface(a.key()).height(0, 0));
        assertEquals(Blocks.GLASS.defaultBlockState(), cache.surface(a.key()).state(0, 0));
        var h = cache.surface(a.key()).heights();
        h[0] = 0;
        assertEquals(130, cache.surface(a.key()).height(0, 0));
        assertEquals(Blocks.GLASS.defaultBlockState(), a.state(0, 130, 0));
    }

    @Test
    void detailEvictionRetainsCoarseCoverageAndRefillCannotRewriteFacts() {
        var cache = new GroundViewCache(k -> {});
        var states = states();
        states[16 * (16 * (130 + 64))] =
                Block.BLOCK_STATE_REGISTRY.getId(Blocks.CHEST.defaultBlockState());
        var first = GroundChunkSnapshot.fromStates(0, 0, 0, 7, states);
        cache.accept(first);
        for (int i = 1; i <= 512; i++)
            cache.accept(GroundChunkSnapshot.fromStates(0, i % 128, i / 128, 7, states));
        assertEquals(512, cache.detailCount());
        assertTrue(cache.detailBytes() <= GroundViewCache.BYTE_LIMIT);
        assertFalse(cache.detailed(first.key()));
        assertNotNull(cache.surface(first.key()));
        assertNull(cache.canonical(BlockPos.ZERO));
        assertFalse(cache.refill(GroundChunkSnapshot.fromStates(0, 0, 0, 7, states())));
        assertTrue(cache.refill(first));
        assertTrue(cache.detailed(first.key()));
        assertFalse(cache.accept(GroundChunkSnapshot.fromStates(0, 0, 0, 6, states)));
        cache.clear();
        assertEquals(0, cache.detailBytes());
        assertEquals(-1, cache.version(first.key()));
    }

    @Test
    void actualNibbleLightsRoundTripWithoutMutableOrSkyPhaseAliases() {
        var base = GroundChunkSnapshot.fromStates(0, 0, 0, 5, states());
        var lights = base.lights();
        int i = 2 + 16 * (3 + 16 * (130 + 64));
        lights[i] = (byte) 0x4d;
        var image = new GroundChunkSnapshot(0, 0, 0, 5, base.palette(), base.packed(), lights);
        lights[i] = 0;
        assertEquals(
                13,
                image.light(net.minecraft.world.level.LightLayer.BLOCK, new BlockPos(2, 130, 3)));
        assertEquals(
                4, image.light(net.minecraft.world.level.LightLayer.SKY, new BlockPos(2, 130, 3)));
        assertNotEquals(base.fingerprint(), image.fingerprint());
        var copy = image.lights();
        copy[i] = 0;
        assertEquals(
                13,
                image.light(net.minecraft.world.level.LightLayer.BLOCK, new BlockPos(2, 130, 3)));
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var b =
                    new net.minecraft.network.RegistryFriendlyByteBuf(
                            raw, net.minecraft.core.RegistryAccess.EMPTY);
            image.write(b);
            var decoded = GroundChunkSnapshot.read(b);
            assertEquals(image.fingerprint(), decoded.fingerprint());
            assertArrayEquals(image.lights(), decoded.lights());
        } finally {
            raw.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundChunkSnapshot(
                                0, 0, 0, 5, base.palette(), base.packed(), new byte[1]));
    }

    @Test
    void onlyExactFullNativeGeneratorStatesCertifyBaseline() {
        var states = new int[GroundChunkSnapshot.VALUES];
        for (int y = -64; y < 320; y++) {
            var state =
                    y == -64
                            ? Blocks.BEDROCK.defaultBlockState()
                            : y < 124
                                    ? Blocks.STONE.defaultBlockState()
                                    : y < 127
                                            ? Blocks.DIRT.defaultBlockState()
                                            : y == 127
                                                    ? Blocks.GRASS_BLOCK.defaultBlockState()
                                                    : Blocks.AIR.defaultBlockState();
            Arrays.fill(
                    states,
                    (y + 64) * 256,
                    (y + 65) * 256,
                    Block.BLOCK_STATE_REGISTRY.getId(state));
        }
        var image = GroundChunkSnapshot.fromStates(0, 0, 0, 1, states);
        assertTrue(image.baseline());
        states[(50 + 64) * 256] =
                Block.BLOCK_STATE_REGISTRY.getId(Blocks.CHEST.defaultBlockState());
        assertFalse(GroundChunkSnapshot.fromStates(0, 0, 0, 2, states).baseline());
    }

    @Test
    void removingChangedTopRestoresDerivedFlatCoverageNotASecondLedger() {
        var cache = new GroundViewCache(k -> {});
        var states = states();
        states[16 * (16 * (130 + 64))] =
                Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        cache.accept(GroundChunkSnapshot.fromStates(0, 0, 0, 1, states));
        assertNotNull(cache.surface(0));
        cache.accept(GroundChunkSnapshot.fromStates(0, 0, 0, 2, states()));
        assertNull(cache.surface(0));
        assertEquals(2, cache.version(0));
    }
}
