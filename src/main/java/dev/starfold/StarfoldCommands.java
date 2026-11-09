package dev.starfold;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.waypoint.WaypointServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Server command tree; permissions and argument ranges live beside their actions. */
final class StarfoldCommands {
    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(coreCommands().then(navigationCommands()).then(faceCommands()).then(spinCommands()));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
                    net.minecraft.commands.CommandSourceStack>
            coreCommands() {
        return Commands.literal("starfold")
                // Going to the planets, steering and marking are everyone's;
                // the rest is for setting a world up and looking into it.
                .then(
                        Commands.literal("enter")
                                .requires(s -> s.hasPermission(2))
                                .executes(
                                        c ->
                                                PlanetPlayers.enter(
                                                        c.getSource().getPlayerOrException())))
                .then(
                        Commands.literal("terrain")
                                .executes(
                                        c ->
                                                PlanetPlayers.enter(
                                                        c.getSource().getPlayerOrException(),
                                                        PlanetLevels.PLANET)))
                .then(
                        Commands.literal("leave")
                                .executes(
                                        c ->
                                                PlanetPlayers.leave(
                                                        c.getSource().getPlayerOrException())))
                .then(WaypointServer.commands())
                .then(
                        Commands.literal("status")
                                .executes(
                                        c ->
                                                PlanetPlayers.status(
                                                        c.getSource().getPlayerOrException())))
                .then(
                        Commands.literal("universe")
                                .executes(
                                        c ->
                                                PlanetPlayers.universe(
                                                        c.getSource().getPlayerOrException())))
                .then(
                        Commands.literal("orbit")
                                .requires(s -> s.hasPermission(2))
                                .executes(
                                        c ->
                                                GroundFlightServer.orbit(
                                                        c.getSource().getPlayerOrException())));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
                    net.minecraft.commands.CommandSourceStack>
            navigationCommands() {
        return Commands.literal("nav")
                .then(
                        Commands.literal("target")
                                .then(
                                        Commands.literal("a")
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.target(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        0)))
                                .then(
                                        Commands.literal("b")
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.target(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        1)))
                                .then(
                                        Commands.argument(
                                                        "planet",
                                                        IntegerArgumentType.integer(
                                                                0, PlanetStorage.MAX_PLANETS - 1))
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.target(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        IntegerArgumentType
                                                                                .getInteger(
                                                                                        c,
                                                                                        "planet")))))
                .then(
                        Commands.literal("brake")
                                .then(
                                        Commands.literal("on")
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.guidance(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        0,
                                                                        true)))
                                .then(
                                        Commands.literal("off")
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.guidance(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        0,
                                                                        false))))
                .then(
                        Commands.literal("land")
                                .then(
                                        Commands.literal("on")
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.guidance(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        1,
                                                                        true)))
                                .then(
                                        Commands.literal("off")
                                                .executes(
                                                        c ->
                                                                GroundFlightServer.guidance(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        1,
                                                                        false))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
                    net.minecraft.commands.CommandSourceStack>
            faceCommands() {
        return Commands.literal("face")
                .requires(s -> s.hasPermission(2))
                .then(
                        Commands.argument("id", IntegerArgumentType.integer(0, 5))
                                .executes(
                                        c ->
                                                PlanetPlayers.face(
                                                        c.getSource().getPlayerOrException(),
                                                        IntegerArgumentType.getInteger(c, "id"))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
                    net.minecraft.commands.CommandSourceStack>
            spinCommands() {
        return Commands.literal("spin")
                .requires(s -> s.hasPermission(2))
                .then(
                        Commands.literal("on")
                                .executes(
                                        c ->
                                                PlanetPlayers.spin(
                                                        c.getSource().getPlayerOrException(),
                                                        true)))
                .then(
                        Commands.literal("off")
                                .executes(
                                        c ->
                                                PlanetPlayers.spin(
                                                        c.getSource().getPlayerOrException(),
                                                        false)));
    }
    private StarfoldCommands() {}
}
