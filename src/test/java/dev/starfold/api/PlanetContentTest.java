package dev.starfold.api;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import dev.starfold.universe.PlanetTypeFixtures;
import dev.starfold.universe.SystemChunkGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlanetContentTest {
    @BeforeAll
    static void boot() { PlanetTypeFixtures.boot(); }

    @Test
    void coreCodecAndDefaultUniverseWorkWithoutContentRegistration() throws Exception {
        var installed = PlanetContent.get();
        PlanetContent.install(new PlanetContent() {});
        try {
            var core = PlanetContent.get();
            var generator = SystemChunkGenerator.CODEC.codec()
                    .parse(PlanetTypeFixtures.ops(), PlanetTypeFixtures.preset("native_universe")).getOrThrow();
            assertFalse(generator.startsWithShip());
            assertFalse(core.overPad(null, null));
            assertNull(core.launchCentre(null, null));
            var saved = SystemChunkGenerator.CODEC.codec().encodeStart(PlanetTypeFixtures.ops(), generator).getOrThrow();
            assertFalse(saved.getAsJsonObject().has("starter_ship"));
            assertFalse(SystemChunkGenerator.CODEC.codec().parse(PlanetTypeFixtures.ops(), saved).getOrThrow().startsWithShip());
            assertFalse(SystemChunkGenerator.CODEC.keys(JsonOps.INSTANCE)
                    .anyMatch(key -> key.getAsString().equals("starter_ship")));
        } finally {
            PlanetContent.install(installed);
        }
    }
}
