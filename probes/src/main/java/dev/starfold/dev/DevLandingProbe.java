package dev.starfold.dev;

import dev.starfold.ship.Airstair;
import dev.starfold.ship.HullLanding;
import dev.starfold.ship.HullLandingClient;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Development-only input driver. Reads the public landing cue; never moves a hull or player directly. */
final class DevLandingProbe {
    private static int remaining, stable;
    private static double previous, rate;
    private static final Set<Integer> held = new HashSet<>();
    private static List<Vec3> route = List.of();
    private static int waypoint;
    private static Vec3 foot;
    private static boolean returning;

    private static boolean informational;

    static void follow(int ticks, boolean reportTimeout) {
        informational = reportTimeout;
        remaining = ticks;
        stable = 0;
        previous = 0;
        rate = 0;
        route = List.of();
    }

    static boolean tick(Minecraft mc) {
        if (remaining <= 0 || mc.player == null) return false;
        if (--remaining == 0) {
            keys(mc, Set.of());
            if (!informational) throw new IllegalStateException("landing input driver timeout");
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE LANDING TIMEOUT informational site {} clearance {}",
                            HullLandingClient.site(),
                            HullLandingClient.clearance());
            return false;
        }
        if (!route.isEmpty()) {
            var p = route.get(waypoint);
            double dx = p.x - mc.player.getX(), dz = p.z - mc.player.getZ();
            boolean reached = Math.hypot(dx, dz) < .22;
            if (reached) {
                keys(mc, Set.of());
                if (++waypoint == route.size()) {
                    double distance =
                            Math.hypot(mc.player.getX() - foot.x, mc.player.getZ() - foot.z);
                    if (!returning && distance < 20)
                        throw new IllegalStateException("excursion short " + distance);
                    if (!mc.player.onGround())
                        throw new IllegalStateException("excursion not on ground");
                    org.slf4j.LoggerFactory.getLogger("starfold-probe")
                            .info(
                                    "PROBE EXCURSION returning {} distanceFromFoot {} foot {} pos {} health {} food {}",
                                    returning,
                                    distance,
                                    foot,
                                    mc.player.position(),
                                    mc.player.getHealth(),
                                    mc.player.getFoodData().getFoodLevel());
                    remaining = 0;
                    route = List.of();
                    return false;
                }
                return true;
            }
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setXRot(0);
            keys(mc, Set.of(87));
            return true;
        }
        var site = HullLandingClient.site();
        boolean safe = site.deployable();
        stable = safe ? stable + 1 : 0;
        if (stable >= 30) {
            keys(mc, Set.of());
            remaining = 0;
            return false;
        }
        var aid = site.aid();
        var next = new HashSet<Integer>();
        if (!safe && aid.status() == HullLanding.AidStatus.FOUND) {
            double angle = Math.toDegrees(Math.atan2(aid.right(), aid.forward()));
            double delta = net.minecraft.util.Mth.wrapDegrees(angle - previous);
            previous = angle;
            rate = .5 * rate + .5 * delta;
            double predicted = angle + rate * 8;
            if (Math.abs(predicted) > 3) next.add(predicted < 0 ? 65 : 68);
            if (Math.abs(angle) < 5) {
                next.add(87);
                if (aid.distance() > 40) next.add(341);
            }
        }
        keys(mc, next);
        if (remaining % 20 == 0)
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE LANDAID remaining {} site {} clearance {}",
                            remaining,
                            site,
                            HullLandingClient.ground().clearance());
        return true;
    }

    private static void keys(Minecraft mc, Set<Integer> next) {
        for (int k : new HashSet<>(held))
            if (!next.contains(k)) {
                mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), k, 0, 0, 0);
                held.remove(k);
            }
        for (int k : next)
            if (held.add(k)) mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), k, 0, 1, 0);
    }

    private static List<Vec3> outward;
    private static Vec3 measuredFoot;
    private static double side, localFootX, localFootZ;

    private static dev.ryanhcode.sable.sublevel.SubLevel loopHull(Minecraft mc) {
        var bodies = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
        return bodies == null ? null : bodies.getAllSubLevels().stream()
                .filter(hull -> ProbeSession.loopHull.startsWith(hull.getUniqueId() + ","))
                .findFirst().orElse(null);
    }

    static void logLoopStair(Minecraft mc, String label) {
        var logger = org.slf4j.LoggerFactory.getLogger("starfold-probe");
        var body = loopHull(mc);
        logger.info("PROBE LOOPSTAIR {} expected={} loaded={}", label, ProbeSession.loopHull, body != null);
        if (body == null) return;
        for (var holder : body.getPlot().getLoadedChunks())
            for (var be : holder.getChunk().getBlockEntities().values())
                if (be instanceof Airstair.Stair stair)
                    logger.info("PROBE LOOPSTAIR {} pos={} drop={} visual={} foot={}",
                            label, stair.getBlockPos(), stair.length, stair.visual, Airstair.foot(stair, 0));
    }

    static org.joml.Vector3d loopStairFoot(Minecraft mc) {
        var body = loopHull(mc);
        if (body != null)
            for (var holder : body.getPlot().getLoadedChunks())
                for (var be : holder.getChunk().getBlockEntities().values())
                    if (be instanceof Airstair.Stair stair && stair.length > 0 && stair.visual >= 1)
                        return Airstair.foot(stair, 1.5);
        throw new IllegalStateException("Loop hull has no deployed stair for the ground route");
    }

    static boolean loopStairDeployed(Minecraft mc) {
        var body = loopHull(mc);
        if (body == null) return false;
        for (var holder : body.getPlot().getLoadedChunks())
            for (var be : holder.getChunk().getBlockEntities().values())
                if (be instanceof Airstair.Stair stair && stair.length > 0 && stair.visual >= 1)
                    return true;
        return false;
    }

    static List<String> loopStairs(Minecraft mc, boolean back) {
        var body = loopHull(mc);
        if (body == null) throw new IllegalStateException("Loop hull is not loaded");
        var box = body.getPlot().getBoundingBox();
        double centerX = (box.minX() + box.maxX() + 1) / 2.0;
        double centerZ = (box.minZ() + box.maxZ() + 1) / 2.0;
        for (var holder : body.getPlot().getLoadedChunks())
            for (var be : holder.getChunk().getBlockEntities().values())
                if (be instanceof Airstair.Stair stair && stair.length > 0 && stair.visual >= 1) {
                    var end = Airstair.foot(stair, .6);
                    if (back)
                        return List.of("guide walk " + end.x + " " + end.z + " 180",
                                "walklocal 5.1 0 200 jump", "walklocal 2.5 0 160 jump");
                    var foot = body.logicalPose().transformPositionInverse(end);
                    String footOffset = (foot.x - centerX) + " " + (foot.z - centerZ);
                    // Copper Swift's booster and barrel occupy the center aisle. Use the side
                    // deck and step over the tank before lining up with the sill.
                    return List.of(
                            "walklocal 0.7 1 70",
                            "walklocal 2.6 1 120",
                            "walklocal 3.5 1 110 jump",
                            "walklocal 3.8 0 100",
                            "walklocal 5.1 0 100",
                            "walklocal " + footOffset + " 300");
                }
        throw new IllegalStateException("Loop hull has no deployed stair");
    }

    static List<String> stairs(Minecraft mc, boolean back) {
        if (back)
            return List.of(
                    "walklocal " + localFootX + " " + localFootZ + " 300",
                    "walklocal -5 " + (side * 5.2) + " 300",
                    "walklocal -5 " + (side * 2) + " 240",
                    "walklocal -6 0 120");
        var bodies = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
        var body = bodies.getAllSubLevels().iterator().next();
        var box = body.getPlot().getBoundingBox();
        for (var holder : body.getPlot().getLoadedChunks())
            for (var be : holder.getChunk().getBlockEntities().values())
                if (be instanceof Airstair.Stair stair && stair.length > 0 && stair.width > 0) {
                    var end = Airstair.foot(stair, 0);
                    measuredFoot = new Vec3(end.x, end.y, end.z);
                    var path = groundRoute(mc, measuredFoot);
                    if (path.isEmpty()) continue;
                    var local = body.logicalPose().transformPositionInverse(end);
                    localFootX = local.x - (box.minX() + box.maxX() + 1) / 2.0;
                    localFootZ = local.z - (box.minZ() + box.maxZ() + 1) / 2.0;
                    side = Math.signum(localFootZ);
                    org.slf4j.LoggerFactory.getLogger("starfold-probe")
                            .info(
                                    "PROBE STAIR FOOT {} local {} {} side {}",
                                    measuredFoot,
                                    localFootX,
                                    localFootZ,
                                    side);
                    return List.of(
                            "walklocal -6 0 100",
                            "walklocal -5 " + (side * 2) + " 180",
                            "looklocal -5 5.5 " + (side * 4),
                            "wait 10",
                            "openhitdoor",
                            "wait 10",
                            "walklocal -5 " + (side * 5.2) + " 240",
                            "walklocal " + localFootX + " " + localFootZ + " 300");
                }
        throw new IllegalStateException("no deployed stair with an open ground route");
    }

    static void walk(Minecraft mc, boolean back) {
        informational = false;
        returning = back;
        keys(mc, Set.of());
        if (back) {
            var r = new ArrayList<>(outward);
            Collections.reverse(r);
            route = r;
            waypoint = 0;
            remaining = 2400;
            return;
        }
        foot = measuredFoot;
        if (foot == null || Math.hypot(mc.player.getX() - foot.x, mc.player.getZ() - foot.z) > .5)
            throw new IllegalStateException("not at measured stair foot");
        outward = groundRoute(mc, foot);
        if (outward.isEmpty()) throw new IllegalStateException("ground route disappeared");
        route = outward;
        waypoint = 0;
        remaining = 2400;
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE EXCURSION route {} foot {} endpoint {}",
                        route.size(),
                        foot,
                        route.getLast());
    }

    private static List<Vec3> groundRoute(Minecraft mc, Vec3 foot) {
        var start = BlockPos.containing(foot.x, foot.y + .1, foot.z);
        int y = start.getY();
        var queue = new ArrayDeque<BlockPos>();
        var parents = new HashMap<BlockPos, BlockPos>();
        queue.add(start);
        parents.put(start, start);
        BlockPos found = null;
        while (!queue.isEmpty() && parents.size() < 16384) {
            var p = queue.remove();
            if (Math.hypot(p.getX() + .5 - foot.x, p.getZ() + .5 - foot.z) >= 21) {
                found = p;
                break;
            }
            for (int[] d : new int[][] {{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
                var n = p.offset(d[0], 0, d[1]);
                if (parents.containsKey(n)
                        || Math.hypot(n.getX() - start.getX(), n.getZ() - start.getZ()) > 48
                        || !mc.level.getChunkSource().hasChunk(n.getX() >> 4, n.getZ() >> 4))
                    continue;
                // Integer-height solid terrain; no jumps, downhill traps, fluids, or block/head
                // collisions.
                var ground = mc.level.getBlockState(n.below());
                if (ground.getCollisionShape(mc.level, n.below()).isEmpty()
                        || !ground.getFluidState().isEmpty()
                        || !mc.level.getBlockState(n).getCollisionShape(mc.level, n).isEmpty()
                        || !mc.level
                                .getBlockState(n.above())
                                .getCollisionShape(mc.level, n.above())
                                .isEmpty()) continue;
                parents.put(n, p);
                queue.add(n);
            }
        }
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE GROUND ROUTE foot {} cells {} found {} neighbors {}",
                        foot,
                        parents.size(),
                        found,
                        List.of(
                                mc.level.getBlockState(start.below()).toString(),
                                mc.level.getBlockState(start.north()).toString(),
                                mc.level.getBlockState(start.south()).toString()));
        if (found == null) return List.of();
        var reverse = new ArrayList<Vec3>();
        for (var p = found; !p.equals(start); p = parents.get(p))
            reverse.add(new Vec3(p.getX() + .5, y, p.getZ() + .5));
        reverse.add(foot);
        Collections.reverse(reverse);
        return List.copyOf(reverse);
    }

    private DevLandingProbe() {}
}
