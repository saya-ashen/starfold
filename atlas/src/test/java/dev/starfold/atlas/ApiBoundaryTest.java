package dev.starfold.atlas;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ApiBoundaryTest {
    @Test void dependenciesOnlyCrossThePublicApiAndNeverPointBackToAtlas() throws Exception {
        var imports = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?(dev\\.starfold\\.[\\w.*]+)");
        try (var files = Files.walk(Path.of("atlas/src"))) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var matcher = imports.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String imported = matcher.group(1);
                    assertTrue(imported.startsWith("dev.starfold.api.") || imported.startsWith("dev.starfold.atlas."),
                            () -> file + " bypasses API: " + imported);
                }
            }
        }
        try (var files = Files.walk(Path.of("src"))) {
            for (var file : files.filter(Files::isRegularFile).toList())
                assertFalse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("dev.starfold.atlas"),
                        () -> file + " references Atlas");
        }
    }
}
