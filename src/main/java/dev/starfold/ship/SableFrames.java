package dev.starfold.ship;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundRecentlySplitSubLevelPacket;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.GroundSeams;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.ground.ObserverFrames;
import dev.starfold.ground.PacketMap;
import dev.starfold.ground.PayloadMap;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.probe.ArrivalProbe;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;

import net.minecraft.server.level.ServerPlayer;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;

/** Sable tells each client where a sub-level's body is in the level's own coordinates, on its own channel. For an
 *  observer those are storage coordinates: the body is relabelled as an entity there would be. The plot that holds
 *  the body's blocks lies outside every chart and is the same for everyone. */
public final class SableFrames {
    private static final java.util.Map<ServerPlayer, dev.ryanhcode.sable.sublevel.ServerSubLevel>
            CARRYING = new java.util.WeakHashMap<>();
    private static final java.util.Map<ServerPlayer, net.minecraft.server.level.ServerLevel>
            RELIABLE = new java.util.WeakHashMap<>();

    public static boolean carrying(ServerPlayer player) {
        return CARRYING.containsKey(player);
    }

    /** New-label snapshots must not overtake the TCP carry; already flying old UDP snapshots meet its client fence. */
    public static boolean reliable(ServerPlayer player) {
        return RELIABLE.get(player) == player.level();
    }

    static boolean retain(Object body, Object carried) {
        return body == carried;
    }

    public static void initialize() {
        StarfoldNetwork.playS2C(SableCarry.TYPE, SableCarry.CODEC);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
                                event) -> RELIABLE.remove(event.getEntity()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> {
                    RELIABLE.clear();
                    CARRYING.clear();
                    WEIGHING.clear();
                    FLOATING.clear();
                    ABOARD_OF.clear();
                    SableTickets.clear();
                });
        // Whoever stands on a body is carried by it: the body decides when they change chart, as a
        // vehicle would.
        GroundSeams.carriedBy(e -> aboard(e) != null);
        // Other bodies reload after a fresh observer frame. The carried hull must keep its plot and
        // controls;
        // its poses and its rider receive the new labels together instead.
        ObserverFrames.onReset(
                player -> {
                    var container =
                            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                                    player.serverLevel());
                    if (container == null) return;
                    var origin = container.getOrigin();
                    for (var body : container.getAllSubLevels())
                        if (!retain(body, CARRYING.get(player))
                                && body.getTrackingPlayers()
                                        .remove(player.getGameProfile().getId())) {
                            var plot = body.getPlot().plotPos;
                            player.connection.send(
                                    new net.minecraft.network.protocol.common
                                            .ClientboundCustomPayloadPacket(
                                            new dev.ryanhcode.sable.network.packets.tcp
                                                    .ClientboundStopTrackingSubLevelPacket(
                                                    net.minecraft.world.level.ChunkPos.asLong(
                                                            plot.x - origin.x,
                                                            plot.z - origin.y))));
                        }
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) -> {
                    if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                            && PlanetLevels.is(level)) cross(level);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent event) ->
                        weighPre(event.getPhysicsSystem(), event.getTimeStep()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent event) ->
                        weighPost(event.getPhysicsSystem()));
        PayloadMap.register(
                ClientboundStartTrackingSubLevelPacket.class,
                (p, f, s) -> {
                    var last = pose(p, f, s.lastPose());
                    var now = pose(p, f, s.pose());
                    return last == s.lastPose() && now == s.pose()
                            ? s
                            : new ClientboundStartTrackingSubLevelPacket(
                                    s.plotCoordinate(),
                                    s.subLevelID(),
                                    last,
                                    new Pose3d(now),
                                    s.bounds(),
                                    s.name(),
                                    s.gameTick());
                });
        PayloadMap.register(
                ClientboundRecentlySplitSubLevelPacket.class,
                (p, f, s) -> {
                    var at = pose(p, f, s.pose());
                    return at == s.pose()
                            ? s
                            : new ClientboundRecentlySplitSubLevelPacket(
                                    s.splitSubLevelID(), s.splitFromID(), new Pose3d(at));
                });
        PayloadMap.register(
                ClientboundSableSnapshotDualPacket.class,
                (p, f, s) -> {
                    var entries = new ArrayList<ClientboundSableSnapshotDualPacket.Entry>();
                    for (var e : s.entries()) {
                        var at = pose(p, f, e.pose());
                        int t = turns(p, f, e.pose());
                        // UDP encodes later. Even an identity observer needs a copy: move mutates
                        // the live pose in place.
                        entries.add(
                                new ClientboundSableSnapshotDualPacket.Entry(
                                        e.plotCoordinate(),
                                        new Pose3d(at),
                                        turn(e.linearVelocity(), t),
                                        turn(e.angularVelocity(), t)));
                    }
                    return new ClientboundSableSnapshotDualPacket(
                            ((dev.starfold.mixin.SableSnapshotAccess) (Object) s)
                                    .starfold$interpolationTick(),
                            entries);
                });
    }

    /** Whom a body carries: whoever stands on it, and for a moment after whoever stood on it and is still by it. A jump,
     *  or a deck that falls faster than its crew can, does not end a passage aboard: without this a rider in the air over
     *  a seam changed chart alone and was left behind. */
    private record Aboard(dev.ryanhcode.sable.sublevel.ServerSubLevel body, long tick) {}

    private static final java.util.Map<net.minecraft.world.entity.Entity, Aboard> ABOARD_OF =
            new java.util.WeakHashMap<>();
    static final int ABOARD = 40;
    static final double REACH = 24;

    public static dev.ryanhcode.sable.sublevel.ServerSubLevel aboard(
            net.minecraft.world.entity.Entity entity) {
        long tick = entity.level().getGameTime();
        if (dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(entity)
                instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
            ABOARD_OF.put(entity, new Aboard(body, tick));
            return body;
        }
        // Collision tracking is contact-based, not cabin membership. Loose cargo can lose it
        // between steps.
        if (!(entity instanceof ServerPlayer)) {
            var container =
                    dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(entity.level());
            if (container != null)
                for (var candidate : container.getAllSubLevels())
                    if (candidate instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel body
                            && !body.isRemoved()
                            && inside(body, entity)) {
                        ABOARD_OF.put(entity, new Aboard(body, tick));
                        return body;
                    }
        }
        var was = ABOARD_OF.get(entity);
        if (was == null) return null;
        if (!aboard(
                tick - was.tick(),
                was.body().isRemoved()
                        ? Double.POSITIVE_INFINITY
                        : was.body()
                                .logicalPose()
                                .position()
                                .distance(entity.getX(), entity.getY(), entity.getZ()))) {
            ABOARD_OF.remove(entity);
            return null;
        }
        return was.body();
    }

    /** Test in plot coordinates: the bounds follow the full pose, not a fixed radius or a world-axis box. */
    public static boolean inside(
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            net.minecraft.world.entity.Entity entity) {
        var bounds = body.getPlot().getBoundingBox();
        if (bounds == null) return false;
        return inside(
                body.logicalPose(),
                bounds.toAABB().inflate(.5),
                new Vector3d(entity.getX(), entity.getY(), entity.getZ()));
    }

    static boolean inside(Pose3dc pose, net.minecraft.world.phys.AABB bounds, Vector3d feet) {
        var local = pose.transformPositionInverse(new Vector3d(feet));
        return bounds.contains(local.x, local.y, local.z);
    }

    /** Whether someone who left a deck so many ticks ago, and is so far from the body now, is still aboard it. */
    static boolean aboard(long ticks, double distance) {
        return ticks <= ABOARD && distance <= REACH;
    }

    /** Position weight of Sable 2.0.5's native step: two 0.025 s sub-steps, gravity 11.
     *  PRE-only rose 0.130005 blocks/s over 60 s with velocity ~0: a=0.130005/0.275=0.47275.
     *  The 42 sub-step pose measurements averaged 0.47267 (spread 0.0236 from float quantisation).
     *  tools/probe/rocket-hover.txt re-measures the drift this cancellation is meant to remove. */
    static final double POSITION_WEIGHT = 0.47275;

    /** POST consumes PRE's world impulse snapshot: same lost weight, mass, gravity, drag and dt in both phases. */
    private static final java.util.Map<
                    dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem,
                    java.util.Map<dev.ryanhcode.sable.sublevel.ServerSubLevel, Vector3d>>
            WEIGHING = new java.util.WeakHashMap<>();

    private static final java.util.Map<dev.ryanhcode.sable.sublevel.ServerSubLevel, Double>
            FLOATING = new java.util.WeakHashMap<>();

    private static void weighPre(
            dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physics, double step) {
        WEIGHING.remove(physics);
        var level = physics.getLevel();
        if (!PlanetLevels.is(level)) return;
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) return;
        var pending =
                new java.util.IdentityHashMap<
                        dev.ryanhcode.sable.sublevel.ServerSubLevel, Vector3d>();
        double c =
                dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData
                                .getUniversalDrag(level)
                        * step;
        for (var body : container.getAllSubLevels()) {
            if (body.isRemoved() || pending.containsKey(body)) continue;
            var at = body.logicalPose().position();
            double weight = HullWeight.at(level, new BentStripGeometry.Point(at.x, at.y, at.z));
            double lost = 1 - weight * LiftControl.HULL_GRAVITY;
            double mass = body.getMassTracker().getMass();
            var impulses =
                    weighPair(
                            dev.ryanhcode.sable.physics.config.dimension_physics
                                    .DimensionPhysicsData.getGravity(level),
                            lost,
                            mass,
                            step,
                            c);
            pending.put(body, impulses.post());
            if (lost > 0) weigh(physics, body, impulses.pre());
            LiftServer.pre(physics, body, step, c);
            // Only actual fluid contact may supply external support; no airborne velocity clamp.
            land(level, container, body, false, mass > 0 ? impulses.pre().y / mass : 0);
        }
        WEIGHING.put(physics, pending);
    }

    private static void weighPost(
            dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physics) {
        var pending = WEIGHING.remove(physics);
        if (pending == null) return;
        for (var entry : pending.entrySet()) {
            var body = entry.getKey();
            Double floor = FLOATING.remove(body);
            if (body.isRemoved()) continue;
            if (entry.getValue().lengthSquared() > 0) weigh(physics, body, entry.getValue());
            LiftServer.post(physics, body);
            // Enforce zero downward displacement too: native float integration can creep despite
            // zero speed.
            // This is the preceding submerged pose, never a lift to the water height (including a
            // sunken start).
            if (floor != null) {
                var at = body.logicalPose().position();
                if (at.y < floor)
                    physics.getPipeline()
                            .teleport(
                                    body,
                                    new Vector3d(at.x, HullLanding.supportedY(floor, at.y), at.z),
                                    body.logicalPose().orientation());
            }
        }
    }

    record WeightPair(Vector3d pre, Vector3d post) {}

    static WeightPair weighPair(Vector3d gravity, double lost, double mass, double step, double c) {
        var G = new Vector3d(gravity).mul(-lost * mass * step);
        var p =
                new Vector3d(G)
                        .mul(
                                (1 - POSITION_WEIGHT)
                                        / (POSITION_WEIGHT * (1 + c) + (1 - POSITION_WEIGHT)));
        var q = new Vector3d(G).sub(p).div(1 + c);
        return new WeightPair(p, q);
    }

    private static void weigh(
            dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physics,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            Vector3d impulse) {
        physics.getPhysicsHandle(body)
                .applyLinearImpulse(
                        body.logicalPose().transformNormalInverse(impulse, new Vector3d()));
    }

    /** Hold drift once per game tick, outside gravity; let upward thrust gather climb speed, not horizontal speed. */
    private static void damp(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        var at = body.logicalPose().position();
        double weight = HullWeight.at(level, new BentStripGeometry.Point(at.x, at.y, at.z));
        boolean steadied = Thrusters.steadied(body, level.getGameTime());
        if (steadied && weight > 0) {
            var held = container.physicsSystem().getPipeline();
            boolean chart =
                    !Space.holds(at.x, at.z)
                            && PlanetAccess.of(level)
                                    .inChart(
                                            net.minecraft.core.BlockPos.containing(
                                                    at.x, GroundCoordinates.FLOOR, at.z));
            var push = Thrusters.push(body, level.getGameTime());
            held.addLinearAndAngularVelocity(
                    body,
                    drift(
                            held.getLinearVelocity(body, new Vector3d()),
                            weight,
                            chart
                                    ? horizontalHold(body.logicalPose().orientation(), push)
                                    : Thrusters.HOLD),
                    new Vector3d());
        }
    }

    /** Unlike drift holding, landing protection also works above the air and without a pilot. */
    private static void land(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            boolean sample) {
        land(level, container, body, sample, 0);
    }

    private static void land(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            boolean sample,
            double weightCancellation) {
        var at = body.logicalPose().position();
        if (Space.holds(at.x, at.z)
                || !Thrusters.steadied(body, level.getGameTime())
                || !PlanetAccess.of(level)
                        .inChart(
                                net.minecraft.core.BlockPos.containing(
                                        at.x, GroundCoordinates.FLOOR, at.z))) return;
        var box = HullLanding.bounds(body);
        var ground =
                HullLanding.ground(
                        box.minX(),
                        box.minY(),
                        box.minZ(),
                        box.maxX(),
                        box.maxZ(),
                        (x, z) -> HullLanding.column(level, x, z));
        double clearance = ground.clearance();
        var pipeline = container.physicsSystem().getPipeline();
        var velocity = pipeline.getLinearVelocity(body, new Vector3d());
        // At fluid contact preserve the PRE weight cancellation: native gravity has yet to
        // integrate it.
        if (!ground.fluid() || clearance > 0) return;
        double support = weightCancellation;
        if (support > 0) FLOATING.put(body, at.y);
        double allowed = -support;
        if (allowed < -velocity.y)
            pipeline.addLinearAndAngularVelocity(
                    body, new Vector3d(0, -allowed - velocity.y, 0), new Vector3d());
        if (sample && Boolean.getBoolean("starfold.carryProbe") && level.getGameTime() % 10 == 0)
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE LAND tick {} pos {} {} {} bottom {} clearance {} vy {} allowed {} horizontal {} up {}",
                            level.getGameTime(),
                            at.x,
                            at.y,
                            at.z,
                            box.minY(),
                            clearance,
                            velocity.y,
                            allowed,
                            Math.hypot(velocity.x, velocity.z),
                            body.logicalPose().orientation().transform(new Vector3d(0, 1, 0)).y);
    }

    static final double HOLD_STILL = 4;

    /** Read the summed push in the body's axes: deck tilt is not a request to cruise sideways. */
    static double horizontalHold(org.joml.Quaterniondc orientation, BentStripGeometry.Point push) {
        var local = orientation.transformInverse(new Vector3d(push.x(), push.y(), push.z()));
        return local.lengthSquared() == 0 || Math.hypot(local.x, local.z) < .1 * local.length()
                ? HOLD_STILL
                : Thrusters.HOLD;
    }

    static Vector3d drift(Vector3d velocity, double weight, double horizontalHold) {
        return new Vector3d(velocity.x * horizontalHold, 0, velocity.z * horizontalHold)
                .mul(-weight / 20);
    }

    /** A body that has drifted into its chart's halo is moved to the chart that owns the place, whole: pose, motion
     *  and everyone standing on it, by one transport. */
    static void cross(net.minecraft.server.level.ServerLevel level) {
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        SableTickets.update(level, container);
        if (container == null) {
            ShipHost.get().releaseArrival(level);
            return;
        }
        ShipHost.get().advanceSurroundings(level, container);
        for (var body : java.util.List.copyOf(container.getAllSubLevels())) {
            if (body.isRemoved()) continue;
            var at = body.logicalPose().position();
            damp(level, container, body);
            land(level, container, body, true);
            if (Boolean.getBoolean("starfold.carryProbe")
                    && level.getGameTime() % 20 == 0
                    && !body.getTrackingPlayers().isEmpty()) {
                var velocity =
                        container
                                .physicsSystem()
                                .getPipeline()
                                .getLinearVelocity(body, new Vector3d());
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE BODY tick {} pos {} {} {} velocity {} {} {} speed {} weight {}",
                                level.getGameTime(),
                                at.x,
                                at.y,
                                at.z,
                                velocity.x,
                                velocity.y,
                                velocity.z,
                                velocity.length(),
                                HullWeight.at(
                                        level, new BentStripGeometry.Point(at.x, at.y, at.z)));
            }
            if (Space.holds(at.x, at.z)) {
                arrive(level, container, body);
                continue;
            }
            var t = GroundSeams.transport(level, new BentStripGeometry.Point(at.x, at.y, at.z));
            if (t == null) {
                leave(level, container, body);
                continue;
            }
            move(
                    level,
                    container,
                    body,
                    seam(motion(container, body, new BentStripGeometry.Point(at.x, at.y, at.z)), t),
                    true,
                    t);
        }
    }

    /** A seam is just another rigid change of labels, with both velocities expressed in the new chart. */
    static Space.Motion seam(Space.Motion motion, GroundSeams.Transport t) {
        var turn = turn(t.turns());
        return new Space.Motion(
                t.apply(motion.position()),
                turn.mul(motion.orientation()),
                t.vector(motion.velocity()),
                turn(t.turns()).transform(motion.spin()));
    }

    /** Leaving a canonical chart gives the body the planet's drift and spin before the region labels that motion. */
    private static void leave(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        var at = body.logicalPose().position();
        var access = PlanetAccess.of(level);
        if (!access.inChart(net.minecraft.core.BlockPos.containing(at.x, at.y, at.z))) return;
        var point = access.chart(new BentStripGeometry.Point(at.x, at.y, at.z));
        var shape = access.shape(at.x, at.z);
        if (!shape.owned(point.x(), point.z()) || !Space.leaves(point.y())) return;
        var clock = GroundClock.get(level).clock;
        int planet =
                access == PlanetAccess.SHARED
                        ? access.storagePlanet(at.x, at.z)
                        : clock.model.count() == 1 ? 0 : PlanetSystem.planet(level);
        var pose =
                new PlanetShape.Pose(CubeSurfaceTopology.Face.values()[access.face(at.x)], point);
        var universe =
                Space.out(
                        clock.model,
                        planet,
                        shape,
                        pose,
                        motion(container, body, point),
                        clock.tick());
        var frame =
                ShipHost.get()
                        .departureFrame(
                                level,
                                universe,
                                new Quaterniond(body.logicalPose().orientation()),
                                clock.model,
                                planet,
                                clock.tick());
        move(level, container, body, frame.level(universe, clock.model, clock.tick()), false);
        ShipHost.get().hullDeparted(level, planet, body.getUniqueId().toString());
    }

    /** Only a region body can arrive; the nearest centre decides which chart receives its universe motion. */
    private static void arrive(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        var at = body.logicalPose().position();
        if (!Space.holds(at.x, at.z)) return;
        var clock = GroundClock.get(level).clock;
        var access = PlanetAccess.of(level);
        var universe =
                ShipHost.get()
                        .surroundingsFrame(level)
                        .universe(
                                motion(
                                        container,
                                        body,
                                        new BentStripGeometry.Point(at.x, at.y, at.z)),
                                clock.model,
                                clock.tick());
        int planet = Space.nearest(clock.model, universe.position(), clock.tick());
        var shape = access == PlanetAccess.SHARED ? PlanetShape.of(planet) : PlanetShape.STANDARD;
        // Old saves put the other planet in another Level. Do not pretend its chart is in this one.
        if (access == PlanetAccess.LEGACY
                && planet != (clock.model.count() == 1 ? 0 : PlanetSystem.planet(level))) return;
        double height = Space.height(clock.model, planet, shape, universe.position(), clock.tick());
        if (!Space.enters(height)) return;
        var arrival = Space.in(clock.model, planet, shape, universe, clock.tick());
        var chart = arrival.motion();
        var anchor = access.bodyAnchor(planet, arrival.pose());
        boolean full = ShipHost.get().landingLoaded(level, anchor);
        if (!ShipHost.get().landingReady(height, full)) return;
        if (ArrivalProbe.ENABLED)
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE ARRIVAL_HANDOVER wall {} tick {} game {} planet {} height {} full {} branch {} column {}",
                            System.currentTimeMillis(),
                            level.getServer().getTickCount(),
                            level.getGameTime(),
                            planet,
                            height,
                            full,
                            full ? "full" : "emergency",
                            ShipHost.get().landingColumn(anchor));
        long start = ArrivalProbe.start();
        try {
            move(
                    level,
                    container,
                    body,
                    new Space.Motion(anchor, chart.orientation(), chart.velocity(), chart.spin()),
                    false);
            ShipHost.get().hullEnteredChart(level, body.getUniqueId().toString());
            ShipHost.get().hullArrived(level, body);
        } finally {
            ArrivalProbe.end("carry", start);
        }
    }

    /** Sable counts both blocks and radians per second; all frame arithmetic counts ticks. */
    public static Space.Motion motion(
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            BentStripGeometry.Point point) {
        var pipeline = container.physicsSystem().getPipeline();
        var linear = pipeline.getLinearVelocity(body, new Vector3d());
        var angular = pipeline.getAngularVelocity(body, new Vector3d());
        return new Space.Motion(
                point,
                new Quaterniond(body.logicalPose().orientation()),
                new BentStripGeometry.Point(linear.x / 20, linear.y / 20, linear.z / 20),
                angular.div(20));
    }

    /** Both poses and the crew take the same rigid change of Level coordinates, never a second chart projection. */
    static void move(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            Space.Motion next,
            boolean keepRiderVelocity,
            GroundSeams.Transport seam) {
        var before = new Pose3d(body.logicalPose());
        var riders = new java.util.LinkedHashMap<net.minecraft.world.entity.Entity, Vector3d>();
        for (var e : level.getAllEntities())
            if (aboard(e) == body) {
                riders.put(e, new Vector3d(e.getX(), e.getY(), e.getZ()));
                for (var passenger : e.getIndirectPassengers())
                    riders.put(
                            passenger,
                            new Vector3d(passenger.getX(), passenger.getY(), passenger.getZ()));
            }
        var turn =
                next.orientation()
                        .mul(new Quaterniond(before.orientation()).conjugate())
                        .normalize();
        var oldVelocity =
                motion(
                                container,
                                body,
                                new BentStripGeometry.Point(
                                        before.position().x,
                                        before.position().y,
                                        before.position().z))
                        .velocity();
        carry(before, next, body.logicalPose());
        carry(before, next, (Pose3d) body.lastPose());
        var pipeline = container.physicsSystem().getPipeline();
        var linear = next.velocity().scale(20);
        var angular = next.spin().mul(20);
        pipeline.resetVelocity(body);
        pipeline.teleport(body, body.logicalPose().position(), body.logicalPose().orientation());
        pipeline.addLinearAndAngularVelocity(
                body, new Vector3d(linear.x(), linear.y(), linear.z()), angular);
        logCarryOrientation(before, body);
        for (var entry : riders.entrySet()) {
            var rider = entry.getKey();
            var at = riderPoint(before, body.logicalPose(), entry.getValue());
            var v = rider.getDeltaMovement();
            boolean tracked = tracked(rider, body);
            var velocity =
                    riderVelocity(
                            turn,
                            new BentStripGeometry.Point(v.x, v.y, v.z),
                            oldVelocity,
                            next.velocity(),
                            tracked || keepRiderVelocity);
            if (Boolean.getBoolean("starfold.carryProbe")) {
                var oldLocal = before.transformPositionInverse(new Vector3d(entry.getValue()));
                var newLocal = body.logicalPose().transformPositionInverse(new Vector3d(at));
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE CARRY rider tick {} error {} up {} speed {} weight {} tracked {}",
                                level.getGameTime(),
                                oldLocal.distance(newLocal),
                                body.logicalPose().orientation().transform(new Vector3d(0, 1, 0)).y,
                                linear.length(),
                                HullWeight.at(level, next.position()),
                                tracked);
            }
            if (rider instanceof ServerPlayer player) {
                RELIABLE.put(player, level);
                CARRYING.put(player, body);
            }
            try {
                GroundSeams.carryBody(
                        rider,
                        new BentStripGeometry.Point(at.x, at.y, at.z),
                        velocity,
                        yaw(turn, rider.getYRot()),
                        tracked,
                        seam);
                if (rider instanceof ServerPlayer player) {
                    var f = ObserverFrames.get(player);
                    var origin = container.getOrigin();
                    var plot = body.getPlot().plotPos;
                    var packet =
                            new SableCarry(
                                    net.minecraft.world.level.ChunkPos.asLong(
                                            plot.x - origin.x, plot.z - origin.y),
                                    container.trackingSystem().getInterpolationTick(),
                                    new Pose3d(pose(player, f, body.logicalPose())),
                                    new Pose3d(pose(player, f, body.lastPose())),
                                    f.toClient(new BentStripGeometry.Point(at.x, at.y, at.z)),
                                    f.yawToClient(player.getYRot()),
                                    f,
                                    ShipHost.get().carriedFrame(player));
                    PacketMap.raw(
                            player,
                            new net.minecraft.network.protocol.common
                                    .ClientboundCustomPayloadPacket(packet));
                }
            } finally {
                if (rider instanceof ServerPlayer player) CARRYING.remove(player);
            }
        }
    }

    private static void logCarryOrientation(
            Pose3d before, dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        if (System.getProperty("starfold.probe") != null
                || Boolean.getBoolean("starfold.carryProbe"))
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE CARRY orientation before {} {} {} {} after {} {} {} {} angle {}",
                            before.orientation().x(),
                            before.orientation().y(),
                            before.orientation().z(),
                            before.orientation().w(),
                            body.logicalPose().orientation().x,
                            body.logicalPose().orientation().y,
                            body.logicalPose().orientation().z,
                            body.logicalPose().orientation().w,
                            Math.toDegrees(
                                    new Quaterniond(before.orientation())
                                            .normalize()
                                            .difference(
                                                    new Quaterniond(
                                                                    body.logicalPose()
                                                                            .orientation())
                                                            .normalize())
                                            .angle()));
    }

    public static void move(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            Space.Motion next,
            boolean keepRiderVelocity) {
        move(level, container, body, next, keepRiderVelocity, null);
    }

    public static boolean tracked(
            net.minecraft.world.entity.Entity rider,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        return dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(rider) == body;
    }

    static BentStripGeometry.Point riderVelocity(
            Quaterniond turn,
            BentStripGeometry.Point own,
            BentStripGeometry.Point before,
            BentStripGeometry.Point after,
            boolean tracked) {
        var v = turn.transform(new Vector3d(own.x(), own.y(), own.z()));
        var result = new BentStripGeometry.Point(v.x, v.y, v.z);
        var old = turn.transform(new Vector3d(before.x(), before.y(), before.z()));
        return tracked
                ? result
                : result.add(after.sub(new BentStripGeometry.Point(old.x, old.y, old.z)));
    }

    public static void carry(Pose3dc before, Space.Motion next, Pose3d pose) {
        var turn =
                next.orientation()
                        .mul(new Quaterniond(before.orientation()).conjugate())
                        .normalize();
        var at = turn.transform(new Vector3d(pose.position()).sub(before.position()));
        pose.position()
                .set(
                        at.x + next.position().x(),
                        at.y + next.position().y(),
                        at.z + next.position().z());
        turn.mul(pose.orientation(), pose.orientation()).normalize();
    }

    /** Feet keep the same place among the body's blocks, including a nonzero rotation point or scale. */
    public static Vector3d riderPoint(Pose3dc before, Pose3dc after, Vector3d feet) {
        return after.transformPosition(
                before.transformPositionInverse(new Vector3d(feet)), new Vector3d());
    }

    static float yaw(Quaterniond turn, float yaw) {
        double r = Math.toRadians(yaw);
        var f = turn.transform(new Vector3d(-Math.sin(r), 0, Math.cos(r)));
        return Math.hypot(f.x, f.z) < 1e-6 ? yaw : (float) Math.toDegrees(Math.atan2(-f.x, f.z));
    }

    private static int turns(ServerPlayer p, ObserverFrame f, Pose3dc pose) {
        var at = pose.position();
        return PacketMap.turns(PlanetAccess.of(p.level()), f, at.x(), at.y(), at.z());
    }

    /** A quarter turn of the plane as a rotation: {@link ChartLabSpace#rotate} carries +X to +Z. */
    static Quaterniond turn(int turns) {
        return new Quaterniond().rotateY(-Math.PI / 2 * Math.floorMod(turns, 4));
    }

    private static Vector3fc turn(Vector3fc v, int turns) {
        var r = ChartLabSpace.rotate(new BentStripGeometry.Point(v.x(), v.y(), v.z()), turns);
        return new Vector3f((float) r.x(), (float) r.y(), (float) r.z());
    }

    /** The same pose where the observer sees it as the server holds it, or where their chart does not show that place. */
    static Pose3dc pose(ServerPlayer p, ObserverFrame f, Pose3dc pose) {
        var at = pose.position();
        return pose(PacketMap.plane(p, f, at.x(), at.y(), at.z()), turns(p, f, pose), pose);
    }

    public static Pose3dc pose(BentStripGeometry.Point image, int t, Pose3dc pose) {
        var at = pose.position();
        if (image == null || t == 0 && image.x() == at.x() && image.z() == at.z()) return pose;
        return new Pose3d(
                new Vector3d(image.x(), image.y(), image.z()),
                turn(t).mul(pose.orientation()),
                new Vector3d(pose.rotationPoint()),
                new Vector3d(pose.scale()));
    }

    private SableFrames() {}

    /** Where a position is in the level: itself, or carried out of the plot of the hull that holds it. */
    public static org.joml.Vector3d world(
            net.minecraft.world.level.Level level, double x, double y, double z) {
        var at = new org.joml.Vector3d(x, y, z);
        var body = dev.ryanhcode.sable.Sable.HELPER.getContaining(level, x, z);
        return body == null ? at : body.logicalPose().transformPosition(at);
    }
}
