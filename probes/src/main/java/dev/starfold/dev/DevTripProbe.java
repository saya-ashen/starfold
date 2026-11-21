package dev.starfold.dev;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.render.GroundClient;
import dev.starfold.ship.Airstair;
import dev.starfold.ship.HelmClient;
import dev.starfold.ship.HullLanding;
import dev.starfold.ship.HullLandingClient;
import dev.starfold.sky.Space;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceNavigationClient;

import net.minecraft.client.Minecraft;

/** Read-only trip evidence, separate from the personal-flight nav reading. */
final class DevTripProbe {
    private static boolean chart, lost;
    private static int resting;

    /** Margin of the entire hull in the current surface chart, including rotation. */
    static double faceInset() {
        var body = HelmClient.body();
        if (body == null || SpaceClient.active() || !GroundClient.ready()) return Double.NaN;
        var frame = GroundClient.frame();
        var origin = faceOrigin();
        var box = HullLanding.bounds(body);
        var a = GroundClient.storage(box.minX(), box.minY(), box.minZ()).sub(origin);
        var b = GroundClient.storage(box.maxX(), box.maxY(), box.maxZ()).sub(origin);
        int size = PlanetShape.of(frame.planet()).size();
        return Math.min(Math.min(Math.min(a.x(), b.x()), Math.min(a.z(), b.z())),
                size - Math.max(Math.max(a.x(), b.x()), Math.max(a.z(), b.z())));
    }

    static double centerHeading() {
        var body = HelmClient.body();
        if (body == null || SpaceClient.active() || !GroundClient.ready())
            throw new IllegalStateException("facecenter requires a piloted surface hull");
        var frame = GroundClient.frame();
        double half = PlanetShape.of(frame.planet()).half();
        var center = frame.toClient(faceOrigin().add(new Point(half, 0, half)));
        var at = body.logicalPose().position();
        return Math.toDegrees(Math.atan2(center.z() - at.z, at.x - center.x()));
    }

    static Point chartImage(double x, double z) {
        var frame = GroundClient.frame();
        var origin = PlanetStorage.origin(frame.planet());
        int face = GroundCoordinates.face(x - origin.x());
        var targetOrigin = origin.add(GroundCoordinates.origin(face));
        var chart = new dev.starfold.chart.PlanetChart(
                PlanetShape.of(frame.planet()), dev.starfold.geometry.CubeSurfaceTopology.Face.VALUES[frame.root()]);
        var tile = chart.tile(dev.starfold.geometry.CubeSurfaceTopology.Face.VALUES[face]);
        if (tile == null) throw new IllegalStateException("Target is outside the current and adjacent charts");
        return frame.toClient(faceOrigin().add(tile.point(new Point(x - targetOrigin.x(), 0, z - targetOrigin.z()))));
    }

    static Point storageHull() {
        var body = HelmClient.body();
        if (body == null || SpaceClient.active() || !GroundClient.ready()) return null;
        var at = body.logicalPose().position();
        return GroundClient.storage(at.x(), at.y(), at.z());
    }

    private static Point faceOrigin() {
        var frame = GroundClient.frame();
        return PlanetStorage.origin(frame.planet()).add(GroundCoordinates.origin(frame.root()));
    }

    static int restingTicks() {
        return resting;
    }

    static void tick(Minecraft mc) {
        if (!chart || mc.player == null) return;
        var body = HelmClient.body();
        // body() follows authoritative pilot identity until release/disconnect. Command
        // state has a short thrust lease and may expire while the client catches up.
        // At altitude >= 200, UNKNOWN is the normal suppressed landing survey, not loss
        // of authority. A sustained unknown landing survey is separately visible in landing telemetry.
        if (!lost && body == null) {
            lost = true;
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE TRIP HELM LOST tick {} site {}",
                            mc.level.getGameTime(),
                            HullLandingClient.site());
        }
        double clearance = HullLandingClient.clearance();
        resting =
                Double.isFinite(clearance)
                                && clearance <= dev.starfold.ship.LiftControl.CONTACT
                                && Math.abs(HullLandingClient.vertical()) < .3
                                && HullLandingClient.horizontal() < .3
                        ? resting + 1
                        : 0;
    }

    static void snapshot(Minecraft mc, String label) {
        if (!label.startsWith("s-")) return;
        if (label.equals("s-chart")) chart = true;
        var body = HelmClient.body();
        boolean pilot = body != null;
        boolean stair =
                body != null
                        && body.getPlot().getLoadedChunks().stream()
                                .flatMap(c -> c.getChunk().getBlockEntities().values().stream())
                                .anyMatch(
                                        be ->
                                                be instanceof Airstair.Stair s
                                                        && s.length > 0
                                                        && s.visual >= 1);
        var frame = SpaceClient.frame();
        int loaded = 0, chunks = 0;
        if (body != null && HullLandingClient.active()) {
            var box = HullLanding.bounds(body);
            for (int x = (int) Math.floor(box.minX()) >> 4;
                    x <= (int) Math.floor(Math.nextDown(box.maxX())) >> 4;
                    x++)
                for (int z = (int) Math.floor(box.minZ()) >> 4;
                        z <= (int) Math.floor(Math.nextDown(box.maxZ())) >> 4;
                        z++) {
                    chunks++;
                    if (mc.level.getChunkSource().hasChunk(x, z)) loaded++;
                }
        }
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE TRIP {} tick {} sky {} partial {} space {} phase {} locked {} helm {} chart {} planet {} height {} chunks {}/{} clearance {} vertical {} horizontal {} stair {} resting {} drift {} orientation {} site {}",
                        label,
                        mc.level.getGameTime(),
                        SpaceClient.time(),
                        mc.getTimer().getGameTimeDeltaPartialTick(false),
                        SpaceClient.active(),
                        SpaceClient.phase(),
                        SpaceNavigationClient.locked(),
                        pilot,
                        !SpaceClient.active() && !Space.holds(mc.player.getX(), mc.player.getZ()),
                        GroundClient.frame().planet(),
                        body == null || SpaceClient.active()
                                ? Double.NaN
                                : body.logicalPose().position().y() - GroundCoordinates.FLOOR,
                        loaded,
                        chunks,
                        HullLandingClient.clearance(),
                        HullLandingClient.vertical(),
                        HullLandingClient.horizontal(),
                        stair,
                        resting,
                        frame == null ? "none" : frame.drift(),
                        body == null ? "none" : body.logicalPose().orientation(),
                        HullLandingClient.site());
    }

    private DevTripProbe() {}
}
