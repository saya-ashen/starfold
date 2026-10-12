package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.GroundTopology;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.render.GroundExteriorMesh;
import dev.starfold.render.GroundOwnership;
import dev.starfold.render.GroundStreaming;
import dev.starfold.render.GroundViewCache;
import dev.starfold.render.GroundViewPolicy;
import dev.starfold.worldgen.PlanetEcologyTest;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetTerrain;

import org.junit.jupiter.api.Test;

class PlanetShapeTest {
    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    /** The shape keeps two limits that other classes derive: they must agree with where they come from. */
    @Test
    void limitsAgreeWithStorageAndTheVanillaHeightRange() {
        assertEquals(PlanetStorage.MAX_PLANETS, PlanetShape.MAX_PLANETS);
        assertEquals(
                dev.starfold.worldgen.PlanetTerrain.SEA_LEVEL
                        - 1
                        - dev.starfold.worldgen.PlanetTerrain.MIN_Y,
                PlanetShape.STANDARD_DEPTH);
    }

    @Test
    void theSizeInUseReproducesTheFixedGeometryExactly() {
        var shape = new PlanetShape(GroundSpace.SIZE);
        assertEquals(GroundAir.HALF, shape.half());
        assertEquals(GroundOrbit.RADIUS, shape.radius());
        assertEquals(128, shape.chunks());
        assertEquals(shape, PlanetShape.STANDARD);
        assertEquals(shape, PlanetGeneration.defaults(5).shape());
        for (var f : Face.values())
            for (double u : new double[] {0, .5, 300.25, 1024, 2047.5, 2048, -40.5, 2100.5})
                for (double v : new double[] {0, 17.5, 1024, 2048, -3.5, 2060.5}) {
                    if ((u < 0 || u > 2048) && (v < 0 || v > 2048)) continue;
                    assertEquals(
                            GroundOrbit.direction(f, u, v),
                            shape.direction(f, u, v),
                            f + " " + u + " " + v);
                    var here = GroundTopology.canonical(f, new Point(u, 12, v));
                    var there = shape.canonical(f, new Point(u, 12, v));
                    assertEquals(here.pose(), there.pose());
                    assertEquals(here.turns(), there.turns());
                }
        for (var p :
                new Point[] {
                    new Point(900, 700, -650), new Point(-1, -1400, 3), new Point(5000, 5200, 4900)
                }) {
            assertEquals(GroundTopology.locate(p), shape.locate(p));
            assertEquals(
                    GroundTopology.planet(shape.locate(p))
                            .sub(shape.position(shape.locate(p)))
                            .length(),
                    0,
                    1e-9);
        }
    }

    @Test
    void aSmallerPlanetIsTheSameShapeScaled() {
        var big = new PlanetShape(2048);
        var small = new PlanetShape(1024);
        assertEquals(big.radius() / 2, small.radius());
        assertEquals(64, small.chunks());
        assertTrue(small.owned(1023.9, 0));
        assertFalse(small.owned(1024, 0));
        for (var f : Face.values())
            for (double u : new double[] {0, 100.5, 512, 1024, -20.5, 1040.5})
                for (double v : new double[] {0, 333.5, 1024}) {
                    assertEquals(
                            0,
                            big.direction(f, 2 * u, 2 * v).sub(small.direction(f, u, v)).length(),
                            1e-12,
                            f + " " + u + " " + v);
                }
        for (var f : Face.values())
            for (double u : new double[] {.5, 511.5, 1023.5})
                for (double v : new double[] {.5, 700.5, 1023.5})
                    for (double h : new double[] {-60, 0, 90}) {
                        var pose = new PlanetShape.Pose(f, new Point(u, h, v));
                        var back = small.locate(small.position(pose));
                        assertEquals(f, back.face());
                        assertEquals(0, back.point().sub(pose.point()).length(), 1e-9);
                        assertEquals(small.radius() + h, small.position(pose).length(), 1e-9);
                    }
        // Beyond an edge is the neighbouring face of this planet, not a cell 2048 blocks away.
        var over = small.canonical(Face.TOP, new Point(1030.5, 0, 200.5)).pose();
        assertNotEquals(Face.TOP, over.face());
        assertTrue(small.owned(over.point().x(), over.point().z()));
        assertEquals(Face.TOP, big.canonical(Face.TOP, new Point(1030.5, 0, 200.5)).pose().face());
    }

    @Test
    void onlySizesTheStorageLayoutCanHoldAreAccepted() {
        for (int bad : new int[] {0, 255, 1000, 2049, 4096})
            assertThrows(IllegalArgumentException.class, () -> new PlanetShape(bad), "" + bad);
        for (int good : new int[] {256, 1024, 2048})
            assertEquals(good, new PlanetShape(good).size());
    }

    @Test
    void aSmallerPlanetsTerrainIsItsOwnClosedSurfaceWithFeaturesOfTheSameSizeInBlocks() {
        var policy = new PlanetGeneration("rocky", "rocky_craters", 1, 1.2, .04, .09, 1024);
        var small = new PlanetTerrain(20261002, 5, policy);
        var big =
                new PlanetTerrain(
                        20261002,
                        5,
                        new PlanetGeneration("rocky", "rocky_craters", 1, 1.2, .04, .09));
        assertEquals(1024, small.shape().size());
        assertEquals(2048, big.shape().size());
        // Across an edge the neighbouring face continues the same ground: the column just beyond is
        // the neighbour's own.
        for (var f : Face.values())
            for (int v = 8; v < 1024; v += 97) {
                var cell =
                        small.shape()
                                .topology()
                                .canonical(new CubeSurfaceTopology.Cell(f, 1024, 0, v))
                                .cell();
                assertNotEquals(f, cell.face());
                assertEquals(
                        small.groundY(cell.face(), cell.u(), cell.v()),
                        small.column(f, 1024, v).ground());
                assertTrue(
                        Math.abs(small.groundY(f, 1023, v) - small.column(f, 1024, v).ground())
                                <= 6,
                        f + " " + v);
            }
        // All eight vertices carry the anomaly field at the planet's own corners.
        for (var f : Face.values())
            for (int[] at : new int[][] {{20, 20}, {1003, 20}, {20, 1003}, {1003, 1003}})
                assertSame(Anomaly.GROUND_BLOCK, small.surfaceState(f, at[0], at[1]).getBlock());
        assertNotSame(Anomaly.GROUND_BLOCK, small.surfaceState(Face.TOP, 512, 512).getBlock());
        assertNotSame(Anomaly.GROUND_BLOCK, big.surfaceState(Face.TOP, 1003, 1003).getBlock());
        // Slopes away from the vertices stay as gentle as on the standard planet: relief is not
        // squeezed into half the width.
        int steep = 0, total = 0;
        for (var f : Face.values())
            for (int u = 120; u < 900; u += 37)
                for (int v = 120; v < 900; v += 41) {
                    total++;
                    if (Math.abs(small.groundY(f, u, v) - small.groundY(f, u + 1, v)) > 2) steep++;
                }
        assertTrue(steep * 50 < total, steep + "/" + total);
        assertEquals(
                policy,
                PlanetGeneration.CODEC
                        .parse(
                                com.mojang.serialization.JsonOps.INSTANCE,
                                PlanetGeneration.CODEC
                                        .encodeStart(
                                                com.mojang.serialization.JsonOps.INSTANCE, policy)
                                        .getOrThrow())
                        .getOrThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlanetGeneration("rocky", "rocky", 1, 1, .035, .085, 1000));
    }

    @Test
    void storageAddressesEachPlanetByItsOwnSize() {
        try {
            PlanetShape.assign(1, new PlanetShape(1024));
            int b = PlanetStorage.offset(1);
            // A keeps every chunk of its 128-wide faces; B owns only the 64-wide corner of each
            // face's storage, under unchanged keys.
            assertTrue(PlanetStorage.key(127, 127) >= 0);
            assertTrue(PlanetStorage.key(b / 16 + 63, 63) >= 0);
            assertEquals(-1, PlanetStorage.key(b / 16 + 64, 10));
            assertEquals(-1, PlanetStorage.key(b / 16 + 10, 64));
            long key = PlanetStorage.key(b / 16 + 256 * 3 + 5, 7);
            assertEquals(PlanetStorage.CHUNKS_PER_PLANET + 5 + 128L * (7 + 128L * 3), key);
            assertEquals(
                    new net.minecraft.world.level.ChunkPos(b / 16 + 256 * 3 + 5, 7),
                    PlanetStorage.chunk(key));
            // One block beyond B's edge is its neighbouring face, 1024 blocks across; on A the same
            // chart position is still the face itself.
            var over = PlanetStorage.address(new net.minecraft.core.BlockPos(b + 1024, 140, 200));
            assertEquals(1, over.owner().planet());
            assertNotEquals(Face.TOP, over.owner().cell().face());
            assertTrue(PlanetShape.of(1).topology().owned(over.owner().cell()));
            var same = PlanetStorage.address(new net.minecraft.core.BlockPos(1024, 140, 200));
            assertEquals(Face.TOP, same.owner().cell().face());
            assertEquals(0, same.turns());
            // Every position of B's chart halo resolves to an owner that maps back into B's own
            // storage.
            for (int face = 0; face < 6; face++)
                for (int u : new int[] {-1024, -1, 0, 1023, 1024, 2047})
                    for (int v : new int[] {-1024, -1, 0, 500, 1023, 1024, 2047}) {
                        if ((u < 0 || u >= 1024) && (v < 0 || v >= 1024)) continue;
                        var q = new net.minecraft.core.BlockPos(b + face * 4096 + u, 130, v);
                        assertTrue(PlanetAccess.SHARED.inChart(q), q.toString());
                        var owner = PlanetStorage.address(q).owner();
                        var block = owner.block();
                        assertEquals(1, PlanetStorage.planet(block));
                        assertTrue(
                                PlanetStorage.key(block.getX() >> 4, block.getZ() >> 4)
                                        >= PlanetStorage.CHUNKS_PER_PLANET);
                    }
            assertFalse(
                    PlanetAccess.SHARED.inChart(
                            new net.minecraft.core.BlockPos(b + 2048, 130, 10)));
            assertTrue(PlanetAccess.SHARED.inChart(new net.minecraft.core.BlockPos(2048, 130, 10)));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new PlanetStorage.Owner(
                                    1, new CubeSurfaceTopology.Cell(Face.TOP, 1024, 0, 5)));
            // A neighbouring face is drawn one planet-width away in the observer's chart.
            var image =
                    PlanetAccess.SHARED.image(
                            b + 10.5,
                            10.5,
                            new Point(
                                    over.owner().block().getX() + .5,
                                    140,
                                    over.owner().block().getZ() + .5));
            assertNotNull(image);
            assertEquals(b + 1024.5, image.x(), 1e-9);
            assertEquals(200.5, image.z(), 1e-9);
        } finally {
            PlanetSystem.reset();
        }
        assertEquals(PlanetShape.STANDARD, PlanetShape.of(1));
    }

    @Test
    void theStandardChartIsTheAcceptedOne() {
        for (var root : Face.values()) {
            var mine = new PlanetChart(PlanetShape.STANDARD, root);
            var accepted = new GroundChart(root);
            assertEquals(accepted.tiles().size(), mine.tiles().size());
            for (int i = 0; i < 5; i++) {
                var a = accepted.tiles().get(i);
                var m = mine.tiles().get(i);
                assertEquals(a.face(), m.face());
                assertEquals(a.turn(), m.turn());
                assertEquals(a.x(), m.x());
                assertEquals(a.z(), m.z());
                var p = new Point(300.25, 7, 1900.5);
                assertEquals(a.point(p), m.point(p));
                assertEquals(a.local(a.point(p)), m.local(m.point(p)));
            }
        }
    }

    @Test
    void farViewOfASmallerPlanetCoversOnlyItsOwnTilesAndPatches() {
        var small = new PlanetShape(1024);
        int existing = 0;
        for (int p = 0; p < GroundViewPolicy.PATCH_COUNT; p++)
            if (GroundViewPolicy.exists(small, p)) {
                existing++;
                assertTrue(GroundViewPolicy.x(p) < 1024 && GroundViewPolicy.z(p) < 1024);
            }
        assertEquals(6 * 16, existing);
        assertTrue(GroundViewPolicy.exists(PlanetShape.STANDARD, GroundViewPolicy.PATCH_COUNT - 1));
        var dirty = new GroundViewPolicy.DirtyPatches(() -> small);
        assertEquals(96, dirty.count());
        dirty.tile(100 + 128L * 5);
        assertEquals(96, dirty.count());
        for (int i = 0; i < 96; i++) assertTrue(GroundViewPolicy.exists(small, dirty.poll()));
        assertEquals(-1, dirty.poll());
        // Standing at an edge, the working set continues onto the neighbouring face; every tile is
        // one the planet has.
        var pose = new PlanetShape.Pose(Face.TOP, new Point(1016.5, 10, 500.5));
        var tiles = GroundStreaming.footprint(small, pose, 4);
        assertEquals(81, tiles.size());
        boolean crossed = false;
        for (long key : tiles) {
            int face = (int) (key / 16384), u = (int) (key % 128), v = (int) (key / 128 % 128);
            assertTrue(u < 64 && v < 64, "" + key);
            crossed |= face != Face.TOP.ordinal();
            for (long n : GroundStreaming.neighbours(small, key)) {
                assertTrue(n >= 0 && n % 128 < 64 && n / 128 % 128 < 64);
                assertTrue(
                        java.util.Arrays.stream(GroundStreaming.neighbours(small, n))
                                .anyMatch(k -> k == key));
            }
        }
        assertTrue(crossed);
        assertTrue(GroundViewPolicy.nearSeam(small, pose));
        assertFalse(GroundViewPolicy.nearSeam(PlanetShape.STANDARD, pose));
        // The ownership atlas puts the halo ring right beyond the planet's last tile.
        var surface = new GroundViewCache.Surface(1, new int[256], new int[256]);
        long edge = GroundOwnership.key(small, 0, 1024 + 8, 8);
        assertNotEquals(0, edge / 16384);
        var atlas =
                GroundOwnership.atlas(
                        small,
                        new GroundOwnership.Snapshot(
                                java.util.Map.of(edge, surface), new java.util.BitSet()));
        assertEquals(127, atlas[64 + 1 + GroundOwnership.SIDE * (0 + 1)] & 255);
        assertEquals(0, atlas[65 + 1 + GroundOwnership.SIDE * (0 + 1)]);
        // The far mesh stands on the smaller sphere and closes at its own edges and vertices.
        GroundExteriorMesh.Heights flat =
                new GroundExteriorMesh.Heights() {
                    public double node(Face f, int x, int z) {
                        return 5;
                    }

                    public Double edited(Face f, int x, int z) {
                        return null;
                    }
                };
        for (var f : Face.values()) {
            assertEquals(
                    small.radius() + 5,
                    GroundExteriorMesh.node(small, flat, f, 1024, 1024).length(),
                    1e-9);
            assertEquals(
                    small.radius() + 5,
                    GroundExteriorMesh.centre(small, flat, f, 1023, 0).length(),
                    .2);
            assertEquals(2, GroundExteriorMesh.anomalyBeams(small, f, 768, 768, 128).size());
            assertEquals(0, GroundExteriorMesh.anomalyBeams(small, f, 256, 256, 128).size());
        }
        var seam = GroundExteriorMesh.node(small, flat, Face.TOP, 1024, 300);
        var other = small.canonical(Face.TOP, new Point(1024.5, 0, 300.5)).pose();
        boolean met = false;
        for (int u : new int[] {0, 1024})
            for (int v = 0; v <= 1024; v++)
                for (boolean swap : new boolean[] {false, true})
                    if (!met
                            && GroundExteriorMesh.node(
                                                    small,
                                                    flat,
                                                    other.face(),
                                                    swap ? v : u,
                                                    swap ? u : v)
                                            .sub(seam)
                                            .length()
                                    < 1e-9) met = true;
        assertTrue(met, "the neighbouring face has the same edge vertex");
    }

    @Test
    void theSolidCornerClosureStandsOnEachPlanetsOwnVertices() {
        var mask = GroundSpace.DEFAULT_MASK;
        var small = new PlanetShape(1024);
        for (var f : Face.values())
            for (int u : new int[] {0, 15, 16, 1007, 1008, 1023, 1024, 2032, 2047})
                for (int v : new int[] {0, 15, 16, 1008, 1023, 2047})
                    for (int h : new int[] {-9, -8, 0, 127, 128}) {
                        var cell = new CubeSurfaceTopology.Cell(f, u, h, v);
                        assertEquals(
                                GroundSpace.mask(mask, cell),
                                Anomaly.mask(PlanetShape.STANDARD, mask, cell),
                                cell.toString());
                        assertEquals(
                                GroundSpace.column(mask, cell),
                                Anomaly.column(PlanetShape.STANDARD, mask, cell));
                        boolean corner =
                                u < 1024
                                        && v < 1024
                                        && Math.min(u, 1023 - u) < 16
                                        && Math.min(v, 1023 - v) < 16;
                        assertEquals(corner, Anomaly.column(small, mask, cell), cell.toString());
                        assertEquals(corner && h >= -8 && h < 128, Anomaly.mask(small, mask, cell));
                    }
        for (int in : new int[] {0, 15, -1, -16, 1023, 1008, 1024, 1039})
            assertTrue(Anomaly.closed(small, in, 16), "" + in);
        for (int out : new int[] {16, -17, 1007, 1040, 2040})
            assertFalse(Anomaly.closed(small, out, 16), "" + out);
    }
}
