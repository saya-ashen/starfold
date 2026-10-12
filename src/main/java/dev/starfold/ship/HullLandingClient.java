package dev.starfold.ship;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.planet.GroundCoordinates;

import net.minecraft.client.Minecraft;

import org.joml.Vector3d;

/** Chart measurements only. Rendering belongs to snapshot-driven flight widgets. */
public final class HullLandingClient {
    private static Object body, level;
    private static ObserverFrame frame;
    private static Vector3d previous;
    private static double vertical, horizontal;
    private static Point motion = GroundEphemeris.ZERO;

    public static boolean active() {
        if (!ShipClientHost.get().chartActive() || ShipClientHost.get().movingSurroundings())
            return false;
        var hull = HelmClient.body();
        if (hull == null) return false;
        var at = hull.logicalPose().position();
        var storage = ShipClientHost.get().storage(at.x, GroundCoordinates.FLOOR, at.z);
        return ShipClientHost.get()
                .chartAccess()
                .inChart(
                        net.minecraft.core.BlockPos.containing(
                                storage.x(), storage.y(), storage.z()));
    }

    public static void tick() {
        var mc = Minecraft.getInstance();
        if (!active()) {
            body = null;
            level = null;
            frame = null;
            previous = null;
            vertical = horizontal = 0;
            motion = GroundEphemeris.ZERO;
            return;
        }
        var hull = HelmClient.body();
        var at = hull.logicalPose().position();
        var now = ShipClientHost.get().chartFrame();
        if (body == hull && level == mc.level && now.equals(frame) && previous != null) {
            vertical = (at.y - previous.y) * 20;
            motion = new Point((at.x - previous.x) * 20, 0, (at.z - previous.z) * 20);
            horizontal = motion.length();
        } else {
            vertical = horizontal = 0;
            motion = GroundEphemeris.ZERO;
        }
        body = hull;
        level = mc.level;
        frame = now;
        previous = new Vector3d(at);
    }

    public static HullLanding.Ground ground() {
        var mc = Minecraft.getInstance();
        var hull = HelmClient.body();
        if (!active()) return new HullLanding.Ground(Double.NaN, false);
        var box = HullLanding.bounds(hull);
        return HullLanding.ground(
                box.minX(),
                box.minY(),
                box.minZ(),
                box.maxX(),
                box.maxZ(),
                (x, z) -> HullLanding.column(mc.level, x, z));
    }

    public static HullLanding.Site site() {
        var hull = HelmClient.body();
        var state = hull == null ? null : HelmInputClient.state(hull.getUniqueId());
        return state != null && state.pilot() ? state.landing() : HullLanding.Site.UNKNOWN;
    }

    public static double clearance() {
        return ground().clearance();
    }

    public static double vertical() {
        return vertical;
    }

    public static double horizontal() {
        return horizontal;
    }

    public static Point motion() {
        return motion;
    }

    private HullLandingClient() {}
}
