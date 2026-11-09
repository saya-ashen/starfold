package dev.starfold.probes;

import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ship.*;
import dev.starfold.sky.*;
import dev.starfold.space.*;
import dev.starfold.probe.ArrivalProbe;

public final class SpaceFixtures {
    public static void initialize() {
        if (ArrivalProbe.ENABLED)
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
                            event.getDispatcher()
                                    .register(
                                            net.minecraft.commands.Commands.literal("arrivalprobe")
                                                    .requires(s -> s.hasPermission(2))
                                                    .then(
                                                            net.minecraft.commands.Commands
                                                                    .argument(
                                                                            "planet",
                                                                            com.mojang.brigadier
                                                                                    .arguments
                                                                                    .IntegerArgumentType
                                                                                    .integer(0))
                                                                    .then(
                                                                            net.minecraft.commands
                                                                                    .Commands
                                                                                    .argument(
                                                                                            "face",
                                                                                            com
                                                                                                    .mojang
                                                                                                    .brigadier
                                                                                                    .arguments
                                                                                                    .IntegerArgumentType
                                                                                                    .integer(
                                                                                                            0,
                                                                                                            5))
                                                                                    .executes(
                                                                                            c -> {
                                                                                                probeArrival(
                                                                                                        c.getSource()
                                                                                                                .getPlayerOrException(),
                                                                                                        com
                                                                                                                .mojang
                                                                                                                .brigadier
                                                                                                                .arguments
                                                                                                                .IntegerArgumentType
                                                                                                                .getInteger(
                                                                                                                        c,
                                                                                                                        "planet"),
                                                                                                        com
                                                                                                                .mojang
                                                                                                                .brigadier
                                                                                                                .arguments
                                                                                                                .IntegerArgumentType
                                                                                                                .getInteger(
                                                                                                                        c,
                                                                                                                        "face"));
                                                                                                return 1;
                                                                                            })))));
    }

    /** Development fixture: relocate only the occupied region's far-away reference, never load a chart. */
    public static void probeArrival(
            net.minecraft.server.level.ServerPlayer player, int planet, int face) {
        if (!ArrivalProbe.ENABLED || !Space.in(player))
            throw new IllegalStateException("Arrival fixture requires region pilot");
        var level = player.serverLevel();
        var body = SableFrames.aboard(player);
        if (body == null) throw new IllegalStateException("No pilot body");
        var clock = GroundClock.get(level).clock;
        var shape = PlanetShape.of(planet);
        var target = clock.model.at(planet, clock.tick() + 500);
        var offset =
                target.vector(
                                shape.direction(
                                        CubeSurfaceTopology.Face.VALUES[face],
                                        face == 1 ? 1700 : shape.size() / 2.0,
                                        face == 1 ? 900 : shape.size() / 2.0))
                        .scale(shape.radius() + 7500);
        var at = body.logicalPose().position();
        var old = SpaceServer.frame(level);
        SpaceServer.frame(level,
                new Space.Frame(
                        old.rotation(),
                        old.shift(),
                        planet,
                        offset.sub(
                                old.universeVector(new Point(at.x, at.y, at.z).sub(old.shift()))),
                        new Point(0, 0, 0),
                        clock.tick()), true);
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE ARRIVAL_SETUP planet {} face {} height 7500 game {}",
                        planet,
                        face,
                        level.getGameTime());
    }

    public static void probeSpeed(net.minecraft.server.level.ServerPlayer player, double speed) {
        if (!Boolean.getBoolean("starfold.helmProbe")
                || !Space.in(player)
                || !HelmServer.active(player))
            throw new IllegalStateException("Speed fixture requires development region pilot");
        var level = player.serverLevel();
        var f = SpaceServer.frame(level).rebased(GroundClock.get(level).clock.tick());
        var nose = HelmServer.forward(SableFrames.aboard(player));
        SpaceServer.frame(level,
                new Space.Frame(
                        f.rotation(),
                        f.shift(),
                        f.planet(),
                        f.offset(),
                        f.universeVector(nose).scale(speed / 20),
                        f.epoch()), false);
    }

    /** End chart ownership without changing position or velocity, then announce passive coordinates. */
    private SpaceFixtures() {}
}
