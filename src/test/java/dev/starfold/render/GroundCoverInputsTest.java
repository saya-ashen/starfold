package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.PlanetShape;
import dev.starfold.worldgen.PlanetEcologyTest;
import dev.starfold.worldgen.PlanetHeightCache;
import dev.starfold.worldgen.PlanetTerrain;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.Map;

class GroundCoverInputsTest {
    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    private static GroundViewCache.Surface surface(long revision, int height, int state) {
        var heights = new int[256];
        var states = new int[256];
        java.util.Arrays.fill(heights, height);
        java.util.Arrays.fill(states, state);
        return new GroundViewCache.Surface(revision, heights, states);
    }

    @Test
    void notificationRevisionIsNotAGeometryChangeButColumnsAndUnknownAre() {
        var a = surface(1, 127, 0);
        var newer = surface(20, 127, 0);
        assertFalse(a.sameAs(newer), "revision-aware consumers retain their old contract");
        assertTrue(a.sameColumns(newer));
        var snapshot = new GroundCoverSnapshot(null, Map.of(0L, a), new BitSet());
        assertTrue(
                snapshot.sameInputs(
                        new GroundCoverSnapshot(null, Map.of(0L, newer), new BitSet())));
        assertFalse(snapshot.sameInputs(null));
        assertFalse(snapshot.sameInputs(new GroundCoverSnapshot(null, Map.of(), new BitSet())));
        assertFalse(
                snapshot.sameInputs(new GroundCoverSnapshot(null, Map.of(1L, a), new BitSet())));
        assertFalse(
                snapshot.sameInputs(
                        new GroundCoverSnapshot(
                                null, Map.of(0L, surface(2, 128, 0)), new BitSet())));
        int stone =
                net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        assertFalse(
                snapshot.sameInputs(
                        new GroundCoverSnapshot(
                                null, Map.of(0L, surface(2, 127, stone)), new BitSet())));
        var unknown = new BitSet();
        unknown.set(1);
        assertFalse(snapshot.sameInputs(new GroundCoverSnapshot(null, Map.of(0L, a), unknown)));
    }

    @Test
    void generatedSourceIdentityAndTransportedHaloAreInputs() {
        var heights = new PlanetHeightCache(new PlanetTerrain(42));
        var a = new GroundCoverSnapshot(heights, Map.of(), new BitSet());
        assertTrue(a.sameInputs(new GroundCoverSnapshot(heights, Map.of(), new BitSet())));
        assertFalse(
                a.sameInputs(
                        new GroundCoverSnapshot(
                                new PlanetHeightCache(new PlanetTerrain(43)),
                                Map.of(),
                                new BitSet())));
        var marked = new BitSet();
        long halo = GroundOwnership.key(PlanetShape.STANDARD, 0, -16, 32);
        marked.set((int) halo);
        var empty = GroundCoverSnapshot.patch(heights, marked, 0, 0, 0, k -> null);
        var known =
                GroundCoverSnapshot.patch(
                        heights, marked, 0, 0, 0, k -> k == halo ? surface(1, 127, 0) : null);
        assertFalse(
                empty.sameInputs(known),
                "a neighbour across the face edge affects the shared border");
        var newer =
                GroundCoverSnapshot.patch(
                        heights, marked, 0, 0, 0, k -> k == halo ? surface(2, 127, 0) : null);
        assertTrue(known.sameInputs(newer));
        marked.set((int) GroundOwnership.key(PlanetShape.STANDARD, 0, 512, 512));
        assertTrue(
                known.sameInputs(
                        GroundCoverSnapshot.patch(
                                heights,
                                marked,
                                0,
                                0,
                                0,
                                k -> k == halo ? surface(3, 127, 0) : null)),
                "an unrelated patch must not invalidate this mesh");
    }

    @Test
    void reusedRemoteBuffersAreNotClosedOrRebuiltAndMaskChangesInvalidate() throws Exception {
        var remote = dev.starfold.Sources.text("PlanetRemoteRenderer");
        assertTrue(remote.contains("next.sameInputs(inputs[cursor])"));
        assertTrue(remote.contains("mask.equals(inputMasks[cursor])"));
        assertTrue(remote.contains("staging[cursor] = meshes[cursor]"));
        assertTrue(remote.contains("meshes[i] != staging[i]"));
        assertTrue(remote.contains("staging[i] != meshes[i]"));
        assertTrue(remote.contains("inputSource.biome().equals(building.biome())"));
        var ground = dev.starfold.Sources.text("GroundRenderer");
        assertTrue(ground.contains("Arrays.fill(coverInputs, null)"));
        assertTrue(
                ground.contains("coverJob.builders.cover.sameInputs(coverInputs(coverJob.patch))"));
    }
}
