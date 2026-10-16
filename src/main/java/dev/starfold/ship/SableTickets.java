package dev.starfold.ship;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.GroundSeams;

import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

/** Cargo lives in raw entity sections, including the trailing halo of a hull in its new chart. */
public final class SableTickets {
    private static final TicketType<Long> TYPE =
            TicketType.create("starfold_sable_cargo", Long::compare);
    private static final Map<ServerLevel, ShipLeases.Plan> held = new HashMap<>();

    static void update(
            ServerLevel level, dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container) {
        var inputs = new ArrayList<ShipLeases.Input>();
        if (container != null)
            for (var body : container.getAllSubLevels())
                if (!body.isRemoved()) inputs.add(input(level, body));
        inputs.addAll(ShipDormancy.prepare(level, container));
        apply(level, ShipLeases.plan(inputs));
        ShipDormancy.finish(level, container);
    }

    public static ShipLeases.Input input(
            ServerLevel level, dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
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
        columns(cargo, box);
        // Ask ahead of the bow, including the destination's noncanonical trailing sections.
        for (int i = 0; i < 4; i++) {
            var t =
                    GroundSeams.transport(
                            level,
                            new Point(
                                    (i & 1) == 0 ? box.minX : box.maxX,
                                    body.logicalPose().position().y,
                                    (i & 2) == 0 ? box.minZ : box.maxZ));
            if (t != null) columns(cargo, box, t);
        }
        return new ShipLeases.Input(
                body.getUniqueId(), footprint, reasons(level, body, raw), cargo);
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
        for (var level : List.copyOf(held.keySet())) update(level, null);
    }

    private SableTickets() {}
}
