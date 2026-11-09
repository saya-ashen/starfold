package dev.starfold.worldgen.plan;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class PlanetPlanFixtureLifecycleTest {
    @Test void reopeningClosesOldStorageAndCloseReleasesContextAndFiles() throws Exception {
        try {
            PlanetPlanFixtures.boot();
            var first = PlanetPlanFixtures.directory;
            var context = PlanetPlanFixtures.context;
            assertTrue(Files.exists(first.resolve("templates/session.lock")));
            PlanetPlanFixtures.boot();
            assertNotEquals(first, PlanetPlanFixtures.directory);
            assertNotSame(context, PlanetPlanFixtures.context);
            assertFalse(Files.exists(first));
            var second = PlanetPlanFixtures.directory;
            PlanetPlanFixtures.close();
            assertNull(PlanetPlanFixtures.context);
            assertNull(PlanetPlanFixtures.storage);
            assertNull(PlanetPlanFixtures.directory);
            assertFalse(Files.exists(second));
            assertDoesNotThrow(PlanetPlanFixtures::close);
        } finally {
            PlanetPlanFixtures.close();
        }
    }
}
