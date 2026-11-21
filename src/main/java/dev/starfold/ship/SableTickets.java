package dev.starfold.ship;

import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.GroundSeams;
import dev.starfold.ground.GroundTickets;

import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** Cargo lives in raw entity sections, including the trailing halo of a hull in its new chart. */
public final class SableTickets {
    private static final TicketType<Long> TYPE =
            TicketType.create("starfold_sable_cargo", Long::compare);
    private static final Map<ServerLevel, ShipLeases.Plan> held = new HashMap<>();
    private static final TicketType<Long> APPROACH = TicketType.create("starfold_sable_approach", Long::compare);
    private static final Map<ServerLevel, Set<Long>> approaches = new HashMap<>();
    private static final int APPROACH_LIMIT = 64;
    private static final int APPROACH_RADIUS = 1;

    static void update(
            ServerLevel level, dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container) {
        var inputs = new ArrayList<ShipLeases.Input>();
        var approach = new LinkedHashSet<Long>();
        if (container != null) {
            for (var body : container.getAllSubLevels()) {
                if (body.isRemoved()) continue;
                inputs.add(input(level, body, approach));
            }
        }
        applyApproach(level, approach);
        inputs.addAll(ShipDormancy.prepare(level, container));
        apply(level, ShipLeases.plan(inputs));
        ShipDormancy.finish(level, container);
    }

    public static ShipLeases.Input input(
            ServerLevel level, dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        return input(level, body, null);
    }

    private static ShipLeases.Input input(
            ServerLevel level, dev.ryanhcode.sable.sublevel.ServerSubLevel body, Set<Long> approach) {
        var bounds = body.boundingBox();
        var raw =
                new net.minecraft.world.phys.AABB(
                        bounds.minX(),
                        bounds.minY(),
                        bounds.minZ(),
                        bounds.maxX(),
                        bounds.maxY(),
                        bounds.maxZ());
        var footprint = new HashSet<Long>();
        columns(footprint, raw.inflate(1));
        var cargo = new HashSet<Long>();
        var box = raw.inflate(32);
        var ahead = box;
        var container =
                dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer.getContainer(level);
        if (container != null) {
            // Sable velocities are blocks/second; ticket lookahead is expressed in ticks.
            var velocity =
                    container
                            .physicsSystem()
                            .getPipeline()
                            .getLinearVelocity(body, new org.joml.Vector3d());
            ahead = GroundTickets.prefetch(box, velocity.x / 20, velocity.z / 20);
            if (approach != null && HelmServer.hasPilot(body)
                    && raw.minY < level.getMaxBuildHeight() && raw.maxY >= level.getMinBuildHeight())
                approachColumns(approach, raw.inflate(1), velocity.x, velocity.z);
        }
        // Retain the current halo's seam transports as well as the predicted destination's.
        for (var area : ahead == box ? List.of(box) : List.of(box, ahead)) {
            columns(cargo, area);
            for (int i = 0; i < 4; i++) {
                var t =
                        GroundSeams.transport(
                                level,
                                new Point(
                                        (i & 1) == 0 ? area.minX : area.maxX,
                                        body.logicalPose().position().y,
                                        (i & 2) == 0 ? area.minZ : area.maxZ));
                if (t != null) columns(cargo, area, t);
            }
        }
        return new ShipLeases.Input(
                body.getUniqueId(), footprint, reasons(level, body, raw), cargo);
    }

    static void approachColumns(Set<Long> result, AABB box, double vx, double vz) {
        double speed = Math.hypot(vx, vz);
        if (!Double.isFinite(speed) || speed <= 64) return;
        double distance = Math.min(speed * 3, 640);
        double dx = vx / speed, dz = vz / speed;
        // Cargo already gives the first 64 blocks a stronger ticket. Give the
        // approach a head start over the surrounding FULL-only view tickets.
        for (double near = 64; near < distance && result.size() < APPROACH_LIMIT; near += 16) {
            double step = Math.min(16, distance - near);
            var swept = box.move(dx * near, 0, dz * near).expandTowards(dx * step, 0, dz * step);
            int minX = net.minecraft.util.Mth.floor(swept.minX) >> 4;
            int maxX = net.minecraft.util.Mth.floor(swept.maxX) >> 4;
            int minZ = net.minecraft.util.Mth.floor(swept.minZ) >> 4;
            int maxZ = net.minecraft.util.Mth.floor(swept.maxZ) >> 4;
            for (int x = minX; x <= maxX && result.size() < APPROACH_LIMIT; x++)
                for (int z = minZ; z <= maxZ && result.size() < APPROACH_LIMIT; z++)
                    result.add(ChunkPos.asLong(x, z));
        }
    }

    private static Set<ShipLeases.Reason> reasons(
            ServerLevel level,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            net.minecraft.world.phys.AABB box) {
        var reasons = EnumSet.noneOf(ShipLeases.Reason.class);
        if (ShipHost.get().alwaysTicket(body.getUniqueId())) reasons.add(ShipLeases.Reason.ALWAYS);
        if (ShipHost.get().probeTicket(body.getUniqueId())) reasons.add(ShipLeases.Reason.PROBE);
        // Only the opt-in natural-unload experiment needs presence scans today.
        if (reasons.contains(ShipLeases.Reason.ALWAYS)) return reasons;
        if (ShipHost.get().retainLead(level, body)) reasons.add(ShipLeases.Reason.LEAD);
        if (dev.ryanhcode.sable.api.SubLevelHelper.getLoadingDependencyChain(body).size() > 1)
            reasons.add(ShipLeases.Reason.DEPENDENCY);
        if (HelmServer.hasPilot(body)) reasons.add(ShipLeases.Reason.PILOT);
        double wake = dev.ryanhcode.sable.SableConfig.SUB_LEVEL_TRACKING_RANGE.get() + 64;
        for (var player : level.players()) {
            if (aboard(body, player)) reasons.add(ShipLeases.Reason.PLAYER_ABOARD);
            var image = GroundSeams.box(level, box, player.getX(), player.getZ());
            if (image != null && near(image, player.position(), wake))
                reasons.add(ShipLeases.Reason.PLAYER_NEAR);
        }
        for (var entity : level.getAllEntities())
            if (!(entity instanceof net.minecraft.world.entity.player.Player)
                    && !entity.isRemoved()
                    && aboard(body, entity)) reasons.add(ShipLeases.Reason.NON_PLAYER_ABOARD);
        return reasons;
    }

    private static boolean aboard(
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            net.minecraft.world.entity.Entity entity) {
        var matches = aboardTests(body, entity);
        return worldCargo(matches);
    }

    /** Plot residents use the plot's entity sections, not the hull's world-side cargo tickets. */
    static boolean worldCargo(Set<String> matches) {
        return !matches.contains("plot")
                && (matches.contains("tracking") || matches.contains("inside"));
    }

    public static Set<String> aboardTests(
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            net.minecraft.world.entity.Entity entity) {
        var matches = new HashSet<String>();
        if (dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(entity) == body)
            matches.add("tracking");
        if (SableFrames.inside(body, entity)) matches.add("inside");
        var plot = body.getPlot().getBoundingBox();
        if (plot != null && plot.toAABB().contains(entity.position())) matches.add("plot");
        return Set.copyOf(matches);
    }

    static boolean near(
            net.minecraft.world.phys.AABB box, net.minecraft.world.phys.Vec3 p, double distance) {
        double x = Math.max(box.minX - p.x, Math.max(0, p.x - box.maxX));
        double y = Math.max(box.minY - p.y, Math.max(0, p.y - box.maxY));
        double z = Math.max(box.minZ - p.z, Math.max(0, p.z - box.maxZ));
        return x * x + y * y + z * z <= distance * distance;
    }

    /** Match Sable's section-index check before it acquires any XZ columns. */
    public static BoundingBox3i collisionBounds(
            BoundingBox3i bounds, int minSection, int sectionsCount) {
        if (sectionsCount > 0
                && bounds.maxY() >= minSection
                && bounds.minY() < (long) minSection + sectionsCount) return bounds;
        // The companion constructor normalizes endpoints. Set an empty X range explicitly so
        // both upstream column loops skip work; ticket expiry and other physics objects still run.
        bounds.minX = 1;
        bounds.maxX = 0;
        return bounds;
    }

    public static Set<Long> held(ServerLevel level, UUID ship) {
        var plan = held.get(level);
        return plan == null ? Set.of() : plan.columns(ship);
    }

    private static void apply(ServerLevel level, ShipLeases.Plan plan) {
        var wanted = plan.columns();
        var old = held.get(level);
        var previous = old == null ? Set.<Long>of() : old.columns();
        for (long key : wanted)
            if (!previous.contains(key))
                level.getChunkSource().addRegionTicket(TYPE, new ChunkPos(key), 2, key);
        for (long key : previous)
            if (!wanted.contains(key))
                level.getChunkSource().removeRegionTicket(TYPE, new ChunkPos(key), 2, key);
        if (wanted.isEmpty()) held.remove(level);
        else held.put(level, plan);
    }

    private static void applyApproach(ServerLevel level, Set<Long> wanted) {
        var previous = approaches.getOrDefault(level, Set.of());
        for (long key : wanted)
            if (!previous.contains(key))
                level.getChunkSource().addRegionTicket(APPROACH, new ChunkPos(key), APPROACH_RADIUS, key);
        for (long key : previous)
            if (!wanted.contains(key))
                level.getChunkSource().removeRegionTicket(APPROACH, new ChunkPos(key), APPROACH_RADIUS, key);
        if (wanted.isEmpty()) approaches.remove(level);
        else approaches.put(level, wanted);
    }

    /** Quarter turns preserve an axis-aligned box, including its noncanonical trailing edge. */
    static void columns(
            Set<Long> result, net.minecraft.world.phys.AABB box, GroundSeams.Transport t) {
        var a = t.apply(new Point(box.minX, box.minY, box.minZ));
        var b = t.apply(new Point(box.maxX, box.maxY, box.maxZ));
        columns(
                result,
                new net.minecraft.world.phys.AABB(a.x(), a.y(), a.z(), b.x(), b.y(), b.z()));
    }

    public static void columns(Set<Long> result, net.minecraft.world.phys.AABB box) {
        for (int x = (int) Math.floor(box.minX) >> 4; x <= ((int) Math.floor(box.maxX) >> 4); x++)
            for (int z = (int) Math.floor(box.minZ) >> 4;
                    z <= ((int) Math.floor(box.maxZ) >> 4);
                    z++) result.add(ChunkPos.asLong(x, z));
    }

    static void clear() {
        for (var level : List.copyOf(approaches.keySet())) applyApproach(level, Set.of());
        for (var level : List.copyOf(held.keySet())) update(level, null);
    }

    private SableTickets() {}
}
