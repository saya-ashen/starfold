package dev.starfold.devtools;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import dev.starfold.api.FarViewDiagnostics;
import dev.starfold.render.GroundClient;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Opt-in, one-region client diagnostics. File IO occurs only on explicit snapshot/stop commands.
 */
final class FarCommands {
    private static FarCapture capture;

    static void register() {
        NeoForge.EVENT_BUS.addListener(FarCommands::commands);
        NeoForge.EVENT_BUS.addListener(FarCommands::tick);
    }

    private static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher()
                .register(
                        Commands.literal("sffar")
                                .executes(context -> status())
                                .then(Commands.literal("status").executes(context -> status()))
                                .then(
                                        Commands.literal("watch")
                                                .executes(context -> here(0))
                                                .then(
                                                        Commands.literal("here")
                                                                .executes(context -> here(0))
                                                                .then(
                                                                        Commands.argument(
                                                                                        "radius",
                                                                                        IntegerArgumentType
                                                                                                .integer(
                                                                                                        0,
                                                                                                        2))
                                                                                .executes(
                                                                                        context ->
                                                                                                here(
                                                                                                        IntegerArgumentType
                                                                                                                .getInteger(
                                                                                                                        context,
                                                                                                                        "radius")))))
                                                .then(
                                                        Commands.argument(
                                                                        "face",
                                                                        IntegerArgumentType.integer(
                                                                                0, 5))
                                                                .then(
                                                                        Commands.argument(
                                                                                        "x",
                                                                                        IntegerArgumentType
                                                                                                .integer(
                                                                                                        0,
                                                                                                        2047))
                                                                                .then(
                                                                                        Commands
                                                                                                .argument(
                                                                                                        "z",
                                                                                                        IntegerArgumentType
                                                                                                                .integer(
                                                                                                                        0,
                                                                                                                        2047))
                                                                                                .executes(
                                                                                                        context ->
                                                                                                                watch(
                                                                                                                        IntegerArgumentType
                                                                                                                                .getInteger(
                                                                                                                                        context,
                                                                                                                                        "face"),
                                                                                                                        IntegerArgumentType
                                                                                                                                .getInteger(
                                                                                                                                        context,
                                                                                                                                        "x"),
                                                                                                                        IntegerArgumentType
                                                                                                                                .getInteger(
                                                                                                                                        context,
                                                                                                                                        "z"),
                                                                                                                        0))
                                                                                                .then(
                                                                                                        Commands
                                                                                                                .argument(
                                                                                                                        "radius",
                                                                                                                        IntegerArgumentType
                                                                                                                                .integer(
                                                                                                                                        0,
                                                                                                                                        2))
                                                                                                                .executes(
                                                                                                                        context ->
                                                                                                                                watch(
                                                                                                                                        IntegerArgumentType
                                                                                                                                                .getInteger(
                                                                                                                                                        context,
                                                                                                                                                        "face"),
                                                                                                                                        IntegerArgumentType
                                                                                                                                                .getInteger(
                                                                                                                                                        context,
                                                                                                                                                        "x"),
                                                                                                                                        IntegerArgumentType
                                                                                                                                                .getInteger(
                                                                                                                                                        context,
                                                                                                                                                        "z"),
                                                                                                                                        IntegerArgumentType
                                                                                                                                                .getInteger(
                                                                                                                                                        context,
                                                                                                                                                        "radius"))))))))
                                .then(
                                        Commands.literal("here")
                                                .executes(context -> here(0))
                                                .then(
                                                        Commands.argument(
                                                                        "radius",
                                                                        IntegerArgumentType.integer(
                                                                                0, 2))
                                                                .executes(
                                                                        context ->
                                                                                here(
                                                                                        IntegerArgumentType
                                                                                                .getInteger(
                                                                                                        context,
                                                                                                        "radius")))))
                                .then(
                                        Commands.literal("snapshot")
                                                .executes(context -> export(false)))
                                .then(Commands.literal("stop").executes(context -> export(true))));
    }

    static int here(int radius) {
        if (!GroundClient.ready() || Minecraft.getInstance().level == null) {
            message("Observer metadata unavailable; cannot watch here.");
            return 0;
        }
        var eye = GroundClient.eye();
        return watch(
                GroundClient.face(), (int) Math.floor(eye.x()), (int) Math.floor(eye.z()), radius);
    }

    static int watch(int face, int x, int z, int radius) {
        if (!GroundClient.ready() || Minecraft.getInstance().level == null) {
            message("Observer metadata unavailable; no watch started.");
            return 0;
        }
        if (capture != null && !capture.stopped()) {
            message("Already watching; /sffar stop exports and releases this watch.");
            return 0;
        }
        try {
            var watch = FarViewDiagnostics.watch(GroundClient.planet(), face, x, z, radius);
            capture = new FarCapture(System.nanoTime());
            capture.accept(FarViewDiagnostics.snapshot());
            message(
                    "Watching planet="
                            + watch.planet()
                            + " face="
                            + watch.face()
                            + " tile="
                            + watch.x()
                            + ","
                            + watch.z()
                            + " radius="
                            + watch.radius()
                            + " tiles; max 2400 ticks/120s. /sffar snapshot or stop exports.");
            return 1;
        } catch (IllegalArgumentException exception) {
            message(exception.getMessage());
            return 0;
        }
    }

    private static void tick(ClientTickEvent.Post event) {
        if (capture == null || capture.stopped()) return;
        capture.ticks++;
        if (!FarViewDiagnostics.active()) {
            freeze("core watch stopped externally");
            return;
        }
        capture.accept(FarViewDiagnostics.snapshot());
        if (Minecraft.getInstance().level == null) freeze("client disconnected");
        else if (capture.expired(System.nanoTime())) freeze("bounded capture window ended");
    }

    private static void freeze(String reason) {
        capture.stoppedReason = reason;
        FarViewDiagnostics.stop();
        message(
                reason
                        + "; retained bounded evidence. /sffar snapshot or stop exports; no file"
                        + " written automatically.");
    }

    private static int status() {
        if (capture == null) {
            message("Off. /sffar watch [face x z radius] or /sffar here [radius].");
            return 1;
        }
        message(
                (capture.stopped() ? "Stopped: " + capture.stoppedReason : "Watching")
                        + "; ticks="
                        + capture.ticks
                        + "; states="
                        + capture.states().size()
                        + "; events="
                        + capture.events().size()
                        + "; missing="
                        + capture.missingEvents
                        + "; state="
                        + (capture.latest == null || capture.latest.region() == null
                                ? "UNAVAILABLE"
                                : "OBSERVED")
                        + "; geometry="
                        + (capture.latest == null || capture.latest.geometry().isEmpty()
                                ? "UNAVAILABLE"
                                : "OBSERVED_CPU"));
        return 1;
    }

    private static int export(boolean stop) {
        if (capture == null) {
            message("No capture. /sffar watch first.");
            return 0;
        }
        if (!capture.stopped()) {
            if (!FarViewDiagnostics.active()) freeze("core watch stopped externally");
            else capture.accept(FarViewDiagnostics.snapshot());
        }
        var snapshot = capture.latest;
        if (snapshot == null || snapshot.watch() == null) {
            message("Watch metadata unavailable.");
            return 0;
        }
        if (stop && !capture.stopped()) freeze("explicit stop");
        try {
            var w = snapshot.watch();
            var raw = FarRawWorld.capture(w.planet(), w.face(), w.x(), w.z());
            var report = FarReport.create(capture, raw, stop ? "stop" : "snapshot");
            var file = FarReport.write(Minecraft.getInstance().gameDirectory.toPath(), report);
            message(
                    "Exported "
                            + report.analysis().status()
                            + " "
                            + file
                            + "; findings="
                            + report.analysis().findings().size()
                            + "; "
                            + report.analysis().lifecycle());
            return 1;
        } catch (Exception exception) {
            Devtools.LOGGER.error("Far-view diagnostics export failed", exception);
            message(
                    "Export failed: "
                            + exception.getMessage()
                            + "; bounded evidence retained for retry.");
            return 0;
        }
    }

    static FarReport.Report liveSnapshot() {
        if (capture == null || capture.latest == null || capture.latest.watch() == null)
            throw new IllegalStateException("No far capture; call far_watch first.");
        if (!capture.stopped()) {
            if (!FarViewDiagnostics.active()) freeze("core watch stopped externally");
            else capture.accept(FarViewDiagnostics.snapshot());
        }
        var w = capture.latest.watch();
        return FarReport.create(
                capture, FarRawWorld.capture(w.planet(), w.face(), w.x(), w.z()), "live");
    }

    static void liveStop() {
        if (capture != null && !capture.stopped()) {
            if (FarViewDiagnostics.active()) capture.accept(FarViewDiagnostics.snapshot());
            freeze("live bridge stop");
        }
    }

    private static void message(String text) {
        Devtools.LOGGER.info("[sffar] {}", text);
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[sffar] " + text));
    }

    private FarCommands() {}
}
