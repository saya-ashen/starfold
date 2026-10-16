package dev.starfold.dev;

import dev.starfold.planet.GroundCoordinates;
import dev.starfold.render.GroundClient;
import dev.starfold.ship.Airstair;
import dev.starfold.ship.HelmClient;
import dev.starfold.ship.HelmInputClient;
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

    static void tick(Minecraft mc) {
        if (!chart || mc.player == null) return;
        var body = HelmClient.body();
        var state = body == null ? null : HelmInputClient.state(body.getUniqueId());
        // At altitude >= 200, UNKNOWN is the normal suppressed landing survey, not loss
        // of authority. LANDAID UNKNOWN during the low landing leg remains a hard failure.
        if (!lost && (state == null || !state.pilot())) {
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
        var state = body == null ? null : HelmInputClient.state(body.getUniqueId());
        boolean pilot = state != null && state.pilot();
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
