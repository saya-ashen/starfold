package dev.starfold.waypoint;

import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.ship.HelmCommand;
import dev.starfold.ship.HelmServer;
import dev.starfold.ship.HullLanding;
import dev.starfold.ship.SableFrames;
import dev.starfold.sky.Space;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import org.joml.Vector3d;

/** Earned server observations only; vanilla advancement progress supplies persistence and deduplication. */
public final class ShipGuide {
    private static final java.util.Map<ServerPlayer, Flight> flights =
            new java.util.WeakHashMap<>();

    private static final class Flight {
        Object body;
        boolean grounded;
        int settled;
    }

    public static void initialize() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) -> {
                    if (!(event.getEntity() instanceof ServerPlayer player)) return;
                    if (!PlanetLevels.is(player.level())) {
                        flights.remove(player);
                        return;
                    }
                    award(player, "root");
                    if (net.neoforged.fml.ModList.get().isLoaded("sable")) flight(player);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
                                event) -> flights.remove(event.getEntity()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> flights.clear());
    }

    static boolean lifted(boolean grounded, boolean up, double clearance) {
        return grounded && up && Double.isFinite(clearance) && clearance > 2;
    }

    static int settled(int previous, double clearance, double speed) {
        return WaypointNavigation.grounded(clearance, speed) ? Math.min(20, previous + 1) : 0;
    }

    private static void flight(ServerPlayer player) {
        var body = SableFrames.aboard(player);
        if (body == null
                || !HelmServer.pilot(player, body)
                || !player.isAlive()
                || player.isSpectator()) {
            flights.remove(player);
            return;
        }
        var state = flights.computeIfAbsent(player, p -> new Flight());
        if (state.body != body) {
            state.body = body;
            state.grounded = false;
            state.settled = 0;
        }
        if (Space.in(player)) {
            state.settled = 0;
            if (done(player, "lift_off")) {
                award(player, "space");
                if (done(player, "other_planet")) award(player, "return_space");
            }
            return;
        }
        var level = player.serverLevel();
        var at = body.logicalPose().position();
        var access = PlanetAccess.of(level);
        if (!access.inChart(
                net.minecraft.core.BlockPos.containing(at.x, GroundCoordinates.FLOOR, at.z))) {
            state.settled = 0;
            return;
        }
        var box = HullLanding.bounds(body);
        double clearance =
                HullLanding.clearance(
                        box.minX(),
                        box.minY(),
                        box.minZ(),
                        box.maxX(),
                        box.maxZ(),
                        (x, z) -> HullLanding.surface(level, x, z));
        var pipeline =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)
                        .physicsSystem()
                        .getPipeline();
        double speed =
                pipeline.getLinearVelocity(body, new Vector3d()).length()
                        + pipeline.getAngularVelocity(body, new Vector3d()).length()
                                * Math.max(box.maxX() - box.minX(), box.maxZ() - box.minZ());
        state.settled = settled(state.settled, clearance, speed);
        int planet =
                access == PlanetAccess.SHARED
                        ? access.storagePlanet(at.x, at.z)
                        : PlanetSystem.planet(level);
        if (state.settled >= 20) {
            // The same grounded launch bookmark used by navigation; a parked hull alone is not
            // lift-off.
            if (WaypointSave.get(level).launches(player.getUUID()).containsKey(planet))
                state.grounded = true;
            if (done(player, "space") && planet != 0) award(player, "other_planet");
            if (done(player, "return_space") && planet == 0) award(player, "home");
        }
        if (lifted(state.grounded, (HelmServer.bits(body) & HelmCommand.UP) != 0, clearance))
            award(player, "lift_off");
    }

    private static net.minecraft.advancements.AdvancementHolder advancement(
            ServerPlayer player, String step) {
        return player.server
                .getAdvancements()
                .get(
                        ResourceLocation.parse(
                                "starfold:"
                                        + ((step.equals("root")
                                                        || step.equals("andesite")
                                                        || net.neoforged.fml.ModList.get()
                                                                        .isLoaded("create")
                                                                && net.neoforged.fml.ModList.get()
                                                                        .isLoaded("simulated"))
                                                ? "ship/"
                                                : "ship_without_workshop/")
                                        + step));
    }

    static boolean done(ServerPlayer player, String step) {
        var advancement = advancement(player, step);
        return advancement != null
                && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    static void award(ServerPlayer player, String step) {
        var advancement = advancement(player, step);
        if (advancement != null
                && !player.getAdvancements().getOrStartProgress(advancement).isDone())
            player.getAdvancements().award(advancement, "earned");
    }

    private ShipGuide() {}
}
