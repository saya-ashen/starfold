package dev.starfold.ship;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;

import net.minecraft.server.level.ServerLevel;

import org.joml.Quaterniond;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/** Read-only Sable observer. Never acquires tickets or changes a hull's pose. */
public final class ShipRecords {
    private static final Map<ServerLevel, ShipObserver> observers = new IdentityHashMap<>();

    private ShipRecords() {}

    /** Called after the system level and its celestial model have been initialized. */
    public static void attach(ServerLevel level) {
        if (!PlanetLevels.is(level) || observers.containsKey(level)) return;
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        var observer = new ShipObserver(ShipSave.get(level));
        observers.put(level, observer);
        container.addObserver(
                new SubLevelObserver() {
                    @Override
                    public void onSubLevelAdded(SubLevel hull) {
                        observer.added(hull.getUniqueId(), () -> sample(level, hull));
                    }

                    @Override
                    public void onSubLevelRemoved(SubLevel hull, SubLevelRemovalReason reason) {
                        observer.removed(
                                hull.getUniqueId(), reason == SubLevelRemovalReason.REMOVED);
                    }
                });
        // Startup loads may precede registration; only the currently live list is adopted.
        for (var hull : container.getAllSubLevels())
            observer.added(hull.getUniqueId(), () -> sample(level, hull));
    }

    public static void refresh(ServerLevel level) {
        var observer = observers.get(level);
        if (observer != null) observer.refresh();
    }

    /** Sample release before the region folds motion or changes its labels. */
    public static void binding(ServerLevel level, SubLevel hull) {
        var observer = observers.get(level);
        if (observer == null) return;
        var at = hull.logicalPose().position();
        boolean holding = Space.holds(at.x(), at.z()) && !piloted(level, hull);
        var held = observer.hold(hull.getUniqueId());
        if (holding && held != null) {
            var clock = GroundClock.get(level).clock;
            var pose = hull.logicalPose();
            var anchor = pose.rotationPoint();
            var target =
                    ShipParking.target(
                            clock.model,
                            held,
                            new Point(anchor.x(), anchor.y(), anchor.z()),
                            clock.tick());
            var frame = ShipHost.get().surroundingsFrame(level);
            if (!ShipParking.yields(
                    frame.universe(new Point(at.x(), at.y(), at.z()), clock.model, clock.tick()),
                    frame.universeOrientation(new Quaterniond(pose.orientation())),
                    target)) return;
            observer.bind(hull.getUniqueId(), true, null);
        } else if (holding == (held != null)) return;
        observer.serialized(hull.getUniqueId(), sample(level, hull));
    }

    public static ShipRecord.Place hold(ServerLevel level, SubLevel hull) {
        var observer = observers.get(level);
        return observer == null ? null : observer.hold(hull.getUniqueId());
    }

    public static boolean piloted(ServerLevel level, SubLevel hull) {
        return hull instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel server
                && HelmServer.hasPilot(server);
    }

    public static void clear() {
        observers.clear();
    }

    /** The serialized COM pose, not the older periodic sample or toData's metadata pose. */
    public static void serialized(
            dev.ryanhcode.sable.sublevel.ServerSubLevel hull,
            dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData data) {
        if (!(hull.getLevel() instanceof ServerLevel level)) return;
        var observer = observers.get(level);
        if (observer == null) return;
        var pose = serializedPose(data);
        observer.serialized(data.uuid(), sample(level, hull, pose));
    }

    public static void pointerSaved(
            ServerLevel level,
            dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData data,
            dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer pointer) {
        if (!observers.containsKey(level) || pointer == null) return;
        ShipSave.get(level)
                .pointerSaved(
                        data.uuid(),
                        new ShipRecord.Locator(
                                pointer.chunkPos().x,
                                pointer.chunkPos().z,
                                pointer.storageIndex(),
                                pointer.subLevelIndex()));
    }

    static dev.ryanhcode.sable.companion.math.Pose3d serializedPose(
            dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData data) {
        return dev.ryanhcode.sable.util.SableNBTUtils.readPose3d(
                data.fullTag().getCompound("pose"));
    }

    static ShipRecord.ChartPlace chartPlace(
            dev.ryanhcode.sable.companion.math.Pose3dc pose, long tick) {
        var at = pose.position();
        var anchor = pose.rotationPoint();
        return new ShipRecord.ChartPlace(
                new Point(at.x(), at.y(), at.z()),
                new Point(anchor.x(), anchor.y(), anchor.z()),
                new Quaterniond(pose.orientation()),
                tick);
    }

    private static ShipRecord.Place captureWhenSlow(
            ServerLevel level, SubLevel hull, ShipRecord.Place place) {
        if (!(hull instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel server)) return null;
        var container =
                dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer.getContainer(level);
        if (container == null) return null;
        var clock = GroundClock.get(level).clock;
        var at = hull.logicalPose().position();
        var motion =
                ShipHost.get()
                        .surroundingsFrame(level)
                        .universe(
                                SableFrames.motion(
                                        container, server, new Point(at.x(), at.y(), at.z())),
                                clock.model,
                                clock.tick());
        var carried = ShipHost.get().carriedSpin(level, hull.getUniqueId());
        motion =
                new Space.Motion(
                        motion.position(),
                        motion.orientation(),
                        motion.velocity(),
                        motion.spin().add(carried.x(), carried.y(), carried.z()));
        return ShipParking.slow(motion, ShipParking.evaluate(clock.model, place, clock.tick()))
                ? place
                : null;
    }

    private static ShipObserver.Sample sample(ServerLevel level, SubLevel hull) {
        var sample = sample(level, hull, hull.logicalPose());
        if (sample.state() instanceof ShipRecord.OnSlot slot)
            return new ShipObserver.Sample(
                    sample.dimension(), sample.name(), new ShipRecord.OnSlot(slot.slot()));
        return sample;
    }

    private static ShipObserver.Sample sample(
            ServerLevel level, SubLevel hull, dev.ryanhcode.sable.companion.math.Pose3dc pose) {
        var at = pose.position();
        ShipRecord.State state;
        if (Space.holds(at.x(), at.z())) {
            var clock = GroundClock.get(level).clock;
            var frame = ShipHost.get().surroundingsFrame(level);
            var position = new Point(at.x(), at.y(), at.z());
            var universe = frame.universe(position, clock.model, clock.tick());
            var anchor = pose.rotationPoint();
            var attitude = frame.universeOrientation(new Quaterniond(pose.orientation()));
            var observer = observers.get(level);
            var held =
                    observer == null
                            ? null
                            : observer.bind(
                                    hull.getUniqueId(),
                                    piloted(level, hull),
                                    () ->
                                            captureWhenSlow(
                                                    level,
                                                    hull,
                                                    ShipParking.capture(
                                                            clock.model,
                                                            ShipParking.nearestBody(
                                                                    clock.model,
                                                                    universe,
                                                                    clock.tick()),
                                                            new Point(
                                                                    anchor.x(),
                                                                    anchor.y(),
                                                                    anchor.z()),
                                                            universe,
                                                            attitude,
                                                            clock.tick())));
            var place =
                    held != null
                            ? held
                            : ShipParking.capture(
                                    clock.model,
                                    ShipParking.nearestBody(clock.model, universe, clock.tick()),
                                    new Point(anchor.x(), anchor.y(), anchor.z()),
                                    universe,
                                    attitude,
                                    clock.tick());
            state = new ShipRecord.InSpace(place);
        } else {
            var observer = observers.get(level);
            if (observer != null) observer.bind(hull.getUniqueId(), true, null);
            state =
                    new ShipRecord.OnSlot(
                            PlanetStorage.planet(at.x(), at.z()),
                            Optional.of(chartPlace(pose, GroundClock.get(level).clock.tick())));
        }
        return new ShipObserver.Sample(
                level.dimension().location(), Optional.ofNullable(hull.getName()), state);
    }
}
