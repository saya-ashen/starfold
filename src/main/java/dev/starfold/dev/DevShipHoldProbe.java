package dev.starfold.dev;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ship.SableFrames;
import dev.starfold.ship.ShipDormancy;
import dev.starfold.ship.ShipHost;
import dev.starfold.ship.ShipParking;
import dev.starfold.ship.ShipRecord;
import dev.starfold.ship.ShipRecords;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;

import net.minecraft.server.level.ServerLevel;

import org.joml.Quaterniond;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Opt-in server-tick windows, independent of client waits or rendering speed. */
public final class DevShipHoldProbe {
    private record Window(ShipRecord.Place place, long start, int readings, boolean passed) {}

    private static final Map<ServerLevel, Map<UUID, Window>> windows = new IdentityHashMap<>();
    private static final Set<UUID> pilots = new HashSet<>();

    private record Release(ShipRecord.Place place, long start) {}

    private static final Map<UUID, Release> releases = new HashMap<>();

    private DevShipHoldProbe() {}

    public static void tick(ServerLevel level) {
        var container = ServerSubLevelContainer.getContainer(level);
        if (container == null) return;
        var current = windows.computeIfAbsent(level, l -> new HashMap<>());
        current.keySet().removeIf(id -> container.getSubLevel(id) == null);
        for (var hull : container.getAllSubLevels()) {
            var id = hull.getUniqueId();
            var held = ShipRecords.hold(level, hull);
            if (ShipRecords.piloted(level, hull)) {
                if (held != null) throw new IllegalStateException("Pilot has a hold");
                current.remove(id);
                releases.remove(id);
                var at = hull.logicalPose().position();
                if (Space.holds(at.x(), at.z()) && pilots.add(id))
                    org.slf4j.LoggerFactory.getLogger("starfold-probe")
                            .info("SHIP_HOLD_PILOT uuid={} held=false", id);
                continue;
            }
            pilots.remove(id);
            coast(level, hull);
            if (held == null) {
                current.remove(id);
                continue;
            }
            var window = current.get(id);
            if (window == null || window.place() != held) {
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "SHIP_HOLD_BEGIN uuid={} game={} release={} recaptured={}",
                                id,
                                level.getGameTime(),
                                held.tick(),
                                window != null);
                window = new Window(held, level.getGameTime(), 0, true);
            }
            long elapsed = level.getGameTime() - window.start();
            if (elapsed == 100 || elapsed == 400 || elapsed == 1200) {
                var error = ShipDormancy.errors(level, held, hull);
                boolean passed = error[0] < .1 && error[1] < .1;
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "SHIP_HOLD_WINDOW uuid={} elapsed={} game={} celestial={} release={} body={} wake={} blocks={} degrees={} pass={}",
                                id,
                                elapsed,
                                level.getGameTime(),
                                GroundClock.get(level).clock.tick(),
                                held.tick(),
                                held.body(),
                                ShipDormancy.lastLoad(level, id) != null,
                                error[0],
                                error[1],
                                passed);
                window =
                        new Window(
                                held,
                                window.start(),
                                window.readings() + 1,
                                window.passed() && passed);
            }
            current.put(id, window);
        }
    }

    private static void coast(ServerLevel level, dev.ryanhcode.sable.sublevel.ServerSubLevel hull) {
        var at = hull.logicalPose().position();
        if (!Space.holds(at.x(), at.z())) return;
        var clock = GroundClock.get(level).clock;
        var frame = ShipHost.get().surroundingsFrame(level);
        var point = new Point(at.x(), at.y(), at.z());
        var universe = frame.universe(point, clock.model, clock.tick());
        int body = ShipParking.nearestBody(clock.model, universe, clock.tick());
        var anchor = hull.logicalPose().rotationPoint();
        var release =
                releases.computeIfAbsent(
                        hull.getUniqueId(),
                        id ->
                                new Release(
                                        ShipParking.fromLevel(
                                                clock.model,
                                                frame,
                                                body,
                                                new Point(anchor.x(), anchor.y(), anchor.z()),
                                                point,
                                                new Quaterniond(hull.logicalPose().orientation()),
                                                clock.tick()),
                                        level.getGameTime()));
        long elapsed = level.getGameTime() - release.start();
        if (elapsed != 0
                && elapsed != 23
                && elapsed != 428
                && elapsed != 833
                && elapsed != 1238
                && elapsed != 1643
                && elapsed != 2048
                && elapsed != 2453) return;
        var motion =
                frame.universe(
                        SableFrames.motion(
                                ServerSubLevelContainer.getContainer(level), hull, point),
                        clock.model,
                        clock.tick());
        var carried = ShipHost.get().carriedSpin(level, hull.getUniqueId());
        var target = ShipParking.coastTarget(clock.model, body, universe, clock.tick());
        double speed = motion.velocity().sub(target.velocity()).length();
        double spin =
                motion.spin()
                        .add(carried.x(), carried.y(), carried.z())
                        .sub(target.spin())
                        .length();
        var error = ShipDormancy.errors(level, release.place(), hull);
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "SHIP_COAST uuid={} elapsed={} celestial={} body={} blocks={} degrees={} speed={} spin={} held={}",
                        hull.getUniqueId(),
                        elapsed,
                        clock.tick(),
                        body,
                        error[0],
                        error[1],
                        speed,
                        spin,
                        ShipRecords.hold(level, hull) != null);
    }

    public static ShipRecord.Place release(UUID ship) {
        var release = releases.get(ship);
        return release == null ? null : release.place();
    }

    public static boolean passed(ServerLevel level, UUID ship) {
        var window = windows.getOrDefault(level, Map.of()).get(ship);
        return window != null && window.readings() == 3 && window.passed();
    }

    public static void clear() {
        windows.clear();
        pilots.clear();
        releases.clear();
    }
}
