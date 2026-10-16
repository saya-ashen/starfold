package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** W1's RED AABB test rewritten against paired collision bottoms, not an offset or clamp.
 * Fixtures below are the limiting faces from the original read-only LANDTRUTH evidence. */
class LandingTruthReproductionTest {
    private static HullLanding.Surface solid(double y) { return new HullLanding.Surface(y, false); }
    private static final List<Vector3d> P4 = List.of(
            new Vector3d(82063.39569503532,134.00607919378,744.04719781193),
            new Vector3d(82063.24951815193,133.99743669382,743.05797709543),
            new Vector3d(82064.23767318988,134.04337770116,742.91155631542),
            new Vector3d(82064.38385007327,134.05202020112,743.90077703193));

    @Test
    void reportedClearanceMatchesActualCollisionGapInMeasuredPlanet4Fixture() {
        // evidence/W1/W1-planet4-2/latest.log:4569 (limiting top-slab bottom), 4899 (END).
        // Old AABB minY 131.26366899034474 - highest 134 = -2.73633100965526.
        var result = HullBottom.measureFaces(List.of(P4), (x, z) -> solid(
                x == 82063 && z == 743 ? 134 : 131));
        assertEquals(-.0025633061756593634, result.clearance(), 1e-9);
        assertFalse(result.fluid());
    }

    @Test
    void originalMoonGapIsNotOneBlockTooLow() {
        // evidence/W1/W1-original-moon-initial/latest.log:564; END:616.
        var face = List.of(
                new Vector3d(20923.964642208222,133.30538874510114,8653.149693753605),
                new Vector3d(20924.96460154094,133.30537651919084,8653.140675285682),
                new Vector3d(20924.9736200089,133.3053794163866,8654.140634618469),
                new Vector3d(20923.973660676183,133.30539164229688,8654.149653086391));
        var result = HullBottom.measureFaces(List.of(face), (x, z) -> solid(
                x == 20927 && z == 8645 ? 132 : x == 20924 && z == 8653 ? 131 : 128));
        assertEquals(2.305376519190844, result.clearance(), 1e-9);
        assertFalse(LiftControl.landed(result.clearance(), 0));
    }

    @Test
    void flatContactAndSignedPenetration() {
        var shape = List.of(new AABB(0, 1, 0, 1, 2, 1));
        assertEquals(0, HullBottom.measure(shape, v -> v, (x,z) -> solid(1)).clearance());
        assertEquals(-.002, HullBottom.measure(shape, v -> v, (x,z) -> solid(1.002)).clearance(), 1e-12);
    }

    @Test
    void yaw45DoesNotIncludeEmptyAabbCorners() {
        var box = new AABB(-1, 2, -1, 1, 3, 1);
        var yaw = new Quaterniond().rotateY(Math.PI / 4);
        var result = HullBottom.measure(List.of(box), yaw::transform, (x,z) -> {
            assertFalse(x == 1 && z == 1, "this square is outside the diamond, but inside its AABB");
            return solid(x == 1 && z == 1 ? 100 : 0);
        });
        assertEquals(2, result.clearance(), 1e-12);
    }

    @Test
    void halfSlabBottomAndHalfSlabTerrain() {
        // Upper slab bottom y=10.5, terrain bottom slab top=10.5, not integer 10 / 11.
        var level = net.minecraft.world.level.EmptyBlockGetter.INSTANCE;
        var pos = net.minecraft.core.BlockPos.ZERO;
        var slab = net.minecraft.world.level.block.Blocks.STONE_SLAB.defaultBlockState();
        var bottom = slab.setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
                net.minecraft.world.level.block.state.properties.SlabType.TOP).getCollisionShape(level, pos);
        var terrain = slab.getCollisionShape(level, pos);
        var boxes = bottom.toAabbs().stream().map(box -> box.move(0, 10, 0)).toList();
        double top = 10 + terrain.max(net.minecraft.core.Direction.Axis.Y);
        assertEquals(10.5, top);
        assertEquals(0, HullBottom.measure(boxes, v -> v, (x,z) -> solid(top)).clearance());
    }

    @Test
    void tiltedPlaneUsesLowestPointInsideEachClippedColumn() {
        var face = List.of(new Vector3d(0, 2, 0), new Vector3d(2, 4, 0),
                new Vector3d(2, 4, 1), new Vector3d(0, 2, 1));
        // x=1 column: bottom min 3, top 3; not global min 2 minus 3.
        assertEquals(0, HullBottom.measureFaces(List.of(face), (x,z) -> solid(x == 1 ? 3 : 1)).clearance());
    }

    @Test
    void unknownColumnsAreSkippedAndAllUnknownIsNan() {
        var boxes = List.of(new AABB(-2, 2, -1, 0, 3, 0));
        assertEquals(1, HullBottom.measure(boxes, v -> v, (x,z) -> solid(x == -1 ? 1 : Double.NaN)).clearance());
        assertTrue(Double.isNaN(HullBottom.measure(boxes, v -> v, (x,z) -> solid(Double.NaN)).clearance()));
        assertTrue(Double.isNaN(HullBottom.measure(List.of(), v -> v, (x,z) -> solid(0)).clearance()));
    }

    @Test
    void materialFollowsMinimumGapNotHighestTerrainAndSolidWinsTies() {
        var boxes = List.of(new AABB(0, 1, 0, 1, 2, 1), new AABB(1, 5, 0, 2, 6, 1));
        assertEquals(new HullLanding.Ground(0, true), HullBottom.measure(boxes, v -> v,
                (x,z) -> new HullLanding.Surface(x == 0 ? 1 : 4, x == 0)));
        for (boolean firstFluid : List.of(true, false))
            assertEquals(new HullLanding.Ground(0, false), HullBottom.measure(boxes, v -> v,
                    (x,z) -> new HullLanding.Surface(x == 0 ? 1 : 5, (x == 0) == firstFluid)));
    }

    @Test
    void sharedColumnsReadOnceAndNoAdjacentEdgeOnlyColumns() {
        var calls = new java.util.HashSet<String>();
        var boxes = List.of(new AABB(0, 2, 0, 1, 3, 1), new AABB(0, 3, 0, 1, 4, 1));
        assertEquals(1, HullBottom.measure(boxes, v -> v, (x,z) -> {
            assertTrue(calls.add(x + "," + z));
            assertEquals(0, x); assertEquals(0, z);
            return solid(1);
        }).clearance());
    }

    @Test
    void recordedReleaseBelowThreeCanCaptureBelowContactWithoutChangingHoverControl() {
        // W2/W2-planet4-hover-1/latest.log:3367 (release), 3368 (captured target).
        double y = 139.2942352294922, gap = 2.6567710946646628;
        double target = LiftControl.capture(y, -5.141942024230957, 8.25,
                33499.999994764956 / 2260.75 - 8.25);
        assertEquals(136.46049542453886, target, 1e-8);
        assertTrue(target - (y - gap) < LiftControl.CONTACT);
        // Releasing is still a stopping-altitude capture, not a promise to hover above terrain.
    }

    @Test
    void moonStairHasAnIndependentRawMotionGate() {
        // W2/W2-moon-2/latest.log:21235: valid plan/drop, but linear + angular*radius > .12.
        assertEquals(0, Airstair.settled(19, true, true, false,
                .04493817983169951 + .1336938411299108, 5.669725301592791));
        assertEquals(20, Airstair.settled(19, true, true, false,
                3.8684131776891764E-4, 5.669725301592791));
    }

    @Test
    void bottomCacheHasShapeMutationAndFullPacketInvalidation() throws Exception {
        var hooks = dev.starfold.Sources.text("HullBottomCacheMixin");
        for (String method : List.of("onBlockChange", "addChunkHolder", "setBoundingBox", "onRemove"))
            assertTrue(hooks.contains("method=\"" + method + "\""));
        var cache = dev.starfold.Sources.text("HullBottom");
        assertTrue(cache.contains("CACHE.computeIfAbsent(body,HullBottom::scan)"));
        assertTrue(cache.contains("CACHE.remove(body)"));
        assertTrue(cache.contains("body.getPlot().contains(event.getChunk().getPos())"));
        assertTrue(dev.starfold.Sources.text("SableFrames").contains("addListener(HullBottom::chunkLoaded)"));
        assertFalse(cache.contains("getMass()"));
    }

    @Test
    void representative481FaceTiming() {
        var boxes = new java.util.ArrayList<AABB>();
        for (int i = 0; i < 481; i++) boxes.add(new AABB(i % 23, 2, i / 23, i % 23 + 1, 3, i / 23 + 1));
        var yaw = new Quaterniond().rotateY(.4);
        for (int i = 0; i < 100; i++) HullBottom.measure(boxes, yaw::transform, (x,z) -> solid(0));
        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) assertEquals(2, HullBottom.measure(boxes, yaw::transform, (x,z) -> solid(0)).clearance(), 1e-9);
        System.out.println("LANDING TIMING 481 cached bottoms: " + (System.nanoTime() - start) / 200_000. + " us/call (pure geometry, not world lookup)");
    }
}
