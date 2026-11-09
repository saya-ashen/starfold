package dev.starfold.ship;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.GroundSeams;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.ground.ObserverFrames;
import dev.starfold.ground.PacketMap;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.DevelopmentHooks;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;

import net.minecraft.server.level.ServerPlayer;

import org.joml.Quaterniond;
import org.joml.Vector3d;

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
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(HullBottom::chunkLoaded);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
                                event) -> RELIABLE.remove(event.getEntity()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> {
                    RELIABLE.clear();
                    CARRYING.clear();
                    HullPhysics.clear();
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
                        HullPhysics.weighPre(event.getPhysicsSystem(), event.getTimeStep()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent event) ->
                        HullPhysics.weighPost(event.getPhysicsSystem()));
        SableObserverPackets.register();
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
        // An emptied body is marked removed and loses its Rapier body before the container drops
        // it; tracking can still name it. Callers read its velocity, so it is nobody's hull.
        if (dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(entity)
                        instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel body
                && !body.isRemoved()) {
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

    /** Whether a live hull's world box, as this player's chart draws it, is within distance. */
    public static boolean near(ServerPlayer player, double distance) {
        var container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(player.level());
        if (container == null) return false;
        for (var candidate : container.getAllSubLevels()) {
            if (!(candidate instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel body)
                    || body.isRemoved()) continue;
            var b = body.boundingBox();
            var image =
                    GroundSeams.box(
                            player.level(),
                            new net.minecraft.world.phys.AABB(
                                    b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()),
                            player.getX(),
                            player.getZ());
            if (image != null && SableTickets.near(image, player.position(), distance)) return true;
        }
        return false;
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

    /** Broad phase for the same inflated plot used by inside, including rotated/scaled cabins. */
    public static net.minecraft.world.phys.AABB cabinBounds(
            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        var bounds = body.getPlot().getBoundingBox();
        return bounds == null ? null : cabinBounds(body.logicalPose(), bounds.toAABB());
    }

    static net.minecraft.world.phys.AABB cabinBounds(
            Pose3dc pose, net.minecraft.world.phys.AABB plot) {
        return new dev.ryanhcode.sable.companion.math.BoundingBox3d(plot.inflate(.5))
                .transform(pose)
                .toMojang();
    }

    /** The cabin plus the anchor-centred grace sphere; a large hull's crew need not be near its anchor. */
    static net.minecraft.world.phys.AABB crewBounds(
            Pose3dc pose, net.minecraft.world.phys.AABB cabin) {
        var at = pose.position();
        var grace =
                new net.minecraft.world.phys.AABB(
                        at.x() - REACH,
                        at.y() - REACH,
                        at.z() - REACH,
                        at.x() + REACH,
                        at.y() + REACH,
                        at.z() + REACH);
        return cabin == null ? grace : grace.minmax(cabin);
    }

    static boolean inside(Pose3dc pose, net.minecraft.world.phys.AABB bounds, Vector3d feet) {
        var local = pose.transformPositionInverse(new Vector3d(feet));
        return bounds.contains(local.x, local.y, local.z);
    }

    /** Whether someone who left a deck so many ticks ago, and is so far from the body now, is still aboard it. */
    static boolean aboard(long ticks, double distance) {
        return ticks <= ABOARD && distance <= REACH;
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
            HullPhysics.damp(level, container, body);
            HullPhysics.land(level, container, body, true);
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
        if (!shape.owned(point.x(), point.z())) return;
        var clock = GroundClock.get(level).clock;
        int planet = access.storagePlanet(at.x, at.z);
        if (!Space.leaves(clock.model.body(planet), point.y())) return;
        var pose =
                new PlanetShape.Pose(CubeSurfaceTopology.Face.VALUES[access.face(at.x)], point);
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
        var shape = PlanetShape.of(planet);
        double height = Space.height(clock.model, planet, shape, universe.position(), clock.tick());
        if (!Space.enters(clock.model.body(planet), height)) return;
        var arrival = Space.in(clock.model, planet, shape, universe, clock.tick());
        var chart = arrival.motion();
        var anchor = access.bodyAnchor(planet, arrival.pose());
        boolean full = ShipHost.get().landingLoaded(level, anchor);
        if (!ShipHost.get().landingReady(clock.model.body(planet), height, full)) return;
        if (DevelopmentHooks.arrival.enabled())
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
        long start = DevelopmentHooks.arrival.start();
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
            DevelopmentHooks.arrival.end("carry", start);
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
        for (var e : crew(level, body, before))
            if (!e.isRemoved() && aboard(e) == body) {
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

    private static java.util.Set<net.minecraft.world.entity.Entity> crew(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            Pose3dc before) {
        var candidates =
                new java.util.LinkedHashSet<>(
                        GroundSeams.entities(
                                level,
                                crewBounds(before, cabinBounds(body))
                                        .inflate(level.getMaxEntityRadius())));
        // Contact can refer to the preceding pose while a fast hull is being re-anchored.
        var plot = body.getPlot().getBoundingBox();
        if (plot != null)
            candidates.addAll(
                    GroundSeams.entities(
                            level,
                            cabinBounds(body.lastPose(), plot.toAABB())
                                    .inflate(level.getMaxEntityRadius())));
        // Known contact/grace riders survive a relabel, even before their section is updated.
        for (var entry : ABOARD_OF.entrySet())
            if (entry.getValue().body() == body && entry.getKey().level() == level)
                candidates.add(entry.getKey());
        candidates.addAll(level.players());
        return candidates;
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

    /** A quarter turn of the plane as a rotation: {@link ChartLabSpace#rotate} carries +X to +Z. */
    static Quaterniond turn(int turns) {
        return new Quaterniond().rotateY(-Math.PI / 2 * Math.floorMod(turns, 4));
    }

    /** The same pose where the observer sees it as the server holds it, or where their chart does not show that place. */
    static Pose3dc pose(ServerPlayer player, ObserverFrame frame, Pose3dc pose) {
        return SableObserverPackets.pose(player, frame, pose);
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
