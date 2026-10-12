package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.GroundTopology;
import dev.starfold.worldgen.PlanetEcologyTest;

import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

/** CPU owner/readiness/rebase transitions; these are not screenshots or GPU acceptance. */
class SeamRenderHandoffTest {
    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    private GroundViewCache.Surface surface(long revision) {
        return GroundOwnershipTest.surface(revision, 127, Blocks.WATER);
    }

    private GroundOwnership.Snapshot detail(long key) {
        return GroundOwnershipTest.snapshot(key, surface(1), false);
    }

    private int atlasCode(byte[] atlas, int face, int x, int z) {
        return Byte.toUnsignedInt(atlas[x + 1 + 130 * (z + 1 + 130 * face)]);
    }

    @Test
    void crossingDoesNotTurnTheDestinationDetailIntoAnUnreadyNativeTile() {
        for (int oldFace = 0; oldFace < 6; oldFace++)
            for (int newFace = 0; newFace < 6; newFace++)
                if (oldFace != newFace) {
                    long oldKey = oldFace * 16384L + 64 * 128, newKey = newFace * 16384L + 64 * 128;
                    var surfaces = Map.of(oldKey, surface(1), newKey, surface(1));
                    var natives = new BitSet();
                    natives.set((int) oldKey);
                    var before = new GroundOwnership.Snapshot(surfaces, natives);
                    assertEquals(127, GroundOwnership.code(before, newKey, oldFace));
                    var after = before.rebase(newFace, surfaces);
                    assertEquals(127, GroundOwnership.code(after, newKey, newFace));
                    assertEquals(127, GroundOwnership.code(after, oldKey, newFace));
                    assertEquals(before.surface(newKey), after.surface(newKey));
                    // Once the full destination volume is ready, exactly that tile hands back to
                    // MC.
                    var ready = new BitSet();
                    ready.set((int) newKey);
                    var committed = new GroundOwnership.Snapshot(surfaces, ready);
                    assertEquals(255, GroundOwnership.code(committed, newKey, newFace));
                    assertEquals(127, GroundOwnership.code(committed, oldKey, newFace));
                }
    }

    @Test
    void currentFaceDetailExceptionIsMirroredIntoCanonicalHalo() {
        for (int face = 0; face < 6; face++) {
            long key = face * 16384L + 64 * 128;
            var snapshot = detail(key);
            var atlas = GroundOwnership.atlas(snapshot, face);
            assertEquals(127, atlasCode(atlas, face, 0, 64));
            assertEquals(255, atlasCode(atlas, face, 64, 64));
            for (int f = 0; f < 6; f++)
                for (int z = -1; z <= 128; z++)
                    for (int x = -1; x <= 128; x++)
                        if (x < 0 || x == 128 || z < 0 || z == 128) {
                            long owner = GroundOwnership.key(f, x * 16 + 8, z * 16 + 8);
                            assertEquals(
                                    GroundOwnership.code(snapshot, owner, face),
                                    atlasCode(atlas, f, x, z));
                        }
        }
    }

    @Test
    void surfaceSectionAloneCannotReleaseAnUndergroundSnapshot() {
        var occupied = new BitSet();
        occupied.set(0, 13);
        var compiled = new BitSet();
        compiled.set(12);
        assertFalse(GroundHandoff.volumeReady(occupied, compiled));
        compiled.set(0, 12);
        assertTrue(GroundHandoff.volumeReady(occupied, compiled));
        compiled.clear(5);
        assertFalse(GroundHandoff.volumeReady(occupied, compiled));
        assertEquals(13, occupied.cardinality());
        assertTrue(GroundHandoff.volumeReady(new BitSet(), new BitSet()));
    }

    @Test
    void staleBatchCannotIntroduceOneFrameCoverDuringAdapterChange() {
        var current = detail(0);
        var nativeReady = GroundOwnershipTest.snapshot(0, surface(1), true);
        var trimmed = current.retainCompatible(nativeReady);
        assertEquals(GroundOwnership.Owner.COVER, trimmed.owner(0)); // old transition failure
        assertFalse(GroundHandoff.preservesSelected(current, trimmed, nativeReady));
        assertTrue(GroundHandoff.preservesSelected(current, nativeReady, nativeReady));
        // Actual eviction is allowed; holding a missing VBO is not.
        assertTrue(
                GroundHandoff.preservesSelected(
                        current, GroundOwnership.empty(), GroundOwnership.empty()));
    }

    @Test
    void changedSnapshotStaysResidentUntilReplacementAndStripsCommit() {
        var old = detail(0);
        var replacement = GroundOwnershipTest.snapshot(0, surface(2), false);
        assertTrue(old.validAgainst(old));
        assertFalse(old.validAgainst(replacement));
        assertFalse(
                GroundHandoff.preservesSelected(
                        old, old.retainCompatible(replacement), replacement));
        assertTrue(GroundHandoff.preservesSelected(old, replacement, replacement));
        assertEquals(127, GroundOwnership.code(old, 0, 0));
        assertEquals(127, GroundOwnership.code(replacement, 0, 0));
    }

    @Test
    void nearbyBothSidesAreSubscribedBeforeAndAfterEverySeam() {
        for (var face : Face.values())
            for (int[] uv : new int[][] {{0, 1024}, {2047, 1024}, {1024, 0}, {1024, 2047}}) {
                var before = new PlanetShape.Pose(face, new Point(uv[0] + .5, 20, uv[1] + .5));
                var move =
                        new Point(
                                uv[0] == 0 ? -1 : uv[0] == 2047 ? 1 : 0,
                                0,
                                uv[1] == 0 ? -1 : uv[1] == 2047 ? 1 : 0);
                var after = GroundTopology.canonical(face, before.point().add(move)).pose();
                var first = GroundViewPolicy.details(before, false);
                var second = GroundViewPolicy.details(after, false);
                assertTrue(first.size() <= 289 && second.size() <= 289);
                assertTrue(first.stream().anyMatch(k -> k / 16384 == face.ordinal()));
                assertTrue(first.stream().anyMatch(k -> k / 16384 == after.face().ordinal()));
                long destination =
                        GroundOwnership.key(
                                after.face().ordinal(),
                                (int) after.point().x(),
                                (int) after.point().z());
                assertTrue(first.contains(destination));
                assertTrue(second.contains(destination));
                var overlap = new HashSet<>(first);
                overlap.retainAll(second);
                assertTrue(overlap.size() >= 35);
            }
        assertEquals(
                289,
                GroundViewPolicy.details(
                                new PlanetShape.Pose(Face.TOP, new Point(1024, 20, 1024)), false)
                        .size());
    }

    @Test
    void boundedRequestsFillAllMissingSeamTilesAndStillRefreshKnownOnes() {
        var pose = new PlanetShape.Pose(Face.TOP, new Point(.5, 20, 1024.5));
        var all = GroundViewPolicy.details(pose, false);
        var resident = new HashSet<Long>();
        for (int i = 0; i < 60; i++) {
            var request =
                    GroundViewPolicy.subscription(pose, false, new BitSet(), i, resident::contains);
            assertTrue(request.size() <= GroundStreaming.REQUEST_TILES);
            assertEquals(request.size(), new HashSet<>(request).size());
            resident.addAll(request);
        }
        assertTrue(resident.containsAll(all));
        var refreshed = new HashSet<Long>();
        for (int i = 0; i < all.size(); i++)
            refreshed.addAll(
                    GroundViewPolicy.subscription(pose, false, new BitSet(), i, k -> true));
        assertTrue(refreshed.containsAll(all));
    }

    @Test
    void runtimeStagesFullVolumesWithoutPromotingThemOnFaceChanges() throws Exception {
        var renderer = Sources.text("GroundRenderer");
        var prepare =
                renderer.substring(
                        renderer.indexOf("private static void prepareDetails"),
                        renderer.indexOf("private static void refreshCoverage"));
        assertFalse(prepare.contains("ownership.rebase"));
        assertFalse(prepare.contains("GroundOwnership.empty()"));
        assertFalse(prepare.contains("detail.remove(key)"));
        assertTrue(renderer.contains("commitDetails(retained)"));
        assertFalse(renderer.contains("nativeVolumeReady"));
        assertTrue(renderer.contains("GroundStreaming.admit"));
        assertTrue(renderer.contains("GroundOwnership.atlas(GroundClient.shape(),next,window)"));
        assertFalse(renderer.contains("if(neighbour/16384!=nativeFace)"));
        assertTrue(
                GroundUndergroundTest.fragment()
                        .contains(
                                "CPNativeSelection<0.5||cpNativeOwner(ivec2(floor(cpNativeSourcePoint.xz/16.0)))>0.75"));
    }
}
