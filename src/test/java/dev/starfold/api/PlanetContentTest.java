package dev.starfold.api;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;

import dev.starfold.universe.PlanetTypeFixtures;
import dev.starfold.universe.SystemChunkGenerator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlanetContentTest {
    @BeforeAll
    static void boot() {
        PlanetTypeFixtures.boot();
    }

    @Test
    void starterHomeTakesPriorityRegardlessOfRegistrationOrder() {
        var terrain = new PlanetContent.Spawn(null, 10);
        var ship = new PlanetContent.Spawn(null, -55);
        var starter =
                new PlanetContent() {
                    @Override
                    public Spawn starterHome(net.minecraft.server.level.ServerLevel level, int face) {
                        return ship;
                    }
                };
        var oldStarter = PlanetContent.starter();
        var oldTerrain = PlanetContent.installHomeSpawn((level, face) -> terrain);
        try {
            PlanetContent.installStarter(null);
            assertSame(terrain, PlanetContent.home(null, 0));
            assertFalse(PlanetContent.starter().missingStarterDependencies());
            PlanetContent.installStarter(starter);
            assertSame(ship, PlanetContent.home(null, 0));
            var replacement = new PlanetContent.Spawn(null, 20);
            PlanetContent.installHomeSpawn((level, face) -> replacement);
            assertSame(ship, PlanetContent.home(null, 0));
            PlanetContent.installStarter(null);
            assertSame(replacement, PlanetContent.home(null, 0));
        } finally {
            PlanetContent.installHomeSpawn(oldTerrain);
            PlanetContent.installStarter(oldStarter);
        }
    }

    @Test
    void coreCodecAndDefaultUniverseWorkWithoutContentRegistration() throws Exception {
        var installed = PlanetContent.starter();
        PlanetContent.installStarter(null);
        try {
            var core = PlanetContent.starter();
            var generator =
                    SystemChunkGenerator.CODEC
                            .codec()
                            .parse(
                                    PlanetTypeFixtures.ops(),
                                    PlanetTypeFixtures.preset("native_universe"))
                            .getOrThrow();
            assertFalse(generator.startsWithShip());
            assertFalse(core.overPad(null, null));
            assertNull(core.launchCentre(null, null));
            var saved =
                    SystemChunkGenerator.CODEC
                            .codec()
                            .encodeStart(PlanetTypeFixtures.ops(), generator)
                            .getOrThrow();
            assertFalse(saved.getAsJsonObject().has("starter_ship"));
            assertFalse(
                    SystemChunkGenerator.CODEC
                            .codec()
                            .parse(PlanetTypeFixtures.ops(), saved)
                            .getOrThrow()
                            .startsWithShip());
            assertFalse(
                    SystemChunkGenerator.CODEC
                            .keys(JsonOps.INSTANCE)
                            .anyMatch(key -> key.getAsString().equals("starter_ship")));
        } finally {
            PlanetContent.installStarter(installed);
        }
    }
}
