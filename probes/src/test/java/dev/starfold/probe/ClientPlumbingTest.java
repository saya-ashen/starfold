package dev.starfold.probe;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class ClientPlumbingTest {
    @Test
    void productionClientsNoLongerNameRenderOrDevelopment() throws Exception {
        for (String path :
                new String[] {
                    "ship/HullLandingClient.java",
                    "ship/SableCarryClient.java",
                    "waypoint/WaypointClient.java"
                }) {
            var source = Sources.text("src/main/java/dev/starfold/" + path);
            assertFalse(source.contains("dev.starfold.render"), path);
            assertFalse(source.contains("dev.starfold.dev"), path);
        }
        for (String pkg : new String[] {"network", "render"}) {
            try (var files = Files.walk(Path.of("src/main/java/dev/starfold/" + pkg))) {
                for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var source = Files.readString(file);
                    assertFalse(source.contains("dev.starfold.dev."), file.toString());
                    if (!pkg.equals("render")) {
                        for (String higher :
                                new String[] {
                                    "sky",
                                    "worldgen",
                                    "universe",
                                    "ground",
                                    "flight",
                                    "ship",
                                    "space",
                                    "waypoint",
                                    "render"
                                })
                            assertFalse(
                                    source.contains("dev.starfold." + higher + "."),
                                    file.toString());
                    }
                }
            }
        }
    }
}
