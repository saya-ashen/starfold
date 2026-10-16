package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class HullBottomEquivalenceTest {
    private static void equivalent(List<AABB> boxes, UnaryOperator<Vector3d> pose,
            HullLanding.SurfaceColumn terrain) {
        var expected = HullBottomReference.measure(boxes, pose, terrain);
        var queried = new HashSet<Long>();
        var actual = HullBottom.measure(boxes, pose, (x, z) -> {
            assertTrue(queried.add(((long) x << 32) ^ (z & 0xffffffffL)), "column read twice");
            return terrain.surface(x, z);
        });
        assertEquals(expected.clearance(), actual.clearance(), 1e-9);
        assertEquals(expected.fluid(), actual.fluid());
    }

    @Test
    void systematicGridEdgesLargeSignedPositionsYawTiltSlabsAndUnknowns() {
        var boxes = List.of(new AABB(-2, 0, -1, -1, 1, 0),
                new AABB(0, .5, 0, 1, 1, 1), new AABB(.13, -.27, -.8, .83, .4, .63));
        int cases = 0;
        for (double at : new double[]{-2e7, -2e7 + .125, -1, -.00000001, 0, .5, 2e7, 2e7 + .75})
            for (int yaw = 0; yaw < 360; yaw += 15)
                for (double tilt : new double[]{-15, -.001, 0, .001, 15})
                    for (int mode = 0; mode < 4; mode++) {
                        var q = new Quaterniond().rotateY(Math.toRadians(yaw)).rotateX(Math.toRadians(tilt));
                        final int m = mode;
                        equivalent(boxes, v -> q.transform(v).add(at, 2, -at), (x, z) -> {
                            int h = Math.floorMod(x * 31 + z, 7);
                            return new HullLanding.Surface(m == 0 || (m == 1 && h == 0) ? Double.NaN
                                    : m == 2 ? 2 : h * .5, (h & 1) == 0);
                        });
                        cases++;
                    }
        System.out.println("LANDING EQUIVALENCE systematic cases=" + cases);
    }

    @Test
    void seededRandomPosesAndNonIntegralBottoms() {
        var random = new Random(0x57454c414e44L);
        for (int i = 0; i < 4000; i++) {
            var boxes = new ArrayList<AABB>();
            for (int j = 0; j < 1 + i % 19; j++) {
                double x = random.nextDouble() * 12 - 6, z = random.nextDouble() * 12 - 6;
                double y = random.nextDouble() * 3;
                boxes.add(new AABB(x, y, z, x + .01 + random.nextDouble() * 2,
                        y + .5, z + .01 + random.nextDouble() * 2));
            }
            double at = i % 3 == 0 ? -2e7 : i % 3 == 1 ? 2e7 : -12.4;
            var offset = new Vector3d(at + random.nextDouble(), random.nextDouble() * 8 - 4,
                    -at + random.nextDouble());
            // Arbitrary horizontal tilt axis, total tilt <= 15 degrees, arbitrary yaw.
            double axis = random.nextDouble() * Math.PI * 2;
            var q = new Quaterniond().rotateY(random.nextDouble() * Math.PI * 2)
                    .rotateAxis(random.nextDouble() * Math.PI / 12, Math.cos(axis), 0, Math.sin(axis));
            equivalent(boxes, v -> q.transform(v).add(offset), (x, z) -> {
                int h = Math.floorMod(x * 719 + z * 31, 17);
                return new HullLanding.Surface(h == 0 ? Double.NaN : h * .25 - 2, h % 3 == 0);
            });
        }
        System.out.println("LANDING EQUIVALENCE random cases=4000 seed=0x57454c414e44");
    }

    @Test
    void emptyDegenerateTinyFacesAndExactFluidTiesInBothOrders() {
        equivalent(List.of(), v -> v, (x, z) -> new HullLanding.Surface(0, false));
        for (double width : new double[]{0, 1e-12, 1e-6, .5, 1, 2})
            for (boolean reverse : new boolean[]{false, true}) {
                var boxes = new ArrayList<>(List.of(new AABB(-1, 0, 0, -1 + width, 1, width),
                        new AABB(0, 0, 0, width, 1, width)));
                if (reverse) java.util.Collections.reverse(boxes);
                equivalent(boxes, v -> v, (x, z) -> new HullLanding.Surface(0, x < 0));
            }
    }

    @Test
    void independentConcurrentMeasurements() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
            for (int thread = 0; thread < 2; thread++) {
                final int side = thread;
                tasks.add(() -> {
                    for (int i = 0; i < 200; i++) {
                        double y = i * .01 + side;
                        equivalent(List.of(new AABB(-2, y, 0, 2, y + 1, 2)),
                                new Quaterniond().rotateY(i * .03)::transform,
                                (x, z) -> new HullLanding.Surface(x * .125 + side, (z & 1) == 0));
                    }
                    return null;
                });
            }
            for (var f : pool.invokeAll(tasks)) f.get();
        }
    }

    @Test
    void paired481FaceTiming() {
        var boxes = new ArrayList<AABB>();
        for (int i = 0; i < 481; i++) boxes.add(new AABB(i % 23, 2, i / 23, i % 23 + 1, 3, i / 23 + 1));
        var yaw = new Quaterniond().rotateY(.4);
        UnaryOperator<Vector3d> pose = yaw::transform;
        HullLanding.SurfaceColumn terrain = (x, z) -> new HullLanding.Surface(0, false);
        for (int i = 0; i < 500; i++) {
            HullBottomReference.measure(boxes, pose, terrain);
            HullBottom.measure(boxes, pose, terrain);
        }
        for (int round = 0; round < 6; round++) {
            double[] us = new double[2];
            for (int order = 0; order < 2; order++) {
                int impl = (round + order) % 2;
                long start = System.nanoTime();
                for (int i = 0; i < 200; i++) {
                    var result = impl == 0 ? HullBottomReference.measure(boxes, pose, terrain)
                            : HullBottom.measure(boxes, pose, terrain);
                    assertEquals(2, result.clearance(), 1e-9);
                }
                us[impl] = (System.nanoTime() - start) / 200_000.;
            }
            System.out.println("LANDING TIMING paired 481 round=" + round + " reference_us=" + us[0]
                    + " optimized_us=" + us[1] + " speedup=" + us[0] / us[1]);
        }
    }
}
