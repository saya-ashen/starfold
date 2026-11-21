package dev.starfold.dev;

import static dev.starfold.dev.ProbeCamera.*;
import static dev.starfold.dev.ProbeInput.*;
import static dev.starfold.dev.ProbeSamples.*;
import static dev.starfold.dev.ProbeSession.*;

import dev.starfold.probe.ProbeState;
import dev.starfold.space.SpaceClient;

import net.minecraft.client.Minecraft;

import java.nio.file.*;
import java.util.*;

/** Development-only scripted client driver. Inert unless -Dstarfold.probe=<script> is set.
 * Lines: wait N | until alt<|alt>|ground|free|controlled [VALUE] TIMEOUT | aim target|track|free | nav TEXT | waitworld | cmd TEXT | key NAME on|off | look YAW PITCH | fly on|off | abilities TEXT | census RADIUS | shot NAME | log TEXT | quit */
public final class DevProbe {
    private static boolean joinedWorld;
    private static boolean handedOff;
    private static int handoffSamples;

    /** Scripted sessions have no focused, mouse-grabbed window; flight input must still be read. */
    public static boolean active() {
        return !handedOff && !cursor.lines.isEmpty();
    }

    public static void initialize() {
        ArrivalClientProbe.initialize();
        String path = System.getProperty("starfold.probe");
        if (path == null) return;
        try {
            cursor.lines = Files.readAllLines(Path.of(path));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Probe script", error);
        }
        DevChunkPerfProbe.initialize();
        ProbeState.drivenBy(DevProbe::active);
        SpaceClient.frameListener = ProbeInput::mouseFrame;
        DevFilm.initialize();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) -> frame());
        ProbeCamera.initialize();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post cpEvent) -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    try {
                        DevProbe.tick(mc);
                    } catch (RuntimeException error) {
                        if (DevFarTraceProbe.active()) {
                            try {
                                DevFarTraceProbe.stop(mc, "exception: " + error);
                            } catch (RuntimeException exportError) {
                                error.addSuppressed(exportError);
                            }
                        }
                        if (!cursor.strict) throw error;
                        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                                .error("PROBE exception", error);
                        fail(mc, "probe exception " + error);
                    }
                });
    }

    static boolean handOff(Minecraft mc) {
        if (!Boolean.getBoolean("starfold.probe.handoff") || mc.player == null) return false;
        handedOff = true;
        handoffSnapshot(mc);
        org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE HANDOFF READY");
        return true;
    }

    private static void handoffSnapshot(Minecraft mc) {
        if (mc.player == null) return;
        String label = "handoff-" + ++handoffSamples;
        commandHelmstate(mc, label);
        DevLandingProbe.logLoopStair(mc, label);
        commandShot(mc, label);
    }

    private static void handleHandoff(Minecraft mc) {
        var request = mc.gameDirectory.toPath().resolve("probe-handoff-client.txt");
        if (!Files.isRegularFile(request)) return;
        try {
            String command = Files.readString(request).strip();
            Files.delete(request);
            switch (command) {
                case "snapshot" -> handoffSnapshot(mc);
                case "quit" -> mc.stop();
                default ->
                        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                                .warn("PROBE HANDOFF unknown command {}", command);
            }
        } catch (java.io.IOException error) {
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .warn("PROBE HANDOFF request failed", error);
        }
    }

    // Step/await helpers return true when they consume the tick; later work must not run.
    private static void tick(Minecraft mc) {
        if (handedOff) {
            handleHandoff(mc);
            return;
        }
        if (mc.level != null && mc.getConnection() != null) joinedWorld = true;
        if (joinedWorld && mc.getConnection() == null && cursor.line < cursor.lines.size()) {
            fail(mc, "disconnected before script completion");
            return;
        }
        if (DevFarTraceProbe.tick(mc)) return;
        if (DevPlanetSoundProbe.enabled()) DevPlanetSoundProbe.tick(mc);
        updatePresentation(mc);
        if (DevFlightPerfProbe.tick(mc)) return;
        DevTripProbe.tick(mc);
        if (DevFeelProbe.tick(mc) || DevGuideProbe.tick(mc) || DevLandingProbe.tick(mc)) return;
        updateSampling(mc);
        if (awaitReceipt(mc)) return;
        if (awaitMotion(mc)) return;
        if (DevBaritoneProbe.tick(mc) || walkStep(mc)) return;
        updateTracking(mc);
        if (shipAimStep(mc)) return;
        if (mouseAimStep(mc)) return;
        if (steerLeft > 0 && mc.player != null) {
            if (steer(mc)) return;
        }
        if (homeStep(mc)) return;
        if (headingStep(mc)) return;
        if (chartTravelStep(mc)) return;
        if (awaitCondition(mc)) return;
        runScript(mc);
        if (cursor.line >= cursor.lines.size()) DevFarTraceProbe.stop(mc, "script-end");
    }

    private DevProbe() {}
}
