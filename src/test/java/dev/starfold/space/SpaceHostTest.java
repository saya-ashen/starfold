package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class SpaceHostTest {
    @Test
    void regionAndHullsHaveNoWaypointOrRenderingDependencies() throws Exception {
        for (String layer : List.of("space", "ship")) {
            var forbidden =
                    java.util.regex.Pattern.compile(
                            layer.equals("space")
                                    ? "dev\\.starfold\\.(waypoint|render)"
                                    : "dev\\.starfold\\.waypoint");
            try (var files = Files.walk(Path.of("src/main/java/dev/starfold/" + layer))) {
                for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var source = Files.readString(file);
                    // SpaceView's sky projection shares the client-wide depth convention.
                    if (file.getFileName().toString().equals("SpaceView.java"))
                        source = source.replace("dev.starfold.render.ReversedDepth.ACTIVE", "");
                    assertFalse(forbidden.matcher(source).find(), file.toString());
                }
            }
        }
        assertTrue(Files.exists(Path.of("src/main/java/dev/starfold/space/RenderFrame.java")));
        assertTrue(Files.exists(Path.of("src/main/java/dev/starfold/waypoint/ShipGuide.java")));
    }

    @Test
    void standaloneHostsHaveNoViewsOrTargets() {
        var server = new SpaceHost() {};
        assertDoesNotThrow(() -> server.targetSync(null, 42));
        var client = new SpaceClientHost() {};
        assertNull(client.ephemeris());
        assertEquals(0, client.skyTime(.5f));
        assertEquals(0, client.sunLimit());
        assertEquals(-1, client.planet());
        assertEquals(-1, client.launchPlanet());
        assertNull(client.home());
        assertDoesNotThrow(client::viewLost);
        assertDoesNotThrow(() -> client.captureTarget(null));
    }
}
