package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundChart;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundSolar;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.geometry.OrbitAttitude;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.planet.GroundDynamics;
import dev.starfold.planet.GroundTopology;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;

import org.joml.*;
import org.junit.jupiter.api.*;

import java.util.UUID;

/** Kernel tests only; NOT evidence of client/server/renderer integration or game acceptance. */
public class GroundIntegrationKernelTest {
    @BeforeAll
    static void registry() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    final GroundEphemeris.Model model = GroundSolar.defaults();
    public static final GroundDynamics.Terrain FLAT =
            new GroundDynamics.Terrain() {
                public boolean clear(PlanetShape.Pose p) {
                    return p.point().y() >= .9;
                }

                public double clearance(PlanetShape.Pose p) {
                    return p.point().y() - .9;
                }
            };

    static void point(Point a, Point b, double epsilon) {
        assertEquals(a.x(), b.x(), epsilon);
        assertEquals(a.y(), b.y(), epsilon);
        assertEquals(a.z(), b.z(), epsilon);
    }

    @Test
    void all24DirectedEdgesUseCanonicalAddressesAndReverseExactly() {
        int count = 0;
        for (Face face : Face.values())
            for (int edge = 0; edge < 4; edge++)
                for (int k : new int[] {16, 31, 1023, 2031}) {
                    int u = edge == 0 ? -1 : edge == 1 ? 2048 : k,
                            v = edge == 2 ? -1 : edge == 3 ? 2048 : k;
                    var q = new Cell(face, u, 17, v);
                    var raw = new BlockPos(face.ordinal() * 4096 + u, 128 + 17, v);
                    var a = GroundTopology.address(raw);
                    var expected = GroundSpace.TOPOLOGY.canonical(q);
                    assertEquals(GroundTopology.block(expected.cell()), a.owner());
                    assertEquals(expected.turns(), a.turns());
                    assertNotEquals(face, expected.cell().face());
                    var chart = new GroundChart(face);
                    var tile = chart.tile(expected.cell().face());
                    var r =
                            tile.point(
                                    new Point(
                                            expected.cell().u() + .5,
                                            17,
                                            expected.cell().v() + .5));
                    point(new Point(u + .5, 17, v + .5), r, 1e-10);
                    var s =
                            Blocks.OAK_STAIRS
                                    .defaultBlockState()
                                    .setValue(
                                            net.minecraft.world.level.block.StairBlock.FACING,
                                            net.minecraft.core.Direction.NORTH);
                    assertEquals(s, a.read(a.write(s)));
                    if (k == 16) count++;
                }
        assertEquals(24, count);
    }

    @Test
    void fractionalBodyAndAllVectorAxesTransportWithAim() {
        for (Face face : Face.values())
            for (Point p :
                    new Point[] {
                        new Point(-.2, 7.25, 50.3),
                        new Point(2048.2, 7.25, 50.3),
                        new Point(50.3, 7.25, -.2),
                        new Point(50.3, 7.25, 2048.2)
                    }) {
                var r = GroundTopology.canonical(face, p);
                var tile = new GroundChart(face).tile(r.pose().face());
                point(p, tile.point(r.pose().point()), 1e-9);
                var q = OrbitAttitude.upright(33, 24);
                var ray = PlanetMotion.direction(q, 0, 0, 1);
                point(r.vector(ray), PlanetMotion.direction(r.aim(q), 0, 0, 1), 1e-9);
                point(ray, ChartLabSpace.rotate(r.vector(ray), -r.turns()), 1e-12);
            }
    }

    @Test
    void notificationsKeepCardinalRelativeSourceInReceiverChart() {
        for (Face face : Face.values())
            for (int edge = 0; edge < 4; edge++) {
                int x = edge == 0 ? -1 : edge == 1 ? 2048 : 1023,
                        z = edge == 2 ? -1 : edge == 3 ? 2048 : 1023;
                var target = new BlockPos(face.ordinal() * 4096 + x, 128, z);
                var source =
                        target.offset(
                                edge < 2 ? (edge == 0 ? 1 : -1) : 0,
                                0,
                                edge >= 2 ? (edge == 2 ? 1 : -1) : 0);
                var n = GroundTopology.notification(target, source);
                assertEquals(GroundTopology.address(target).owner(), n.target());
                assertEquals(1, n.target().distManhattan(n.source()));
                var d = source.subtract(target);
                var rotated =
                        CubeSurfaceTopology.rotate(
                                new Delta(d.getX(), d.getY(), d.getZ()), n.turns());
                assertEquals(
                        new BlockPos(rotated.u(), rotated.h(), rotated.v()),
                        n.source().subtract(n.target()));
            }
    }

    @Test
    void ghostSectionsNeverGenerateAnotherTerrainImageButOwnedOverhangsRemainLegal() {
        for (int face = 0; face < 6; face++) {
            double origin = 4096.0 * face;
            assertTrue(
                    GroundTopology.ownedSection(
                            new net.minecraft.world.phys.AABB(origin, 128, 0, origin + 16, 144, 16),
                            face));
            assertFalse(
                    GroundTopology.ownedSection(
                            new net.minecraft.world.phys.AABB(origin - 16, 128, 0, origin, 144, 16),
                            face));
            assertFalse(
                    GroundTopology.ownedSection(
                            new net.minecraft.world.phys.AABB(
                                    origin, 128, -16, origin + 16, 144, 0),
                            face));
            assertTrue(GroundTopology.inChart(new BlockPos((int) origin - 1, 128, 1024)));
        }
        assertFalse(GroundTopology.inChart(new BlockPos(1_000_000, 128, 1024)));
    }

    @Test
    void undergroundNativePoseIsNotRestrictedToTheOldMinus8Ledger() {
        var p = new PlanetShape.Pose(Face.BOTTOM, new Point(300, -150, 800));
        var raw = GroundTopology.feet(p);
        assertEquals(p, GroundTopology.body(raw));
        assertEquals(GroundSpace.MIN, p.renderPose().point().y());
    }

    @Test
    void differentialVelocityRoundTripsAndMatchesFiniteDifferences() {
        for (Face f : Face.values())
            for (Point p :
                    new Point[] {
                        new Point(1024, 2, 1024), new Point(50, 200, 900), new Point(1700, 4096, 30)
                    }) {
                var pose = new PlanetShape.Pose(f, p);
                var v = new Point(.3, -.4, .9);
                var body = GroundTopology.planetVelocity(pose, v);
                point(v, GroundTopology.chartVelocity(pose, body), 1e-10);
                double e = 1e-5;
                var after = GroundTopology.planet(new PlanetShape.Pose(f, p.add(v.scale(e))));
                point(body, after.sub(GroundTopology.planet(pose)).scale(1 / e), 1e-6);
            }
    }

    GroundDynamics.State local(double height) {
        return GroundDynamics.local(
                900,
                new PlanetShape.Pose(Face.TOP, new Point(1024, height, 1024)),
                new Point(.1, .3, .05),
                OrbitAttitude.upright(27, 0),
                true,
                GroundDynamics.OFF);
    }

    @Test
    void movingSpinningReferenceHandoffDoesNotLoseMomentumOrRay() {
        var s = local(4096);
        var detached = GroundDynamics.detach(model, s);
        point(GroundDynamics.position(model, s, 900), detached.position(), 1e-9);
        point(GroundDynamics.velocity(model, s, 900), detached.velocity(), 1e-9);
        var attached = GroundDynamics.attach(model, detached);
        point(s.position(), attached.position(), 1e-8);
        point(s.velocity(), attached.velocity(), 1e-8);
        assertEquals(1, java.lang.Math.abs(s.orientation().dot(attached.orientation())), 1e-10);
        point(GroundDynamics.chartVelocity(s), GroundDynamics.chartVelocity(attached), 1e-8);
    }

    @Test
    void universeIdleCoastsWhileGBrakeIsBoundedAndManualCancelsIt() {
        var p = model.at(0, 900).world(GroundTopology.planet(local(6000).local()));
        var s =
                new GroundDynamics.State(
                        900,
                        true,
                        p,
                        new Point(3, 4, 0),
                        new Quaterniond(),
                        null,
                        true,
                        false,
                        GroundDynamics.OFF);
        var next =
                GroundDynamics.step(
                        model, FLAT, s, PlanetMotion.Input.idle(0, s.orientation()), false);
        point(s.velocity(), next.velocity(), 0);
        point(s.position().add(s.velocity()), next.position(), 1e-7);
        var brake =
                GroundDynamics.step(
                        model, FLAT, s, PlanetMotion.Input.idle(1, s.orientation()), true);
        assertTrue(brake.braking());
        assertEquals(1, brake.velocity().length(), 1e-9);
        var q = brake.orientation();
        var thrust = new PlanetMotion.Input(2, q.x, q.y, q.z, q.w, 1, 0, 0, true, false);
        assertFalse(GroundDynamics.step(model, FLAT, brake, thrust, false).braking());
    }

    @Test
    void nativeGroundMovementIsNotReimplementedInsideFlightKernel() {
        var s = local(100);
        assertThrows(
                IllegalStateException.class,
                () ->
                        GroundDynamics.step(
                                model,
                                FLAT,
                                s,
                                PlanetMotion.Input.idle(0, s.orientation()),
                                false));
    }

    @Test
    void nativeShapeSamplingDoesNotFillTheEmptyHalfOfAStair() {
        var state = Blocks.OAK_STAIRS.defaultBlockState();
        var origin = BlockPos.ZERO;
        var getter =
                new net.minecraft.world.level.BlockGetter() {
                    public net.minecraft.world.level.block.state.BlockState getBlockState(
                            BlockPos p) {
                        return p.equals(origin) ? state : Blocks.AIR.defaultBlockState();
                    }

                    public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p) {
                        return getBlockState(p).getFluidState();
                    }

                    public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(
                            BlockPos p) {
                        return null;
                    }

                    public int getHeight() {
                        return 384;
                    }

                    public int getMinBuildHeight() {
                        return -64;
                    }
                };
        var shape = state.getCollisionShape(getter, origin);
        double volume =
                shape.toAabbs().stream()
                        .mapToDouble(b -> b.getXsize() * b.getYsize() * b.getZsize())
                        .sum();
        assertEquals(.75, volume, 1e-12);
        int solid = 0, empty = 0;
        for (double x : new double[] {.25, .75})
            for (double y : new double[] {.25, .75})
                for (double z : new double[] {.25, .75}) {
                    var b =
                            new net.minecraft.world.phys.AABB(
                                    x - .01, y - .01, z - .01, x + .01, y + .01, z + .01);
                    if (GroundTerrain.overlaps(
                            getter, net.minecraft.world.phys.shapes.CollisionContext.empty(), b))
                        solid++;
                    else empty++;
                }
        assertEquals(6, solid);
        assertEquals(2, empty);
    }

    @Test
    void fullNavigationRoundTripsUndergroundAndUniverseWithoutBuildingTags() {
        var save = GroundNavigationSave.fresh(false);
        var id = UUID.randomUUID();
        var pose = new PlanetShape.Pose(Face.SOUTH, new Point(300, -100, 900));
        var s =
                GroundDynamics.local(
                        900,
                        pose,
                        new Point(.1, 0, 0),
                        new Quaterniond(),
                        false,
                        GroundDynamics.OFF);
        save.put(id, s);
        var tag = save.save(new CompoundTag(), null);
        assertEquals(s, GroundNavigationSave.load(tag, null).find(id));
        assertFalse(tag.contains("blocks"));
        assertNotEquals("starfold_ground_navigation_v1", GroundNavigationSave.FILE);
        var orbital = GroundDynamics.detach(model, local(6000));
        save.put(id, orbital);
        assertEquals(
                orbital,
                GroundNavigationSave.load(save.save(new CompoundTag(), null), null).find(id));
        assertThrows(IllegalStateException.class, () -> GroundNavigationSave.fresh(true));
    }

    @Test
    void malformedAndOccupiedNavigationNeverResetsToSpawn() {
        var tag = GroundNavigationSave.encode(local(200));
        tag.remove("velocity");
        assertThrows(IllegalArgumentException.class, () -> GroundNavigationSave.decode(tag));
        var occupied =
                new GroundDynamics.Terrain() {
                    public boolean clear(PlanetShape.Pose p) {
                        return false;
                    }

                    public double clearance(PlanetShape.Pose p) {
                        return 0;
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () -> GroundDynamics.resume(model, occupied, local(200), 901));
        assertThrows(
                IllegalArgumentException.class,
                () -> GroundDynamics.resume(model, FLAT, local(200), 899));
        var s =
                GroundDynamics.resume(
                        model, FLAT, GroundDynamics.detach(model, local(6000)), 100000);
        point(GroundDynamics.detach(model, local(6000)).position(), s.position(), 0);
    }

    @Test
    void kernelTakeoffAndReturnHandsMovementBackWithoutPretendingGameTest() {
        var s = local(GroundDynamics.HANDOFF + .01);
        for (int i = 0; i < 1500 && !s.space(); i++) {
            var q = GroundDynamics.orientation(model, s, s.tick());
            var in = new PlanetMotion.Input(i, q.x, q.y, q.z, q.w, 0, 0, 1, false, true);
            s = GroundDynamics.step(model, FLAT, s, in, false);
        }
        assertTrue(s.space());
        boolean handed = false;
        for (int i = 0; i < 3000; i++) {
            var world = GroundDynamics.position(model, s, s.tick());
            var target = model.at(0, s.tick()).center();
            var direction = target.sub(world);
            var q =
                    new Quaterniond()
                            .rotationTo(
                                    new Vector3d(0, 0, 1),
                                    new Vector3d(direction.x(), direction.y(), direction.z())
                                            .normalize());
            var in = new PlanetMotion.Input(i, q.x, q.y, q.z, q.w, 1, 0, 0, true, true);
            s = GroundDynamics.step(model, FLAT, s, in, false);
            if (!s.space() && s.local().point().y() <= GroundDynamics.HANDOFF) {
                handed = true;
                break;
            }
        }
        assertTrue(handed);
        assertNotNull(s.local());
        assertTrue(FLAT.clear(s.local()));
        assertTrue(GroundDynamics.chartVelocity(s).length() > 0);
    }
}
