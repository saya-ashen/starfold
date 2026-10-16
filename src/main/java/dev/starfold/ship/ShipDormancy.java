package dev.starfold.ship;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import dev.ryanhcode.sable.util.SableNBTUtils;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.*;

/** Space hulls resume from a body-relative place, not yesterday's level labels. */
public final class ShipDormancy {
    private static final Map<ServerLevel, Map<UUID, SubLevelData>> waking = new IdentityHashMap<>();
    private static final Map<ServerLevel, Set<UUID>> requested = new IdentityHashMap<>();
    private static final Map<ServerLevel, Set<UUID>> loading = new IdentityHashMap<>();
    private static final Map<ServerLevel, Map<UUID, ShipRecord.Place>> loaded =
            new IdentityHashMap<>();

    private static final Set<UUID> sleep = new HashSet<>();
    private static final Map<ServerLevel, Map<UUID, double[]>> measurements =
            new IdentityHashMap<>();

    private ShipDormancy() {}

    /** Probe requests unload; presence and dependencies still decide whether it is safe. */
    public static void requestSleep(UUID ship) {
        sleep.add(ship);
    }

    public static boolean managed(ServerLevel level, UUID ship) {
        if (!ShipHost.get().spaceDormancy() || !PlanetLevels.is(level)) return false;
        var record = ShipSave.get(level).find(ship);
        return record != null && record.state() instanceof ShipRecord.InSpace;
    }

    /** Unexpected snatch callers must wait for the current destination's columns too. */
    public static boolean defer(ServerLevel level, UUID ship) {
        if (!managed(level, ship) || loading.getOrDefault(level, Set.of()).contains(ship))
            return false;
        requested.computeIfAbsent(level, l -> new HashSet<>()).add(ship);
        return true;
    }

    public static GlobalSavedSubLevelPointer pointer(ShipRecord record) {
        var p = record.locator().orElseThrow();
        return new GlobalSavedSubLevelPointer(
                new ChunkPos(p.chunkX(), p.chunkZ()), p.storageIndex(), p.subLevelIndex());
    }

    public static SubLevelData read(ServerSubLevelContainer container, ShipRecord record) {
        var map = container.getHoldingChunkMap();
        var cached = map.getHoldingSubLevel(record.uuid());
        var data =
                cached != null
                        ? cached.data()
                        : record.locator().isEmpty()
                                ? null
                                : ((SableShipHoldingAccess) map).starfold$readShip(pointer(record));
        return data != null && data.uuid().equals(record.uuid()) ? data : null;
    }

    /** Recompute every tick: neither destination coordinates nor tickets survive a frame change. */
    static List<ShipLeases.Input> prepare(ServerLevel level, ServerSubLevelContainer container) {
        waking.remove(level);
        if (!ShipHost.get().spaceDormancy() || container == null) return List.of();
        var pending = new HashMap<UUID, SubLevelData>();
        var inputs = new ArrayList<ShipLeases.Input>();
        var clock = GroundClock.get(level).clock;
        for (var record : ShipSave.get(level).list()) {
            if (!(record.state() instanceof ShipRecord.InSpace space)
                    || record.locator().isEmpty()
                    || container.getSubLevel(record.uuid()) != null) continue;
            var at =
                    ShipParking.toLevel(
                            clock.model,
                            ShipHost.get().surroundingsFrame(level),
                            space.place(),
                            clock.tick());
            double wake = dev.ryanhcode.sable.SableConfig.SUB_LEVEL_TRACKING_RANGE.get() + 64;
            if (!requested.getOrDefault(level, Set.of()).contains(record.uuid())
                    && level.players().stream()
                            .noneMatch(
                                    p ->
                                            Space.in(p)
                                                    && p.position()
                                                                    .distanceToSqr(
                                                                            at.position().x(),
                                                                            at.position().y(),
                                                                            at.position().z())
                                                            <= wake * wake)) continue;
            var original = read(container, record);
            if (original == null || !original.dependencies().isEmpty()) continue;
            var data = repose(original, space.place(), at);
            var box = data.bounds();
            if (!fits(box)) continue;
            pending.put(record.uuid(), data);
            var columns = new HashSet<Long>();
            SableTickets.columns(
                    columns,
                    new net.minecraft.world.phys.AABB(
                                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
                            .inflate(32));
            inputs.add(
                    new ShipLeases.Input(
                            record.uuid(), columns, Set.of(ShipLeases.Reason.WAKING), Set.of()));
        }
        waking.put(level, pending);
        return inputs;
    }

    static void finish(ServerLevel level, ServerSubLevelContainer container) {
        if (!ShipHost.get().spaceDormancy() || container == null) return;
        for (var entry : waking.getOrDefault(level, Map.of()).entrySet()) {
            var columns = SableTickets.held(level, entry.getKey());
            if (columns.isEmpty()
                    || columns.stream()
                            .anyMatch(
                                    k ->
                                            !PhysicsChunkTicketManager.isChunkLoadedEnough(
                                                    level, ChunkPos.getX(k), ChunkPos.getZ(k))))
                continue;
            var record = ShipSave.get(level).find(entry.getKey());
            var active = loading.computeIfAbsent(level, l -> new HashSet<>());
            active.add(record.uuid());
            try {
                container.getHoldingChunkMap().snatchAndLoad(pointer(record), record.uuid());
            } finally {
                active.remove(record.uuid());
            }
            requested.getOrDefault(level, new HashSet<>()).remove(record.uuid());
        }
        for (var body : List.copyOf(container.getAllSubLevels())) {
            var at = body.logicalPose().position();
            if (body.isRemoved()
                    || !Space.holds(at.x, at.z)
                    || !sleep.contains(body.getUniqueId())
                    || !SableTickets.input(level, body).reasons().isEmpty()) continue;
            container
                    .getHoldingChunkMap()
                    .moveToUnloaded(
                            body,
                            new ChunkPos((int) Math.floor(at.x) >> 4, (int) Math.floor(at.z) >> 4));
        }
    }

    static boolean fits(BoundingBox3d box) {
        return Space.holds(box.minX, box.minZ)
                && Space.holds(box.maxX, box.maxZ)
                && box.minY >= Space.SABLE_MIN_Y
                && box.maxY <= Space.SABLE_MAX_Y;
    }

    /** Transform all eight saved world-box corners in one rigid delta; never rewrite the disk tag. */
    public static SubLevelData repose(
            SubLevelData original, ShipRecord.Place place, Space.Motion motion) {
        var old = ShipRecords.serializedPose(original);
        var pose = new Pose3d(old);
        var p = motion.position();
        var a = place.anchorPlot();
        pose.rotationPoint().set(a.x(), a.y(), a.z());
        pose.position().set(p.x(), p.y(), p.z());
        pose.orientation().set(motion.orientation());
        var box = original.bounds();
        var bounds = new BoundingBox3d();
        bounds.setUnchecked(
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY);
        for (int i = 0; i < 8; i++) {
            var corner =
                    new Vector3d(
                            (i & 1) == 0 ? box.minX : box.maxX,
                            (i & 2) == 0 ? box.minY : box.maxY,
                            (i & 4) == 0 ? box.minZ : box.maxZ);
            bounds.expandTo(pose.transformPosition(old.transformPositionInverse(corner)));
        }
        var tag = original.fullTag().copy();
        tag.put("pose", SableNBTUtils.writePose3d(pose));
        tag.put("world_bounds", SableNBTUtils.writeBoundingBox(bounds));
        tag.remove("linear_velocity");
        tag.remove("angular_velocity");
        var result = SubLevelSerializer.fromData(tag);
        result.setOriginLoadedChunk(original.getOriginLoadedChunk());
        return result;
    }

    /** This runs inside Sable's load call, before its physics or tracking tick can see the hull. */
    public static SubLevelData loadInput(ServerLevel level, SubLevelData original) {
        if (!managed(level, original.uuid())) return original;
        var data = waking.getOrDefault(level, Map.of()).get(original.uuid());
        if (data == null)
            throw new IllegalStateException("Space hull loaded without ready destination");
        return data;
    }

    public static void loaded(ServerLevel level, SubLevelData data, ServerSubLevel hull) {
        if (hull == null || !managed(level, data.uuid())) return;
        var place = ((ShipRecord.InSpace) ShipSave.get(level).find(data.uuid()).state()).place();
        var clock = GroundClock.get(level).clock;
        var motion =
                ShipParking.toLevel(
                        clock.model, ShipHost.get().surroundingsFrame(level), place, clock.tick());
        var container = ServerSubLevelContainer.getContainer(level);
        var pipeline = container.physicsSystem().getPipeline();
        pipeline.resetVelocity(hull);
        var v = motion.velocity();
        pipeline.addLinearAndAngularVelocity(
                hull, new Vector3d(v.x(), v.y(), v.z()).mul(20), motion.spin().mul(20));
        loaded.computeIfAbsent(level, l -> new HashMap<>()).put(data.uuid(), place);
        var error = errors(level, place, hull);
        measurements
                .computeIfAbsent(level, l -> new HashMap<>())
                .put(data.uuid(), new double[] {0, error[0], error[1]});
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "SHIP_WAKE_LOAD uuid={} tick={} pose={} expected={} rotation={} expected_rotation={} blocks={} degrees={}",
                        data.uuid(),
                        clock.tick(),
                        hull.logicalPose().position(),
                        motion.position(),
                        hull.logicalPose().orientation(),
                        motion.orientation(),
                        error[0],
                        error[1]);
    }

    public static ShipRecord.Place lastLoad(ServerLevel level, UUID ship) {
        return loaded.getOrDefault(level, Map.of()).get(ship);
    }

    public static double[] errors(
            ServerLevel level, ShipRecord.Place place, dev.ryanhcode.sable.sublevel.SubLevel hull) {
        var clock = GroundClock.get(level).clock;
        var a = place.anchorPlot();
        var at = hull.logicalPose().transformPosition(new Vector3d(a.x(), a.y(), a.z()));
        var now =
                ShipParking.fromLevel(
                        clock.model,
                        ShipHost.get().surroundingsFrame(level),
                        place.body(),
                        a,
                        new Point(at.x, at.y, at.z),
                        new Quaterniond(hull.logicalPose().orientation()),
                        clock.tick());
        return new double[] {
            now.position().sub(place.position()).length(),
            Math.toDegrees(
                    2 * Math.acos(Math.clamp(Math.abs(now.attitude().dot(place.attitude())), 0, 1)))
        };
    }

    /** Probe evidence at every post tick from materialization to the explicit readback. */
    public static void audit(ServerLevel level) {
        var container = ServerSubLevelContainer.getContainer(level);
        if (container == null) return;
        for (var entry : measurements.getOrDefault(level, Map.of()).entrySet()) {
            var hull = container.getSubLevel(entry.getKey());
            if (hull == null) continue;
            var error = errors(level, lastLoad(level, entry.getKey()), hull);
            var maximum = entry.getValue();
            maximum[0]++;
            maximum[1] = Math.max(maximum[1], error[0]);
            maximum[2] = Math.max(maximum[2], error[1]);
            if (maximum[0] <= 3 || maximum[0] % 50 == 0)
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "SHIP_WAKE_TICK uuid={} count={} blocks={} degrees={}",
                                entry.getKey(),
                                maximum[0],
                                error[0],
                                error[1]);
        }
    }

    public static double[] measured(ServerLevel level, UUID ship) {
        var value = measurements.getOrDefault(level, Map.of()).get(ship);
        return value == null
                ? new double[] {0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY}
                : value.clone();
    }

    /** Saved deck coordinates already exist for boarding; bring that observer to today's deck. */
    public static void crew(ServerPlayer player) {
        var tag = player.getPersistentData().getCompound(SableBoarding.KEY);
        if (!SableBoarding.valid(tag) || !managed(player.serverLevel(), tag.getUUID("body")))
            return;
        var level = player.serverLevel();
        var record = ShipSave.get(level).find(tag.getUUID("body"));
        var place = ((ShipRecord.InSpace) record.state()).place();
        var clock = GroundClock.get(level).clock;
        var motion = ShipParking.evaluate(clock.model, place, clock.tick());
        var frame =
                ShipHost.get()
                        .departureFrame(
                                level,
                                motion,
                                new Quaterniond(),
                                clock.model,
                                ShipHost.get().surroundingsFrame(level).planet(),
                                clock.tick());
        var at = frame.level(motion, clock.model, clock.tick());
        var a = place.anchorPlot();
        var pose =
                new Pose3d(
                        new Vector3d(at.position().x(), at.position().y(), at.position().z()),
                        at.orientation(),
                        new Vector3d(a.x(), a.y(), a.z()),
                        new Vector3d(1));
        var feet =
                pose.transformPosition(
                        new Vector3d(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")));
        requested.computeIfAbsent(level, l -> new HashSet<>()).add(record.uuid());
        player.teleportTo(feet.x, feet.y, feet.z);
    }

    public static void clear() {
        waking.clear();
        loading.clear();
        requested.clear();
        loaded.clear();
        sleep.clear();
        measurements.clear();
    }
}
