package dev.starfold.ship;

import dev.starfold.flight.FlightMode;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.sky.Space;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/** Hull clearance in a chart, not height above the planet's nominal radius. */
public final class HullLanding {
    static final double LAND_MIN = 4, LAND_SLOPE = .75;

    /** Signed downward speed in blocks/s. Unknown ground and rising motion are left alone. */
    static double descent(double clearance, double downward) {
        return descent(clearance, downward, false);
    }

    /** Desired speed envelope only; the lift controller must supply the physical braking force. */
    static double descent(double clearance, double downward, boolean fluid) {
        return Double.isFinite(clearance)
                ? Math.min(
                        downward,
                        fluid && clearance <= 0
                                ? 0
                                : LAND_MIN + Math.max(0, clearance) * LAND_SLOPE)
                : downward;
    }

    /** Zero downward displacement for an already-contacting substep, not a lift to the fluid's surface. */
    static double supportedY(double previous, double next) {
        return Math.max(previous, next);
    }

    record Surface(double height, boolean fluid, boolean lava) {
        Surface(double height, boolean fluid) {
            this(height, fluid, false);
        }
    }

    public enum Kind {
        UNKNOWN,
        LAND,
        WATER,
        LAVA,
        SLOPE,
        PAD
    }

    record Survey(Kind kind, double highest) {}

    public enum AidStatus {
        OFF,
        SEARCHING,
        FOUND,
        NONE
    }

    /** Horizontal direction in the helm's right/forward frame, exactly as the drift widget. */
    public record Aid(AidStatus status, double right, double forward) {
        static final Aid OFF = new Aid(AidStatus.OFF, 0, 0);

        public double distance() {
            return Math.hypot(right, forward);
        }
    }

    public record Site(Kind kind, boolean deployable, Aid aid) {
        Site(Kind kind, boolean deployable) {
            this(kind, deployable, Aid.OFF);
        }

        public static final Site UNKNOWN = new Site(Kind.UNKNOWN, false);
    }

    static final class Search {
        HullLandingSearch scan;
        double x, z;
        org.joml.Quaterniond heading;
        org.joml.Vector3d target;
        long refreshed, lastTick = Long.MIN_VALUE;
        Aid last = Aid.OFF;

        void clear() {
            scan = null;
            target = null;
            last = Aid.OFF;
            lastTick = Long.MIN_VALUE;
        }

        Site update(
                net.minecraft.server.level.ServerLevel level,
                dev.ryanhcode.sable.sublevel.ServerSubLevel body,
                net.minecraft.core.Direction facing,
                Site site) {
            if (site.deployable() || Thrusters.scopeTier(body) < 3 || !eligible(level, body)) {
                clear();
                return site;
            }
            long tick = level.getGameTime();
            if (tick == lastTick) return new Site(site.kind(), site.deployable(), last);
            lastTick = tick;
            var at = body.logicalPose().position();
            var q = body.logicalPose().orientation();
            if (target != null
                    && (Math.hypot(target.x - at.x, target.z - at.z) > 160
                            || !candidate(level, body, target.x - at.x, target.z - at.z)
                                    .deployable())) {
                target = null;
                scan = null;
            }
            if (target == null) {
                if (scan == null
                        || Math.hypot(at.x - x, at.z - z) > 4
                        || Math.abs(q.dot(heading)) < Math.cos(Math.toRadians(1))
                        || scan.complete() && tick - refreshed >= 40) {
                    scan = new HullLandingSearch();
                    x = at.x;
                    z = at.z;
                    heading = new org.joml.Quaterniond(q);
                    refreshed = tick;
                }
                boolean finished = scan.complete();
                var hit =
                        scan.step(
                                p ->
                                        candidate(level, body, x + p.x() - at.x, z + p.z() - at.z)
                                                .deployable());
                if (!finished && scan.complete()) refreshed = tick;
                if (hit != null) target = new org.joml.Vector3d(x + hit.x(), 0, z + hit.z());
            }
            if (target == null)
                last = new Aid(scan.complete() ? AidStatus.NONE : AidStatus.SEARCHING, 0, 0);
            else {
                var v =
                        q.conjugate(new org.joml.Quaterniond())
                                .transform(
                                        new org.joml.Vector3d(target.x - at.x, 0, target.z - at.z));
                var f = new org.joml.Vector3d(facing.getStepX(), 0, facing.getStepZ());
                last =
                        new Aid(
                                AidStatus.FOUND,
                                v.dot(new org.joml.Vector3d(f).cross(0, 1, 0)),
                                v.dot(f));
            }
            return new Site(site.kind(), site.deployable(), last);
        }
    }

    static boolean safeExit(double clearance, boolean deployed) {
        return safeExit(clearance, deployed, Kind.LAND);
    }

    static boolean safeExit(double clearance, boolean deployed, Kind kind) {
        return deployed
                && Double.isFinite(clearance)
                && clearance <= 1
                && (kind == Kind.LAND || kind == Kind.PAD || kind == Kind.SLOPE);
    }

    static boolean landedWithStair(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        if (!Airstair.deployed(body)) return false;
        var box = bounds(body);
        var survey =
                survey(
                        box.minX(),
                        box.minZ(),
                        box.maxX(),
                        box.maxZ(),
                        (x, z) -> column(level, x, z));
        return safeExit(ground(body).clearance(), true, survey.kind());
    }

    public static boolean showSurface(int tier, FlightMode mode, double clearance) {
        return tier >= 3
                && mode == FlightMode.CHART
                && Double.isFinite(clearance)
                && clearance < 200;
    }

    /** Stair planning survey of the bounding footprint. Unknown is never safe. */
    static Survey survey(double minX, double minZ, double maxX, double maxZ, SurfaceColumn column) {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        boolean water = false, lava = false, unknown = false;
        for (int x = (int) Math.floor(minX); x <= Math.floor(Math.nextDown(maxX)); x++)
            for (int z = (int) Math.floor(minZ); z <= Math.floor(Math.nextDown(maxZ)); z++) {
                var s = column.surface(x, z);
                if (!Double.isFinite(s.height())) {
                    unknown = true;
                    continue;
                }
                low = Math.min(low, s.height());
                high = Math.max(high, s.height());
                water |= s.fluid();
                lava |= s.lava();
            }
        return new Survey(
                unknown || !Double.isFinite(high)
                        ? Kind.UNKNOWN
                        : lava
                                ? Kind.LAVA
                                : water
                                        ? Kind.WATER
                                        : high - low > Airstair.MAX_REACH ? Kind.SLOPE : Kind.LAND,
                high);
    }

    static boolean eligible(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        var at = body.logicalPose().position();
        if (!PlanetLevels.is(level) || Space.holds(at.x, at.z)) return false;
        var ground = ground(body);
        return showSurface(3, FlightMode.CHART, ground.clearance());
    }

    static Site site(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        if (!eligible(level, body)) return Site.UNKNOWN;
        var site = candidate(level, body, 0, 0);
        return new Site(
                site.kind() == Kind.LAND && StarterShip.overPad(level, body)
                        ? Kind.PAD
                        : site.kind(),
                site.deployable());
    }

    /** Search and Below use the identical loaded-column survey and actual stair planner. No pose mutation. */
    static Site candidate(
            net.minecraft.server.level.ServerLevel level,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            double dx,
            double dz) {
        var box = bounds(body);
        var survey =
                survey(
                        box.minX() + dx,
                        box.minZ() + dz,
                        box.maxX() + dx,
                        box.maxZ() + dz,
                        (x, z) -> column(level, x, z));
        return new Site(
                survey.kind(),
                survey.kind() == Kind.LAND
                        && Airstair.canDeployAt(
                                level, body, Math.max(0, box.minY() - survey.highest()), dx, dz));
    }

    public record Ground(double clearance, boolean fluid) {}

    @FunctionalInterface
    interface SurfaceColumn {
        Surface surface(int x, int z);
    }

    /** Signed collision-bottom clearance, paired with the terrain columns actually covered. */
    public static Ground ground(dev.ryanhcode.sable.sublevel.SubLevel body) {
        return HullBottom.ground(body, (x, z) -> column(body.getLevel(), x, z, true));
    }

    /** Fresh physical bounds: server cached bounds precede physics; client boundingBox is swept for rendering. */
    public static dev.ryanhcode.sable.companion.math.BoundingBox3d bounds(
            dev.ryanhcode.sable.sublevel.SubLevel body) {
        var plot = body.getPlot().getBoundingBox();
        var box =
                new dev.ryanhcode.sable.companion.math.BoundingBox3d(
                        plot.minX(),
                        plot.minY(),
                        plot.minZ(),
                        plot.maxX() + 1.0,
                        plot.maxY() + 1.0,
                        plot.maxZ() + 1.0);
        box.transform(body.logicalPose(), new org.joml.Matrix4d(), box);
        return box;
    }

    /** Server halo columns use canonical owners, just as the carry/approach chunk views do. Client columns
     *  are already images in the observer's chart. A seam-spanning hull samples the centre face's plane. */
    public static double surface(Level level, int x, int z) {
        return column(level, x, z).height();
    }

    static Surface column(Level level, int x, int z) {
        return column(level, x, z, false); // Stair survey/planning keeps its existing heightmap contract.
    }

    private static Surface column(Level level, int x, int z, boolean collisionTop) {
        if (!level.isClientSide) {
            var access = PlanetAccess.of(level);
            var pos = new BlockPos(x, GroundCoordinates.FLOOR, z);
            if (!access.inChart(pos)) return new Surface(Double.NaN, false);
            var owner = access.address(pos).owner();
            x = owner.getX();
            z = owner.getZ();
        }
        var chunk =
                level instanceof net.minecraft.server.level.ServerLevel server
                        ? server.getChunkSource().getChunkNow(x >> 4, z >> 4)
                        : level.getChunkSource()
                                .getChunk(
                                        x >> 4,
                                        z >> 4,
                                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
                                        false);
        if (chunk == null || chunk instanceof net.minecraft.world.level.chunk.EmptyLevelChunk)
            return new Surface(Double.NaN, false);
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
        var pos = new BlockPos(x, top, z);
        var state = chunk.getBlockState(pos);
        boolean fluid = state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock;
        double height = top + 1.;
        if (collisionTop && !fluid) {
            var shape = state.getCollisionShape(level, pos);
            // A motion-blocking top without a collision box keeps the heightmap top: a loaded column is
            // never unknown, or the descent envelope would be lost over it.
            if (!shape.isEmpty()) height = top + shape.max(net.minecraft.core.Direction.Axis.Y);
        }
        return new Surface(height, fluid, state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA));
    }

    private HullLanding() {}
}
