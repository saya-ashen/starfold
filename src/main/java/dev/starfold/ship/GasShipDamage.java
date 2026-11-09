package dev.starfold.ship;

import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.starfold.chart.GasOcclusion;
import dev.starfold.chart.GasPressure;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.sky.Space;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server-thread adapter, serviced once after SpaceServer's ordinary loaded-hull tick work. */
public final class GasShipDamage {
    static final int MAX_CHILDREN = 256;
    static final int FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    /** Empty geometry is UNKNOWN, not pressure zero. No native hull or level references retained. */
    public record Geometry(Point anchor, double padding) {}

    public record Sample(long tick, Optional<Geometry> geometry, GasPressure.Field field) {
        public Optional<GasPressure.Reading> ambient() {
            return geometry.flatMap(g -> field.worst(g.anchor(), g.padding()));
        }

        public Optional<GasPressure.Reading> selected(int bodyIndex) {
            return geometry.flatMap(g -> field.selected(bodyIndex, g.anchor(), g.padding()));
        }
    }

    private static final class Entry {
        GasDamageScan scan;
        BlockPos pending;
        Sample sample;
        long touched;
    }

    static final class TickGate {
        private long previous = -1;

        boolean enter(long tick) {
            if (tick < 0) throw new IllegalArgumentException("Gas damage service clock");
            if (tick <= previous) return false;
            previous = tick;
            return true;
        }
    }

    private static final class State {
        final TickGate gate = new TickGate();
        final GasDamageRoundRobin order = new GasDamageRoundRobin();
        final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();
        long watermark;
    }

    private static final Map<ServerLevel, State> states = new WeakHashMap<>();

    public static void release(ServerLevel level) {
        states.remove(level);
    }

    public static void clear() {
        states.clear();
    }

    public static void removed(ServerLevel level, UUID uuid) {
        var state = states.get(level);
        if (state != null) state.entries.remove(uuid);
        // Never erase the saved successful-write cooldown merely because Sable sleeps a hull.
    }

    public static Sample sample(ServerLevel level, UUID uuid) {
        var state = states.get(level);
        var entry = state == null ? null : state.entries.get(uuid);
        return entry == null ? null : entry.sample;
    }

    public static void step(
            ServerLevel level,
            ServerSubLevelContainer container,
            List<ServerSubLevel> bodies,
            GroundEphemeris.Model model,
            Space.Frame frame,
            double tick) {
        if (bodies.isEmpty() || !GasOcclusion.present(model)) {
            release(level);
            return;
        }
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Gas damage server thread");
        var state = states.computeIfAbsent(level, ignored -> new State());
        long now = level.getGameTime();
        if (!state.gate.enter(now)) return;
        var cohort = GasDamageRoundRobin.ordered(bodies, ServerSubLevel::getUniqueId);
        var field = GasPressure.snapshot(model, tick);
        state.watermark = Math.max(state.watermark, now);
        // At least one cohort position advances per tick, even when writes consume the global cap.
        long ttl = 200 + 2L * cohort.size();
        var old = state.entries.entrySet().iterator();
        for (int i = 0; i < GasDamageRoundRobin.MAX_HULLS && old.hasNext(); i++) {
            var entry = old.next();
            if (state.watermark - entry.getValue().touched <= ttl) break;
            old.remove();
        }
        var cooldown = GasDamageSave.get(level);
        var batch = state.order.begin(cohort.size());
        int lastWrite = -1;
        try {
            for (int visit = 0; visit < batch.size(); visit++) {
                var hull = cohort.get(batch.index(visit));
                if (hull.isRemoved()
                        || hull.getLevel() != level
                        || container.getSubLevel(hull.getUniqueId()) != hull) continue;
                var entry = state.entries.remove(hull.getUniqueId());
                if (entry == null) entry = new Entry();
                state.entries.put(hull.getUniqueId(), entry);
                entry.touched = state.watermark;
                var padding = padding(hull);
                var at = hull.logicalPose().position();
                Optional<Geometry> geometry =
                        padding.isEmpty()
                                ? Optional.empty()
                                : Optional.of(
                                        new Geometry(
                                                frame.universe(
                                                        new Point(at.x, at.y, at.z), model, tick),
                                                padding.getAsDouble()));
                entry.sample = new Sample(now, geometry, field);
                if (geometry.isEmpty()) continue;
                int interval = HullPressure.damageInterval(entry.sample.ambient().orElseThrow().pressure());
                if (interval == 0) {
                    entry.pending = null;
                    continue;
                }
                var domain = domain(level, hull);
                if (domain == null) continue;
                if (entry.scan == null) entry.scan = new GasDamageScan(domain);
                else entry.scan.reset(domain);
                if (scanAndRemove(
                        level, container, hull, entry, field, model, frame, tick, cooldown, now,
                        interval)) lastWrite = visit;
            }
        } finally {
            state.order.finish(batch, lastWrite);
        }
    }

    private static OptionalDouble padding(ServerSubLevel hull) {
        return padding(
                hull.logicalPose(),
                hull.getPlot().getBoundingBox(),
                hull.getPlot().getContraptions());
    }

    static OptionalDouble padding(
            Pose3dc hull, BoundingBox3ic plot, Collection<KinematicContraption> children) {
        if (children == null || children.size() > MAX_CHILDREN) return OptionalDouble.empty();
        try {
            double radius = GasHullGeometry.radius(hull, plot, null);
            var bounds = new BoundingBox3i();
            var pose = new Pose3d();
            int visited = 0;
            for (var child : children) {
                if (++visited > MAX_CHILDREN) return OptionalDouble.empty();
                if (!child.sable$isValid()) continue;
                child.sable$getLocalBounds(bounds);
                child.sable$getLocalPose(pose, 1);
                radius = Math.max(radius, GasHullGeometry.radius(hull, bounds, pose));
            }
            // Also encloses the conservative unit-block circumspheres used by damage admission.
            return OptionalDouble.of(radius + GasHullGeometry.blockPadding(hull));
        } catch (IllegalArgumentException badGeometry) {
            return OptionalDouble.empty();
        }
    }

    private static GasDamageScan.Domain domain(ServerLevel level, ServerSubLevel hull) {
        var box = hull.getPlot().getBoundingBox();
        if (box == null
                || box.maxX() < box.minX()
                || box.maxY() < box.minY()
                || box.maxZ() < box.minZ()) return null;
        int minY = Math.max(level.getMinSection(), box.minY() >> 4);
        int maxY = Math.min(level.getMaxSection() - 1, box.maxY() >> 4);
        var low = hull.getPlot().getChunkMin();
        var high = hull.getPlot().getChunkMax();
        int minX = Math.max(low.x, box.minX() >> 4), maxX = Math.min(high.x, box.maxX() >> 4);
        int minZ = Math.max(low.z, box.minZ() >> 4), maxZ = Math.min(high.z, box.maxZ() >> 4);
        return minX > maxX || minZ > maxZ || minY > maxY
                ? null
                : new GasDamageScan.Domain(minX, maxX, minZ, maxZ, minY, maxY);
    }

    private static boolean eligible(LevelChunk chunk, BlockPos pos) {
        return GasDamageAdmission.eligible(
                chunk.getBlockState(pos), chunk, pos, chunk.getBlockEntities().containsKey(pos));
    }

    /**
     * Sable destroys and marks removed a body whose self mass is no longer positive, and a removed
     * body cannot be sampled or saved until the container processes it. Pressure never empties one.
     */
    private static boolean leavesMass(
            ServerLevel level, ServerSubLevel hull, BlockPos pos, LevelChunk chunk) {
        return GasDamageAdmission.leavesMass(
                hull.getSelfMassTracker().getMass(),
                PhysicsBlockPropertyHelper.getMass(level, pos, chunk.getBlockState(pos)));
    }

    /** Sable records the source body when it splits off a piece; the piece waits out its cadence. */
    public static void split(ServerSubLevel piece, ServerSubLevel source) {
        if (piece == null
                || source == null
                || !(piece.getLevel() instanceof ServerLevel level)
                || !PlanetLevels.is(level)) return;
        GasDamageSave.get(level).inherit(piece.getUniqueId(), source.getUniqueId());
    }

    private static boolean exposed(
            ServerSubLevel hull,
            BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state,
            GasPressure.Field field,
            GroundEphemeris.Model model,
            Space.Frame frame,
            double tick) {
        var p =
                hull.logicalPose()
                        .transformPosition(
                                new org.joml.Vector3d(
                                        pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5));
        var universe = frame.universe(new Point(p.x, p.y, p.z), model, tick);
        return HullPressure.damaged(state,
                field.worst(universe, GasHullGeometry.blockPadding(hull.logicalPose()))
                        .orElseThrow().pressure());
    }

    private static boolean scanAndRemove(
            ServerLevel level,
            ServerSubLevelContainer container,
            ServerSubLevel hull,
            Entry entry,
            GasPressure.Field field,
            GroundEphemeris.Model model,
            Space.Frame frame,
            double tick,
            GasDamageSave cooldown,
            long now,
            int interval) {
        for (int visit = 0; visit < GasDamageScan.MAX_VISITS; visit++) {
            if (entry.pending == null) {
                var pos = entry.scan.position();
                var chunk = GasDamageAdmission.ownedChunk(level, hull, pos);
                if (chunk == null) {
                    entry.scan.nextChunk();
                    continue;
                }
                if (chunk.getSection(chunk.getSectionIndex(pos.getY())).hasOnlyAir()) {
                    entry.scan.nextSection();
                    continue;
                }
                entry.scan.nextCell();
                if (!eligible(chunk, pos) || !exposed(hull, pos, chunk.getBlockState(pos), field, model, frame, tick))
                    continue;
                entry.pending = pos;
            }
            if (!cooldown.ready(hull.getUniqueId(), now, interval)) return false;
            var pos = entry.pending;
            entry.pending = null;
            var chunk = GasDamageAdmission.checkedChunk(level, container, hull, pos);
            if (chunk == null
                    || !eligible(chunk, pos)
                    || !exposed(hull, pos, chunk.getBlockState(pos), field, model, frame, tick)
                    || !leavesMass(level, hull, pos, chunk)) continue;
            if (level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS)
                    && chunk.getBlockState(pos).isAir()) {
                cooldown.succeeded(hull.getUniqueId(), now, interval);
                return true;
            }
        }
        return false;
    }

    private GasShipDamage() {}
}
