package dev.starfold.dev;

import com.google.gson.GsonBuilder;

import dev.starfold.api.FarViewDiagnostics;
import dev.starfold.render.GroundClient;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit opt-in consumer of core diagnostics; has no devtools dependency. */
final class DevFarTraceProbe {
    private static FarTraceSession session;
    private static long deadline;

    static boolean active() {
        return session != null;
    }

    static boolean command(Minecraft mc, String arg) {
        if (!Boolean.getBoolean("starfold.farTrace"))
            throw new IllegalStateException("fartrace requires -Dstarfold.farTrace=true");
        String[] words = arg.trim().split("\\s+");
        switch (words[0]) {
            case "watch" -> {
                if (words.length != 5)
                    throw new IllegalArgumentException("fartrace watch face x z radius");
                if (!GroundClient.ready() || mc.player == null || mc.level == null)
                    throw new IllegalStateException(
                            "fartrace watch requires a ready ground observer");
                stop(mc, "replaced");
                var watch =
                        FarViewDiagnostics.watch(
                                GroundClient.planet(),
                                Integer.parseInt(words[1]),
                                Integer.parseInt(words[2]),
                                Integer.parseInt(words[3]),
                                Integer.parseInt(words[4]));
                session = new FarTraceSession(watch);
                deadline = System.nanoTime() + 180_000_000_000L;
                session.collect(FarViewDiagnostics.snapshot(), pose(mc));
            }
            case "mark" -> {
                requireSession();
                session.mark(arg.trim().substring("mark".length()).trim());
            }
            case "check" -> {
                if (words.length != 1) throw new IllegalArgumentException("fartrace check");
                requireSession();
                session.collect(FarViewDiagnostics.snapshot(), pose(mc));
                var result = session.check();
                export(mc, "check");
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info("PROBE FARTRACE {} {}", result.status(), result.assertions());
                if (!result.status().equals("PASS")) {
                    session.incomplete("check did not establish complete route coverage");
                    ProbeSession.fail(mc, "fartrace " + result.status());
                    return false;
                }
            }
            case "export" -> {
                if (words.length != 1) throw new IllegalArgumentException("fartrace export");
                requireSession();
                session.collect(FarViewDiagnostics.snapshot(), pose(mc));
                export(mc, "export");
            }
            case "stop" -> {
                if (words.length != 1) throw new IllegalArgumentException("fartrace stop");
                requireSession();
                stop(mc, "stopped");
            }
            default -> throw new IllegalArgumentException("fartrace watch|mark|check|stop|export");
        }
        return true;
    }

    static boolean tick(Minecraft mc) {
        if (session == null) return false;
        if (mc.level == null || mc.player == null) {
            session.incomplete("world/player unavailable");
            ProbeSession.fail(mc, "fartrace world/player unavailable");
            return true;
        }
        boolean finished = session.tick(FarViewDiagnostics.snapshot(), pose(mc));
        if (System.nanoTime() >= deadline) {
            session.incomplete("wall-clock limit reached");
            finished = true;
        }
        if (finished) {
            ProbeSession.fail(mc, "fartrace collection bound or incomplete capture");
            return true;
        }
        return false;
    }

    private static FarTraceSession.Pose pose(Minecraft mc) {
        if (mc.player == null || !GroundClient.ready()) return null;
        var eye = GroundClient.eye();
        return new FarTraceSession.Pose(
                GroundClient.planet(),
                GroundClient.face(),
                eye.x(),
                eye.y(),
                eye.z(),
                mc.player.getYRot());
    }

    private static void requireSession() {
        if (session == null) throw new IllegalStateException("fartrace watch required");
    }

    private static void export(Minecraft mc, String reason) {
        Path output = mc.gameDirectory.toPath().resolve("logs/fartrace.json");
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(
                    output,
                    new GsonBuilder().setPrettyPrinting().create().toJson(session.report(reason)));
        } catch (IOException error) {
            throw new IllegalStateException("fartrace export " + output, error);
        }
    }

    static void stop(Minecraft mc, String reason) {
        if (session == null) return;
        try {
            try {
                session.collect(FarViewDiagnostics.snapshot(), pose(mc));
            } catch (RuntimeException error) {
                session.incomplete("final collection failed: " + error);
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .warn(
                                "PROBE FARTRACE exporting retained evidence after collection"
                                        + " failure",
                                error);
            }
            export(mc, reason);
        } finally {
            FarViewDiagnostics.stop();
            session = null;
        }
    }

    private DevFarTraceProbe() {}
}
