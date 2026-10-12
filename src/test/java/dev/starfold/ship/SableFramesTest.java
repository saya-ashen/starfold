package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.TestArchiveResources;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundTransit;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.ground.GroundSeams;
import dev.starfold.ground.PayloadMap;
import dev.starfold.sky.Space;
import dev.starfold.sky.SpaceTest;
import dev.starfold.space.SpaceServer;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

class SableFramesTest {
    /** A body's orientation turns exactly as positions and entity yaws on the same plane do. */
    @Test
    void aQuarterTurnOfABodyIsTheQuarterTurnOfThePlane() {
        for (int t = -4; t <= 7; t++)
            for (var v :
                    new Point[] {
                        new Point(1, 0, 0),
                        new Point(0, 0, 1),
                        new Point(0, 1, 0),
                        new Point(3, -2, 5)
                    }) {
                var expected = ChartLabSpace.rotate(v, t);
                var actual = SableFrames.turn(t).transform(new Vector3d(v.x(), v.y(), v.z()));
                assertEquals(expected.x(), actual.x, 1e-12);
                assertEquals(expected.y(), actual.y, 1e-12);
                assertEquals(expected.z(), actual.z, 1e-12);
            }
    }

    @Test
    void seamsExpressBothVelocitiesAndPosesInTheNewChart() {
        var before =
                new dev.ryanhcode.sable.companion.math.Pose3d(
                        new Vector3d(5120, 201, 2048.2),
                        new org.joml.Quaterniond().rotationXYZ(.2, .3, .4),
                        new Vector3d(20000000, -104, 20000000),
                        new Vector3d(1.2, .8, 1.1));
        var velocity = new Point(.5, -.1, .7);
        var spin = new Vector3d(.02, .03, -.04);
        var from = new Point(5120, 201, 2048.2);
        for (int t = 0; t < 4; t++) {
            var transport = new GroundSeams.Transport(from, new Point(18432.2, 201, 1024), t);
            var motion =
                    new Space.Motion(
                            from, new org.joml.Quaterniond(before.orientation()), velocity, spin);
            var next = SableFrames.seam(motion, transport);
            SpaceTest.point(transport.apply(from), next.position());
            SpaceTest.point(transport.vector(velocity), next.velocity());
            SpaceTest.vector(SableFrames.turn(t).transform(new Vector3d(spin)), next.spin());
            SpaceTest.aim(SableFrames.turn(t).mul(before.orientation()), next.orientation());
            var after = new dev.ryanhcode.sable.companion.math.Pose3d(before);
            var last = new dev.ryanhcode.sable.companion.math.Pose3d(before);
            last.position().add(-.5, .1, -.7);
            var oldLast = new Vector3d(last.position());
            SableFrames.carry(before, next, after);
            SableFrames.carry(before, next, last);
            var expected = transport.apply(new Point(oldLast.x, oldLast.y, oldLast.z));
            SpaceTest.vector(
                    new Vector3d(expected.x(), expected.y(), expected.z()), last.position());
            for (var local :
                    new Vector3d[] {
                        new Vector3d(20000001, -103, 20000003),
                        new Vector3d(19999998, -101, 19999999)
                    })
                SpaceTest.vector(
                        local,
                        after.transformPositionInverse(
                                SableFrames.riderPoint(
                                        before,
                                        after,
                                        before.transformPosition(local, new Vector3d()))));
            for (float yaw : new float[] {-170, 0, 37, 125})
                assertEquals(
                        0,
                        Math.IEEEremainder(
                                SableFrames.yaw(SableFrames.turn(t), yaw) - yaw - 90 * t, 360),
                        1e-4);
        }
    }

    @Test
    void cabinMembershipUsesTheFullPoseRatherThanCentreDistance() {
        var pose =
                new dev.ryanhcode.sable.companion.math.Pose3d(
                        new Vector3d(4000, 200, 864),
                        new org.joml.Quaterniond().rotationXYZ(.2, .7, -.3),
                        new Vector3d(20000000, -104, 20000000),
                        new Vector3d(1.2, .8, 1.1));
        var bounds =
                new net.minecraft.world.phys.AABB(19999980, -104, 19999980, 20000020, -99, 20000020)
                        .inflate(.5);
        assertTrue(
                SableFrames.inside(
                        pose,
                        bounds,
                        pose.transformPosition(
                                new Vector3d(20000019, -103, 20000019), new Vector3d())));
        assertFalse(
                SableFrames.inside(
                        pose,
                        bounds,
                        pose.transformPosition(
                                new Vector3d(20000021, -103, 20000019), new Vector3d())));
        assertFalse(
                SableFrames.inside(pose, bounds, new Vector3d(20000000, -103, 20000000)),
                "plot-resident seats and frames stay in their plot");
    }

    @Test
    void rawCargoTicketsIncludeTheTrailingHaloAcrossEveryQuarterTurn() {
        var box = new net.minecraft.world.phys.AABB(5083, 198, 2011, 5157, 206, 2085);
        for (int turn = 0; turn < 4; turn++) {
            var t =
                    new GroundSeams.Transport(
                            new Point(5120, 202, 2048), new Point(18432, 202, 1024), turn);
            var actual = new java.util.HashSet<Long>();
            SableTickets.columns(actual, box, t);
            var expected = new java.util.HashSet<Long>();
            for (int x = 5083; x <= 5157; x++)
                for (int z = 2011; z <= 2085; z++) {
                    var p = t.apply(new Point(x, 202, z));
                    expected.add(
                            net.minecraft.world.level.ChunkPos.asLong(
                                    (int) Math.floor(p.x()) >> 4, (int) Math.floor(p.z()) >> 4));
                }
            assertEquals(expected, actual, "raw columns, not their canonical terrain owners");
            assertTrue(
                    actual.contains(net.minecraft.world.level.ChunkPos.asLong(1152, 64)),
                    "the trailing face4 halo is entity-ticking too");
        }
        var negative = new java.util.HashSet<Long>();
        SableTickets.columns(
                negative, new net.minecraft.world.phys.AABB(-16.01, 0, -.01, 0, 1, 16));
        assertEquals(9, negative.size());
        assertTrue(negative.contains(net.minecraft.world.level.ChunkPos.asLong(-2, -1)));
        assertTrue(negative.contains(net.minecraft.world.level.ChunkPos.asLong(0, 1)));
    }

    @Test
    void cargoTicketsPrecedeRelabellingAndReleaseTheirOwnReservations() throws Exception {
        var frames = type("dev/starfold/ship/SableFrames");
        ordered(calls(frames, "cross"), "update", "advanceSurroundings", "transport", "move");
        var tickets = type("dev/starfold/ship/SableTickets");
        var update = calls(tickets, "update");
        ordered(
                update,
                "getAllSubLevels",
                "isRemoved",
                "boundingBox",
                "inflate",
                "columns",
                "transport",
                "addRegionTicket",
                "removeRegionTicket");
        assertTrue(update.contains("remove"));
        assertTrue(update.contains("copyOf"));
        assertTrue(calls(tickets, "clear").contains("update"));
        boolean stopped = false;
        for (var method : frames.methods)
            for (var ins : method.instructions)
                if (ins instanceof MethodInsnNode call
                        && call.owner.equals("dev/starfold/ship/SableTickets")
                        && call.name.equals("clear")) {
                    assertTrue(method.desc.contains("ServerStoppedEvent"));
                    stopped = true;
                }
        assertTrue(stopped);
        for (var method : tickets.methods)
            for (var ins : method.instructions)
                if (ins instanceof MethodInsnNode call)
                    assertFalse(
                            java.util.Set.of("teleport", "moveTo", "setPos", "setTrackingSubLevel")
                                    .contains(call.name),
                            "tickets retain normal entity ticking, never clamp cargo");
    }

    @Test
    void aPayloadNobodyRegisteredGoesAsItIs() {
        var payload = new Object();
        assertSame(payload, PayloadMap.relabel(null, payload));
    }

    private ClassNode type(String name) throws Exception {
        try (var in = TestArchiveResources.open("/" + name + ".class")) {
            assertNotNull(in, name);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    /** Pinned Sable: its datagrams leave by this one method, and its three pose-bearing kinds are these. */
    @Test
    void sablesPoseBearingPacketsAreTheOnesRelabelled() throws Exception {
        var udp = type("dev/ryanhcode/sable/network/udp/SableUDPServer");
        assertTrue(
                udp.methods.stream()
                        .anyMatch(
                                m ->
                                        m.name.equals("sendUDPPacket")
                                                && m.desc.equals(
                                                        "(Lnet/minecraft/server/level/ServerPlayer;Ldev/ryanhcode/sable/network/udp/SableUDPPacket;Z)Z")));
        var packets = new java.util.TreeSet<String>();
        for (String archive :
                System.getProperty("starfold.testArchives")
                        .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            try (var zip = new java.util.zip.ZipFile(archive)) {
                for (var e : java.util.Collections.list(zip.entries())) {
                    String n = e.getName();
                    if (!n.startsWith("dev/ryanhcode/sable/network/packets/")
                            || !n.endsWith(".class")) continue;
                    var node = new ClassNode();
                    try (var in = zip.getInputStream(e)) {
                        new ClassReader(in).accept(node, 0);
                    }
                    if (node.name.contains("Clientbound")
                            && node.fields.stream()
                                    .anyMatch(f -> f.desc.contains("companion/math/Pose3d")))
                        packets.add(node.name.substring(node.name.lastIndexOf('/') + 1));
                }
            }
        assertEquals(
                java.util.Set.of(
                        "ClientboundRecentlySplitSubLevelPacket",
                        "ClientboundSableSnapshotDualPacket$Entry",
                        "ClientboundStartTrackingSubLevelPacket"),
                packets);
    }

    /** One rigid motion carries a body, its last pose and everyone on it: the anchor goes to its owner place, and
     *  everything keeps its offset from the anchor, turned with the chart. */
    @Test
    void aTransportIsOneRigidMotionForEverythingNearItsAnchor() {
        var from = new Point(4095.2, 173.5, 862.4);
        var to = new Point(2047.2, 173.5, 862.4);
        for (int t = 0; t < 4; t++) {
            var transport = new GroundSeams.Transport(from, to, t);
            assertEquals(to, transport.apply(from));
            var offset = new Point(-1.25, -4.8, 0.5);
            var carried = transport.apply(from.add(offset));
            var expected = to.add(ChartLabSpace.rotate(offset, t));
            assertEquals(expected.x(), carried.x(), 1e-9);
            assertEquals(expected.y(), carried.y(), 1e-9);
            assertEquals(expected.z(), carried.z(), 1e-9);
            assertEquals(ChartLabSpace.rotate(offset, t), transport.vector(offset));
            assertEquals(offset.length(), carried.sub(to).length(), 1e-9);
        }
    }

    /** A body changes chart whole: both poses, the motion the physics holds, and whoever stands on it. */
    @Test
    void aBodyIsMovedWithItsMotionAndItsRiders() throws Exception {
        var cross =
                type("dev/starfold/ship/SableFrames").methods.stream()
                        .filter(m -> m.name.equals("cross"))
                        .findFirst()
                        .orElseThrow();
        var calls = new java.util.ArrayList<String>();
        for (var ins : cross.instructions)
            if (ins instanceof MethodInsnNode call) calls.add(call.name);
        ordered(calls, "transport", "motion", "seam", "move");
        assertEquals(1, java.util.Collections.frequency(calls, "move"));
        for (String name :
                new String[] {
                    "lastPose",
                    "resetVelocity",
                    "teleport",
                    "addLinearAndAngularVelocity",
                    "aboard",
                    "carry"
                }) assertFalse(calls.contains(name), "cross must use the shared move: " + name);
        for (var ins : cross.instructions)
            if (ins instanceof MethodInsnNode call && call.name.equals("move"))
                assertTrue(
                        call.desc.endsWith("ZLdev/starfold/ground/GroundSeams$Transport;)V"),
                        "the new chart accompanies the rigid move");
        var frames = type("dev/starfold/ship/SableFrames");
        var move = calls(frames, "move");
        ordered(
                move,
                "aboard",
                "carry",
                "resetVelocity",
                "teleport",
                "addLinearAndAngularVelocity",
                "carryBody",
                "raw");
        assertEquals(2, java.util.Collections.frequency(move, "carry"));
        assertEquals(1, java.util.Collections.frequency(move, "carryBody"));
        assertTrue(move.contains("getIndirectPassengers"));
        var carry = calls(type("dev/starfold/ground/GroundSeams"), "carryBody");
        ordered(carry, "put", "relocate", "cross", "moveTo", "resetPosition");
        var carried = calls(type("dev/starfold/ground/GroundSeams"), "carried");
        assertTrue(carried.containsAll(java.util.List.of("get", "getGameTime", "equals")));
        ordered(calls(type("dev/starfold/ground/GroundSeams"), "moved"), "carried", "transport");
        boolean registered = false;
        for (var m : type("dev/starfold/ship/SableFrames").methods)
            if (m.name.equals("initialize"))
                for (var ins : m.instructions)
                    if (ins instanceof MethodInsnNode call && call.name.equals("carriedBy"))
                        registered = true;
        assertTrue(registered);
    }

    /** Pinned Sable decides who is told of a body by one straight-line distance; that one is taken over the surface.
     *  A fresh frame makes Sable tell its bodies again. */
    @Test
    void trackingMeasuresOverTheSurfaceAndAFreshFrameIsToldAgain() throws Exception {
        var tracking = type("dev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem");
        var should = tracking.methods.stream().filter(m -> m.name.equals("shouldLoad")).toList();
        assertEquals(1, should.size());
        assertEquals(
                "(Lnet/minecraft/world/entity/player/Player;Lorg/joml/Vector3dc;)Z",
                should.get(0).desc);
        int measured = 0;
        for (var ins : should.get(0).instructions)
            if (ins instanceof MethodInsnNode call
                    && call.owner.equals("org/joml/Vector3dc")
                    && call.name.equals("distanceSquared")
                    && call.desc.equals("(DDD)D")) measured++;
        assertEquals(1, measured);
        boolean surface = false;
        for (var m : type("dev/starfold/mixin/SableTrackingMixin").methods)
            for (var ins : m.instructions)
                if (ins instanceof MethodInsnNode call
                        && call.owner.endsWith("GroundSeams")
                        && call.name.equals("distance")) surface = true;
        assertTrue(surface);
        boolean told = false;
        for (var m : type("dev/starfold/ship/SableFrames").methods)
            if (m.name.equals("initialize"))
                for (var ins : m.instructions)
                    if (ins instanceof MethodInsnNode call && call.name.equals("onReset"))
                        told = true;
        assertTrue(told);
    }

    /** What a client sends about a block in no chart (a lever on a body, in its plot) goes on as sent: moving, using,
     *  breaking and writing a sign all ask before they relabel. */
    @Test
    void whatAClientSendsAboutABlockInNoChartIsNotRelabelled() throws Exception {
        var inbound =
                type("dev/starfold/ground/PacketMap").methods.stream()
                        .filter(m -> m.name.equals("inbound"))
                        .findFirst()
                        .orElseThrow();
        int asked = 0, firstAsk = -1, firstRelabel = -1, n = 0;
        for (var ins : inbound.instructions) {
            n++;
            if (ins instanceof MethodInsnNode call) {
                if (call.name.equals("elsewhere")) {
                    asked++;
                    if (firstAsk < 0) firstAsk = n;
                }
                if (call.name.equals("directionToStorage") && firstRelabel < 0) firstRelabel = n;
            }
        }
        assertEquals(4, asked);
        assertTrue(firstAsk < firstRelabel);
    }

    /** Between planets everything near the anchor goes by one rotation of any angle: offsets and spins turn, headings
     *  follow, and a velocity goes by the motion the passage was given (the planets' own motion is in it). */
    @Test
    void aPassageBetweenPlanetsIsARigidMotionOfAnyAngle() {
        var from = new Point(16887.5, 7282.4, 221.5);
        var to = new Point(41471.8, 7417.4, 267.6);
        var flipped = new org.joml.Quaterniond().rotateX(Math.PI);
        var passage = new GroundSeams.Passage(1, from, to, flipped, v -> v.scale(2));
        assertEquals(to, passage.apply(from));
        var above = passage.apply(from.add(new Point(1, -3, 2)));
        assertEquals(to.x() + 1, above.x(), 1e-9);
        assertEquals(to.y() + 3, above.y(), 1e-9);
        assertEquals(to.z() - 2, above.z(), 1e-9);
        assertEquals(new Point(0, 2, 0), passage.vector(new Point(0, 1, 0)));
        var spin = passage.spin(new Point(0, 1, 0));
        assertEquals(-1, spin.y(), 1e-9);
        var quarter = new GroundSeams.Passage(1, from, to, SableFrames.turn(1), v -> v);
        // A quarter turn of the plane carries +X to +Z: south (yaw 0, +Z) becomes west (yaw 90,
        // -X).
        assertEquals(90, quarter.yaw(0), 1e-4);
        assertEquals(180, Math.abs(quarter.yaw(90)), 1e-4);
        var upright =
                new GroundSeams.Passage(
                        1, from, to, new org.joml.Quaterniond().rotateX(Math.PI / 2), v -> v);
        // A heading turned straight up or down has no direction on the level: the old one is kept.
        assertEquals(0f, upright.yaw(0f), 1e-4f);
    }

    /** A body goes between planets as it goes over a seam, and Sable's blocks a second meet our blocks a tick. */
    @Test
    void aBodyGoesBetweenPlanetsWithItsMotionInTheRightUnits() throws Exception {
        var frames = type("dev/starfold/ship/SableFrames");
        var calls = calls(frames, "move");
        for (String name :
                new String[] {
                    "logicalPose",
                    "lastPose",
                    "resetVelocity",
                    "teleport",
                    "addLinearAndAngularVelocity",
                    "aboard",
                    "carry",
                    "riderPoint",
                    "carryBody"
                }) assertTrue(calls.contains(name), name);
        assertEquals(2, java.util.Collections.frequency(calls, "carry"));
        assertTrue(calls.indexOf("aboard") < calls.indexOf("carry"));
        assertTrue(calls.lastIndexOf("carry") < calls.indexOf("resetVelocity"));
        assertTrue(calls.indexOf("resetVelocity") < calls.indexOf("teleport"));
        assertTrue(calls.indexOf("teleport") < calls.indexOf("addLinearAndAngularVelocity"));
        assertTrue(calls.indexOf("addLinearAndAngularVelocity") < calls.indexOf("carryBody"));
        var motion = calls(frames, "motion");
        assertTrue(
                motion.containsAll(
                        java.util.List.of("getLinearVelocity", "getAngularVelocity", "div")));
        int twenties = 0;
        for (var m : frames.methods)
            if (m.name.equals("motion") || m.name.equals("move"))
                for (var ins : m.instructions)
                    if (ins instanceof LdcInsnNode ldc && Double.valueOf(20).equals(ldc.cst))
                        twenties++;
        assertEquals(
                6,
                twenties,
                "three linear components and spin per second in, linear and spin per second out");
        ordered(
                calls(frames, "leave"),
                "inChart",
                "owned",
                "leaves",
                "out",
                "departureFrame",
                "level",
                "move");
        ordered(
                calls(frames, "arrive"),
                "holds",
                "surroundingsFrame",
                "universe",
                "nearest",
                "height",
                "enters",
                "in",
                "bodyAnchor",
                "move");
        ordered(calls(frames, "cross"), "holds", "arrive", "transport", "leave");
        boolean asked = false;
        for (var ins :
                type("dev/starfold/ground/GroundSeams").methods.stream()
                        .filter(m -> m.name.equals("moved"))
                        .findFirst()
                        .orElseThrow()
                        .instructions)
            if (ins instanceof MethodInsnNode call && call.name.equals("passage")) asked = true;
        assertTrue(asked, "what moves by itself is asked too");
    }

    /** A gyro turns a body's up towards the level's up, about the axis that carries one to the other, damps roll and
     *  pitch, wears a turn about the up away slowly and brakes one faster than a crew can stand. */
    @Test
    void aGyroLevelsADeckAndLeavesItsHeadingAlone() {
        double step = 1 / 60.0;
        var level = Thrusters.level(new org.joml.Quaterniond(), new Vector3d(), step);
        assertEquals(0, level.length(), 1e-12, "level and still: nothing to do");
        // A gentle turn is worn away slowly: the heading holds, and a turning thruster still turns
        // the ship.
        var gentle = Thrusters.level(new org.joml.Quaterniond(), new Vector3d(0, .4, 0), step);
        assertEquals(0, gentle.x, 1e-12);
        assertEquals(0, gentle.z, 1e-12);
        assertEquals(-.4 * Thrusters.STEER * step, gentle.y, 1e-12);
        // Where there is no air a turning thruster would spin a ship without end: beyond its rate
        // the turn is braked, towards that rate.
        for (double fast : new double[] {11, -11}) {
            var held = Thrusters.level(new org.joml.Quaterniond(), new Vector3d(0, fast, 0), step);
            assertEquals(0, held.x, 1e-12);
            assertEquals(0, held.z, 1e-12);
            assertEquals(-Math.signum(fast), Math.signum(held.y));
            assertTrue(Math.abs(held.y) > 1);
            assertTrue(Math.abs(fast + held.y) >= Thrusters.YAW * .9);
        }
        for (var tilt :
                new org.joml.Quaterniond[] {
                    new org.joml.Quaterniond().rotateZ(.5),
                    new org.joml.Quaterniond().rotateX(-1.2),
                    new org.joml.Quaterniond().rotateZ(3.0)
                }) {
            var change = Thrusters.level(tilt, new Vector3d(), step);
            assertTrue(change.length() > 0);
            assertEquals(0, change.y, 1e-12);
            // Turned a little about that change's axis, the body's up is nearer the level's.
            var up = tilt.transform(new Vector3d(0, 1, 0));
            var turned =
                    new org.joml.Quaterniond()
                            .rotateAxis(.01, new Vector3d(change).normalize())
                            .mul(tilt)
                            .transform(new Vector3d(0, 1, 0));
            assertTrue(turned.y > up.y, "towards upright");
        }
        var upside =
                Thrusters.level(new org.joml.Quaterniond().rotateX(Math.PI), new Vector3d(), step);
        assertTrue(upside.length() > 0, "upside down exactly, it still turns");
        var rolling = Thrusters.level(new org.joml.Quaterniond(), new Vector3d(1, 0, 0), step);
        assertTrue(rolling.x < 0 && rolling.y == 0, "a roll is braked");
    }

    /** The measured position weight cancels displacement as well as end velocity, for any incoming velocity. */
    @Test
    void cancelledWeightMatchesTheNativePositionAndVelocityRecurrences() {
        assertEquals(.47275, SableFrames.POSITION_WEIGHT);
        for (double step : new double[] {.05, .025, .01})
            for (double lost : new double[] {0, .25, 1})
                for (double mass : new double[] {1, 250.75, 1000})
                    for (double drag : new double[] {0, .09, .7}) {
                        double c = drag * step,
                                a = SableFrames.POSITION_WEIGHT,
                                G = lost * 11 * step;
                        var gravity = new Vector3d(1, -11, 2);
                        var pair = SableFrames.weighPair(gravity, lost, mass, step, c);
                        SpaceTest.vector(new Vector3d(1, -11, 2), gravity);
                        double p = (1 - a) * G / (a * (1 + c) + (1 - a)), q = (G - p) / (1 + c);
                        assertEquals(p, pair.pre().y / mass, 1e-12);
                        assertEquals(q, pair.post().y / mass, 1e-12);
                        SpaceTest.vector(
                                new Vector3d(gravity).mul(-lost * mass * step),
                                new Vector3d(pair.pre()).add(new Vector3d(pair.post()).mul(1 + c)));
                        for (double v : new double[] {-100, 0, 37}) {
                            double nativePost = (v + p - G) / (1 + c),
                                    withoutCancelledGravity = v / (1 + c);
                            assertEquals(withoutCancelledGravity, nativePost + q, 1e-12);
                            assertEquals(
                                    a * v + (1 - a) * withoutCancelledGravity,
                                    a * (v + p) + (1 - a) * nativePost,
                                    1e-12);
                        }
                        for (var orientation :
                                new org.joml.Quaterniond[] {
                                    new org.joml.Quaterniond(),
                                    new org.joml.Quaterniond().rotationXYZ(.3, -.6, 1.2)
                                }) {
                            var pose =
                                    new dev.ryanhcode.sable.companion.math.Pose3d(
                                            new Vector3d(),
                                            orientation,
                                            new Vector3d(),
                                            new Vector3d(1));
                            for (var impulse : new Vector3d[] {pair.pre(), pair.post()})
                                SpaceTest.vector(
                                        impulse,
                                        pose.transformNormal(
                                                pose.transformNormalInverse(
                                                        impulse, new Vector3d()),
                                                new Vector3d()));
                        }
                    }
    }

    /** One level listener per phase, never per gyro; POST consumes PRE's snapshot and cannot recompute it. */
    @Test
    void weightIsPairedOncePerBodyAndHoldStaysPerTick() throws Exception {
        var frames = type("dev/starfold/ship/SableFrames");
        assertEquals(
                .47275,
                frames.fields.stream()
                        .filter(f -> f.name.equals("POSITION_WEIGHT"))
                        .findFirst()
                        .orElseThrow()
                        .value);
        for (String phase : new String[] {"Pre", "Post"}) {
            var listeners =
                    frames.methods.stream()
                            .filter(
                                    m ->
                                            m.desc.equals(
                                                    "(Ldev/ryanhcode/sable/neoforge/event/ForgeSable"
                                                            + phase
                                                            + "PhysicsTickEvent;)V"))
                            .toList();
            assertEquals(1, listeners.size());
            var names = calls(frames, listeners.getFirst().name);
            assertEquals(1, java.util.Collections.frequency(names, "weigh" + phase));
            assertTrue(names.contains("getPhysicsSystem"));
            if (phase.equals("Pre")) assertTrue(names.contains("getTimeStep"));
            var initialize =
                    frames.methods.stream()
                            .filter(m -> m.name.equals("initialize"))
                            .findFirst()
                            .orElseThrow();
            int registrations = 0;
            for (var ins : initialize.instructions)
                if (ins instanceof InvokeDynamicInsnNode dynamic)
                    for (var arg : dynamic.bsmArgs)
                        if (arg instanceof org.objectweb.asm.Handle handle
                                && handle.getName().equals(listeners.getFirst().name))
                            registrations++;
            assertEquals(1, registrations);
        }
        var pre = calls(frames, "weighPre");
        ordered(
                pre,
                "remove",
                "getLevel",
                "is",
                "getContainer",
                "getUniversalDrag",
                "getAllSubLevels",
                "isRemoved",
                "containsKey",
                "at",
                "getMass",
                "weighPair",
                "put",
                "weigh");
        assertEquals(1, java.util.Collections.frequency(pre, "getAllSubLevels"));
        assertEquals(1, java.util.Collections.frequency(pre, "weigh"));
        assertEquals(1, java.util.Collections.frequency(pre, "getMass"));
        var post = calls(frames, "weighPost");
        ordered(post, "remove", "entrySet", "isRemoved", "weigh");
        assertEquals(1, java.util.Collections.frequency(post, "weigh"));
        for (String name :
                new String[] {
                    "at", "steadied", "getMass", "getGravity", "getUniversalDrag", "getAllSubLevels"
                }) assertFalse(post.contains(name), name);
        var cross = calls(frames, "cross");
        assertFalse(cross.contains("weigh"));
        assertFalse(cross.contains("applyLinearImpulse"));
        assertEquals(1, java.util.Collections.frequency(cross, "damp"));
        ordered(cross, "advanceSurroundings", "damp");
        var damp = calls(frames, "damp");
        assertFalse(damp.contains("applyLinearImpulse"));
        assertEquals(1, java.util.Collections.frequency(damp, "addLinearAndAngularVelocity"));
        ordered(
                damp,
                "at",
                "steadied",
                "getLinearVelocity",
                "drift",
                "addLinearAndAngularVelocity");
        assertEquals(
                1, java.util.Collections.frequency(calls(frames, "weigh"), "applyLinearImpulse"));
        var gyro = calls(type("dev/starfold/ship/Thrusters$Gyro"), "sable$physicsTick");
        assertFalse(gyro.contains("applyLinearImpulse"));
    }

    /** Vertical motion belongs to real weight and lift, never the gyro's drift damper. */
    @Test
    void holdLetsOnlyAPoweredClimbGatherSpeed() {
        for (double weight : new double[] {0, .25, 1})
            for (boolean lifting : new boolean[] {false, true})
                for (double y : new double[] {-100, 0, 100}) {
                    var velocity = new Vector3d(30, y, -40);
                    double held = -Thrusters.HOLD * weight / 20;
                    SpaceTest.vector(
                            new Vector3d(30 * held, 0, -40 * held),
                            SableFrames.drift(velocity, weight, Thrusters.HOLD));
                    SpaceTest.vector(new Vector3d(30, y, -40), velocity);
                }
    }

    @Test
    void horizontalHoldUsesBodyAxesAndNetPush() {
        for (var q :
                new org.joml.Quaterniond[] {
                    new org.joml.Quaterniond(),
                    new org.joml.Quaterniond().rotationXYZ(.03, .7, -.02),
                    new org.joml.Quaterniond().rotationXYZ(1, 2, 3)
                }) {
            for (var push :
                    new Vector3d[] {
                        new Vector3d(),
                        new Vector3d(0, 2, 0),
                        new Vector3d(0, -2, 0),
                        new Vector3d(.099, 1, 0)
                    }) {
                var world = q.transform(new Vector3d(push));
                assertEquals(
                        4,
                        SableFrames.horizontalHold(
                                q, new BentStripGeometry.Point(world.x, world.y, world.z)));
            }
            for (var push :
                    new Vector3d[] {
                        new Vector3d(1, 0, 0),
                        new Vector3d(-1, 0, 0),
                        new Vector3d(0, 0, 1),
                        new Vector3d(.101, 1, 0),
                        new Vector3d(1, -2, 0)
                    }) {
                var world = q.transform(new Vector3d(push));
                assertEquals(
                        .5,
                        SableFrames.horizontalHold(
                                q, new BentStripGeometry.Point(world.x, world.y, world.z)));
            }
        }
    }

    @Test
    void strongerHoldChangesOnlyHorizontalBraking() {
        for (double weight : new double[] {0, .25, 1})
            for (boolean lifting : new boolean[] {false, true})
                for (double y : new double[] {-100, 0, 100}) {
                    var velocity = new Vector3d(30, y, -40);
                    SpaceTest.vector(
                            new Vector3d(-6 * weight, 0, 8 * weight),
                            SableFrames.drift(velocity, weight, 4));
                    SpaceTest.vector(new Vector3d(30, y, -40), velocity);
                }
    }

    /** All of a thing's weight up to where the far view takes over, none from where space begins, and less between. */
    @Test
    void weightFadesWithHeightBetweenTheAirAndSpace() {
        assertEquals(1, HullWeight.at(0));
        assertEquals(1, HullWeight.at(GroundOrbit.END));
        assertEquals(0, HullWeight.at(GroundTransit.ENTER));
        assertEquals(0, HullWeight.at(20000));
        double last = 1;
        for (double h = GroundOrbit.END; h <= GroundTransit.ENTER; h += 64) {
            double w = HullWeight.at(h);
            assertTrue(w <= last);
            last = w;
        }
        assertEquals(.5, HullWeight.at((GroundOrbit.END + GroundTransit.ENTER) / 2), 1e-9);
    }

    /** The air is gone before weight begins to go: nothing that needs air reaches where things float. */
    @Test
    void theAirOfEveryPlanetDimensionThinsToNothingByTheHeightOfTheFarView() throws Exception {
        for (String dimension : new String[] {"native_ground", "native_planet", "native_rock"}) {
            var json =
                    com.google.gson.JsonParser.parseString(
                                    java.nio.file.Files.readString(
                                            java.nio.file.Path.of(
                                                    "src/main/resources/data/starfold/dimension_physics/"
                                                            + dimension
                                                            + ".json")))
                            .getAsJsonObject();
            assertEquals("starfold:" + dimension, json.get("dimension").getAsString());
            var points = json.getAsJsonArray("pressure_function");
            double altitude = Double.NEGATIVE_INFINITY, value = Double.POSITIVE_INFINITY;
            for (var e : points) {
                var point = e.getAsJsonObject();
                assertTrue(point.get("altitude").getAsDouble() > altitude);
                assertTrue(point.get("value").getAsDouble() <= value);
                altitude = point.get("altitude").getAsDouble();
                value = point.get("value").getAsDouble();
            }
            assertEquals(0, value);
            assertTrue(altitude <= GroundOrbit.END);
        }
    }

    /** Riders move once with the body, keeping block-space feet, rather than being held until upright. */
    @Test
    void ridersKeepTheirBlockSpacePlaceWithoutAHold() throws Exception {
        var frames = type("dev/starfold/ship/SableFrames");
        assertFalse(
                frames.methods.stream()
                        .anyMatch(
                                m -> java.util.Set.of("pass", "hold", "upright").contains(m.name)));
        assertFalse(
                frames.fields.stream()
                        .anyMatch(
                                f ->
                                        java.util.Set.of("HELD", "HOLD", "CALM", "UPRIGHT", "SLOW")
                                                .contains(f.name)));
        ordered(calls(frames, "riderPoint"), "transformPositionInverse", "transformPosition");
        var carry = calls(type("dev/starfold/ground/GroundSeams"), "carryBody");
        ordered(
                carry,
                "viewLost",
                "relocate",
                "moveTo",
                "resetPosition",
                "setDeltaMovement",
                "inChart",
                "resited");
        assertFalse(carry.contains("teleport"));
        var before =
                new dev.ryanhcode.sable.companion.math.Pose3d(
                        new Vector3d(123, 2400, 789),
                        new org.joml.Quaterniond().rotationXYZ(.2, .3, .4),
                        new Vector3d(4000, 10, 6000),
                        new Vector3d(1.2, .8, 1.1));
        var after = new dev.ryanhcode.sable.companion.math.Pose3d(before);
        var last = new dev.ryanhcode.sable.companion.math.Pose3d(before);
        last.position().add(-2, 3, -4);
        last.orientation().rotateX(.01);
        var oldLast = new dev.ryanhcode.sable.companion.math.Pose3d(last);
        var next =
                new Space.Motion(
                        new Point(Space.X, Space.Y, Space.Z),
                        new org.joml.Quaterniond().rotationXYZ(-.8, 1.2, .6),
                        new Point(1, 2, 3),
                        new Vector3d(.1, .2, .3));
        SableFrames.carry(before, next, after);
        SableFrames.carry(before, next, last);
        SpaceTest.vector(new Vector3d(Space.X, Space.Y, Space.Z), after.position());
        SpaceTest.aim(next.orientation(), after.orientation());
        var turn =
                next.orientation().mul(new org.joml.Quaterniond(before.orientation()).conjugate());
        SpaceTest.vector(
                turn.transform(new Vector3d(oldLast.position()).sub(before.position())),
                new Vector3d(last.position()).sub(after.position()));
        SpaceTest.aim(
                new org.joml.Quaterniond(turn).mul(oldLast.orientation()), last.orientation());
        assertEquals(before.rotationPoint(), after.rotationPoint());
        assertEquals(before.scale(), after.scale());
        for (var local :
                new Vector3d[] {new Vector3d(4001, 12, 6003), new Vector3d(3998, 15, 5990)}) {
            var feet = before.transformPosition(local, new Vector3d());
            var carried = SableFrames.riderPoint(before, after, feet);
            SpaceTest.vector(local, after.transformPositionInverse(new Vector3d(carried)));
            SpaceTest.vector(feet, SableFrames.riderPoint(after, before, carried));
        }
        assertEquals(90, SableFrames.yaw(SableFrames.turn(1), 0), 1e-4);
        assertEquals(37, SableFrames.yaw(new org.joml.Quaterniond(), 37), 1e-4);
        assertEquals(0, SableFrames.yaw(new org.joml.Quaterniond().rotateX(Math.PI / 2), 0), 1e-4);
    }

    @Test
    void reanchoringTranslatesBothPosesAndRiderFeetWithoutTurning() {
        var before =
                new dev.ryanhcode.sable.companion.math.Pose3d(
                        new Vector3d(Space.X + 40001, Space.Y - 9000, Space.Z + 321),
                        new org.joml.Quaterniond().rotationXYZ(.3, -.8, .6),
                        new Vector3d(4000, 10, 6000),
                        new Vector3d(1.2, .8, 1.1));
        var after = new dev.ryanhcode.sable.companion.math.Pose3d(before);
        var last = new dev.ryanhcode.sable.companion.math.Pose3d(before);
        last.position().add(-2, 3, -4);
        last.orientation().rotateX(.01);
        var oldLast = new dev.ryanhcode.sable.companion.math.Pose3d(last);
        var p = before.position();
        var delta = SpaceServer.anchor(new Point(p.x, p.y, p.z));
        assertNotNull(delta);
        var next =
                new Space.Motion(
                        Space.ORIGIN,
                        new org.joml.Quaterniond(before.orientation()),
                        new Point(1, 2, 3),
                        new Vector3d(.1, .2, .3));
        SableFrames.carry(before, next, after);
        SableFrames.carry(before, next, last);
        var shift = new Vector3d(delta.x(), delta.y(), delta.z());
        SpaceTest.vector(new Vector3d(before.position()).add(shift), after.position());
        SpaceTest.vector(new Vector3d(oldLast.position()).add(shift), last.position());
        SpaceTest.aim(new org.joml.Quaterniond(before.orientation()), after.orientation());
        SpaceTest.aim(new org.joml.Quaterniond(oldLast.orientation()), last.orientation());
        var feet = before.transformPosition(new Vector3d(4001, 12, 6003), new Vector3d());
        SpaceTest.vector(
                new Vector3d(feet).add(shift), SableFrames.riderPoint(before, after, feet));
    }

    private static java.util.List<String> calls(ClassNode type, String name) {
        var result = new java.util.ArrayList<String>();
        for (var ins :
                type.methods.stream()
                        .filter(m -> m.name.equals(name))
                        .findFirst()
                        .orElseThrow()
                        .instructions)
            if (ins instanceof MethodInsnNode call) result.add(call.name);
        return result;
    }

    private static void ordered(java.util.List<String> calls, String... names) {
        int last = -1;
        for (String name : names) {
            int at = calls.indexOf(name);
            assertTrue(at > last, name + " in " + calls);
            last = at;
        }
    }

    @Test
    void regionBodiesWeighNothingEvenBelowTheChartFloor() throws Exception {
        for (double y : new double[] {-1000, 0, 128, Space.Y})
            assertEquals(0, HullWeight.at(null, new Point(Space.X, y, Space.Z)));
        var weight =
                type("dev/starfold/ship/HullWeight").methods.stream()
                        .filter(m -> m.name.equals("at") && m.desc.contains("Level"))
                        .findFirst()
                        .orElseThrow();
        var names = new java.util.ArrayList<String>();
        for (var ins : weight.instructions)
            if (ins instanceof MethodInsnNode call) names.add(call.name);
        ordered(names, "holds", "of", "inChart");
    }

    /** A rider in the air is still aboard for a moment and within reach, and not after or beyond; riders are found
     *  that way wherever a body is moved and wherever an entity asks whether it may change chart by itself. */
    @Test
    void aRiderInTheAirIsStillAboardForAMoment() throws Exception {
        assertTrue(SableFrames.aboard(0, 0));
        assertTrue(SableFrames.aboard(SableFrames.ABOARD, SableFrames.REACH));
        assertFalse(SableFrames.aboard(SableFrames.ABOARD + 1, 1));
        assertFalse(SableFrames.aboard(1, SableFrames.REACH + 1));
        assertFalse(SableFrames.aboard(1, Double.POSITIVE_INFINITY));
        var frames = type("dev/starfold/ship/SableFrames");
        for (var m : frames.methods) {
            boolean direct = false;
            for (var ins : m.instructions)
                if (ins instanceof MethodInsnNode call && call.name.equals("getTrackingSubLevel"))
                    direct = true;
            if (direct)
                assertTrue(
                        java.util.Set.of("aboard", "tracked").contains(m.name),
                        "membership and relative motion distinguish actual tracking from grace");
        }
    }
}
