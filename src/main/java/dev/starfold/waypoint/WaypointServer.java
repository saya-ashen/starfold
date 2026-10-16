package dev.starfold.waypoint;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.ground.GroundServer;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.ship.HullLanding;
import dev.starfold.ship.SableFrames;
import dev.starfold.ship.StarterShip;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;
import dev.starfold.space.SpaceServer;
import dev.starfold.waypoint.WaypointPackets.Target;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/** Player commands and a grounded helm launch bookmark; never changes position or drives thrust. */
public final class WaypointServer {
    public static void initialize() {
        StarfoldNetwork.playS2C(Target.TYPE, Target.CODEC);
        if (net.neoforged.fml.ModList.get().isLoaded("sable"))
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) -> {
                        if (event.getEntity() instanceof ServerPlayer player
                                && PlanetLevels.is(player.level())) launch(player);
                    });
    }

    private static final java.util.Map<ServerPlayer, Object> helms = new java.util.WeakHashMap<>();

    private static void launch(ServerPlayer player) {
        var body = SableFrames.aboard(player);
        if (body == null || !SpaceServer.atHelm(player, body)) {
            helms.remove(player);
            return;
        }
        if (helms.put(player, body) == body || Space.in(player)) return;
        var level = player.serverLevel();
        var at = body.logicalPose().position();
        var access = PlanetAccess.of(level);
        if (!access.inChart(net.minecraft.core.BlockPos.containing(at.x, at.y, at.z))) return;
        double clearance = HullLanding.ground(body).clearance();
        var container =
                (dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer)
                        dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        double speed =
                SableFrames.motion(container, body, new BentStripGeometry.Point(at.x, at.y, at.z))
                                .velocity()
                                .length()
                        * 20;
        if (!WaypointNavigation.grounded(clearance, speed)) return;
        try {
            int planet = access.storagePlanet(at.x, at.z);
            var pad = StarterShip.launchCentre(level, body);
            var marker =
                    new Waypoint(
                            Waypoint.LAUNCH,
                            planet,
                            access.body(
                                    pad != null
                                            ? pad
                                            : new BentStripGeometry.Point(at.x, at.y, at.z)));
            WaypointSave.get(level).launch(player.getUUID(), marker);
            send(player);
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE LAUNCH {} clearance {} speed {}", marker, clearance, speed);
        } catch (IllegalArgumentException | IllegalStateException error) {
            message(player, "message.starfold.mark.launch_failed", failure(error));
        }
    }

    public static void send(ServerPlayer player) {
        send(player, GroundFlightServer.session(player));
    }

    public static void send(ServerPlayer player, long session) {
        if (session == 0 || !player.connection.hasChannel(Target.TYPE)) return;
        try {
            var marker =
                    WaypointSave.get(player.serverLevel())
                            .active(player.getUUID(), markerPlanet(player));
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    player, new Target(session, marker));
        } catch (IllegalArgumentException | IllegalStateException error) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    player, new Target(session, null));
            message(player, "message.starfold.mark.read_failed", failure(error));
        }
    }

    private static int markerPlanet(ServerPlayer player) {
        if (Space.in(player)) return SpaceServer.markerPlanet(player);
        var access = PlanetAccess.of(player.serverLevel());
        var body = SableFrames.aboard(player);
        double x = body == null ? player.getX() : body.logicalPose().position().x,
                z = body == null ? player.getZ() : body.logicalPose().position().z;
        return access.storagePlanet(x, z);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> commands() {
        var root =
                Commands.literal("mark")
                        .then(
                                Commands.literal("list")
                                        .executes(
                                                c ->
                                                        run(
                                                                c.getSource()
                                                                        .getPlayerOrException(),
                                                                "list",
                                                                "")))
                        .then(
                                Commands.literal("clear")
                                        .executes(
                                                c ->
                                                        run(
                                                                c.getSource()
                                                                        .getPlayerOrException(),
                                                                "clear",
                                                                "")));
        for (String verb : new String[] {"add", "target", "remove"}) {
            root.then(
                    Commands.literal(verb)
                            .then(
                                    Commands.argument("name", StringArgumentType.string())
                                            .executes(
                                                    c ->
                                                            run(
                                                                    c.getSource()
                                                                            .getPlayerOrException(),
                                                                    verb,
                                                                    StringArgumentType.getString(
                                                                            c, "name")))));
        }
        return root;
    }

    private static int run(ServerPlayer player, String verb, String name) {
        if (Space.in(player)) return GroundServer.regionMessage(player);
        if (!PlanetLevels.is(player.level()) || GroundFlightServer.session(player) == 0) {
            message(player, "message.starfold.mark.enter");
            return 0;
        }
        try {
            var save = WaypointSave.get(player.serverLevel());
            var id = player.getUUID();
            switch (verb) {
                case "add" -> add(player, save, name);
                case "target" -> {
                    var marker = save.list(id).get(name);
                    if (marker == null)
                        throw new Waypoint.Failure("message.starfold.mark.missing", name);
                    if (GroundFlightServer.target(player, marker.planet()) == 0) return 0;
                    save.select(id, name);
                    message(player, "message.starfold.mark.target", name);
                }
                case "remove" -> {
                    save.remove(id, name);
                    message(player, "message.starfold.mark.removed", name);
                }
                case "clear" -> {
                    save.clear(id);
                    message(player, "message.starfold.mark.cleared");
                }
                case "list" -> list(player, save);
                default -> throw new IllegalArgumentException("Unknown marker command");
            }
            send(player);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            message(player, "message.starfold.mark.failed", failure(error));
            return 0;
        }
    }

    private static void add(ServerPlayer player, WaypointSave save, String name) {
        if (GroundFlightServer.controlled(player)) {
            throw new Waypoint.Failure("message.starfold.mark.ground_required");
        }
        var state = GroundFlightServer.capture(player, GroundClock.get(player.serverLevel()));
        save.add(player.getUUID(), new Waypoint(name, state.planet(), state.local()));
        message(player, "message.starfold.mark.saved", name, planetName(state.planet()));
    }

    private static void list(ServerPlayer player, WaypointSave save) {
        var points = save.list(player.getUUID());
        message(player, "message.starfold.mark.count", points.size(), WaypointSave.MAX_MARKERS);
        var active = save.active(player.getUUID(), markerPlanet(player));
        for (var launch : save.launches(player.getUUID()).values()) entry(player, launch);
        message(
                player,
                "message.starfold.mark.active",
                active == null
                        ? Component.translatable("message.starfold.mark.none")
                        : Component.translatable(
                                "message.starfold.mark.destination",
                                name(active),
                                planetName(active.planet())));
        for (var marker : points.values()) {
            entry(player, marker);
        }
    }

    private static String planetName(int planet) {
        return PlanetSystem.name(planet);
    }

    private static Component name(Waypoint marker) {
        return marker.name().equals(Waypoint.LAUNCH)
                ? Component.translatable("hud.starfold.launch_site")
                : Component.literal(marker.name());
    }

    private static void entry(ServerPlayer player, Waypoint marker) {
        var point = marker.pose().point();
        message(
                player,
                "message.starfold.mark.entry",
                name(marker),
                planetName(marker.planet()),
                Component.translatable(
                        "message.starfold.face."
                                + marker.pose().face().name().toLowerCase(Locale.ROOT)),
                String.format(Locale.ROOT, "%.1f, %.1f, %.1f", point.x(), point.y(), point.z()));
    }

    public static Component failure(RuntimeException error) {
        if (error instanceof Waypoint.Failure failure)
            return Component.translatable(failure.key, failure.arguments);
        org.slf4j.LoggerFactory.getLogger("starfold").warn("Navigation data error", error);
        return Component.translatable("message.starfold.data_error");
    }

    private static void message(ServerPlayer player, String key, Object... arguments) {
        player.sendSystemMessage(Component.translatable(key, arguments));
    }

    private WaypointServer() {}
}
