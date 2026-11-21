package dev.starfold.waypoint;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.ship.HelmCommand;
import dev.starfold.ship.HelmServer;
import dev.starfold.ship.HullLanding;
import dev.starfold.ship.SableFrames;
import dev.starfold.ship.Thrusters;
import dev.starfold.sky.Space;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.joml.Vector3d;

/** Earned server observations only; vanilla advancement progress supplies persistence and deduplication. */
public final class ShipGuide {
    private static final java.util.Map<ServerPlayer, Flight> flights =
            new java.util.WeakHashMap<>();

    static final class Flight {
        Object body;
        int groundedPlanet = -1;
        int settled;
        boolean observed, outside;

        void aboard(Object next) {
            if (body == next) return;
            body = next;
            clearGround();
            observed = false;
        }

        void clearGround() {
            groundedPlanet = -1;
            settled = 0;
        }

        void ground(int planet, boolean pilot, double clearance, double speed) {
            boolean quiet = WaypointNavigation.grounded(clearance, speed);
            if (groundedPlanet != planet || !pilot && !quiet) clearGround();
            if (quiet) groundedPlanet = planet;
            settled = pilot ? ShipGuide.settled(settled, clearance, speed) : 0;
        }

        boolean lifted(int planet, boolean launch, boolean up, double clearance) {
            return ShipGuide.lifted(launch && groundedPlanet == planet, up, clearance);
        }
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

    static boolean crossed(boolean observed, boolean wasOutside, boolean outside, boolean powered) {
        return observed && !wasOutside && outside && powered;
    }

    /** Called only after the scoop has actually inserted fluid into this hull's tanks. */
    public static void skim(ServerSubLevel body) {
        for (var player : ((ServerLevel) body.getLevel()).players())
            if (player.isAlive() && !player.isSpectator() && SableFrames.aboard(player) == body)
                award(player, "skim");
    }

    private static void flight(ServerPlayer player) {
        var body = SableFrames.aboard(player);
        if (body == null || !player.isAlive() || player.isSpectator()) {
            flights.remove(player);
            return;
        }
        var state = flights.computeIfAbsent(player, p -> new Flight());
        state.aboard(body);
        boolean outside = Thrusters.outsideHome(body);
        if (crossed(
                state.observed,
                state.outside,
                outside,
                Thrusters.push(body, player.level().getGameTime()).length() > .5))
            award(player, "interstellar");
        state.outside = outside;
        state.observed = true;
        boolean pilot = HelmServer.pilot(player, body);
        if (Space.in(player)) {
            state.clearGround();
            if (pilot && done(player, "lift_off")) {
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
            state.clearGround();
            return;
        }
        var box = HullLanding.bounds(body);
        double clearance = HullLanding.ground(body).clearance();
        var pipeline =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)
                        .physicsSystem()
                        .getPipeline();
        double speed =
                pipeline.getLinearVelocity(body, new Vector3d()).length()
                        + pipeline.getAngularVelocity(body, new Vector3d()).length()
                                * Math.max(box.maxX() - box.minX(), box.maxZ() - box.minZ());
        int planet = access.storagePlanet(at.x, at.z);
        // Remember real quiet ground even before taking the helm; immediate lift-off must not
        // require another second parked at the controls. Arrival still needs 20 quiet pilot ticks.
        state.ground(planet, pilot, clearance, speed);
        if (!pilot) return;
        if (state.settled >= 20) {
            if (done(player, "space") && planet != 0) award(player, "other_planet");
            if (done(player, "return_space") && planet == 0) award(player, "home");
        }
        if (state.lifted(
                planet,
                WaypointSave.get(level).launches(player.getUUID()).containsKey(planet),
                (HelmServer.bits(body) & HelmCommand.UP) != 0,
                clearance)) award(player, "lift_off");
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
                                                        || step.equals("skim")
                                                        || step.equals("interstellar")
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
