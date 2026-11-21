package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** Only an explicit system preset installs planet storage. */
class SystemOnlyTest {
    @Test
    void everyNativePresetHoldsItsPlanetsInOneSystemLevel() throws Exception {
        var root = Path.of("src/main/resources/data/starfold");
        assertFalse(Files.exists(root.resolve("dimension/native_ground.json")));
        assertFalse(Files.exists(root.resolve("dimension/native_planet.json")));
        assertFalse(Files.exists(root.resolve("dimension_physics/native_rock.json")));
        assertFalse(Files.exists(root.resolve("dimension_physics/native_ground.json")));
        try (var files = Files.list(root.resolve("worldgen/world_preset"))) {
            for (var file : files.toList()) {
                var dimensions =
                        JsonParser.parseString(Files.readString(file))
                                .getAsJsonObject()
                                .getAsJsonObject("dimensions");
                assertFalse(dimensions.has("starfold:native_rock"), file.toString());
                assertFalse(dimensions.has("starfold:native_ground"), file.toString());
                var generator =
                        dimensions
                                .getAsJsonObject("starfold:native_planet")
                                .getAsJsonObject("generator");
                assertEquals("starfold:native_system", generator.get("type").getAsString());
                assertEquals(PlanetStorage.LAYOUT, generator.get("storage_layout").getAsString());
                assertFalse(generator.has("a") || generator.has("b"));
            }
        }
    }

    @Test
    void dimensionTypesKeepTheirGroundEffectsKey() throws Exception {
        var root = Path.of("src/main/resources/data/starfold/dimension_type");
        for (String name : new String[] {"native_ground", "native_deep"}) {
            var type =
                    JsonParser.parseString(Files.readString(root.resolve(name + ".json")))
                            .getAsJsonObject();
            assertEquals("starfold:native_ground", type.get("effects").getAsString());
        }
    }
}
