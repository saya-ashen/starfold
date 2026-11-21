package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

class SkyHostTest {
    @Test
    void standaloneAnswersAreNeutralAndServerAndClientInstallationsAreIndependent() {
        var server = SkyHost.get();
        var client = SkyHost.client();
        try {
            var neutral = new SkyHost() {};
            assertFalse(neutral.underWay());
            assertFalse(neutral.present());
            assertEquals(1, neutral.sunlight(null, null, null));
            assertEquals(new Point(1, 2, 3), neutral.storage(1, 2, 3));
            SkyHost.install(neutral);
            SkyHost.installClient(new SkyHost() {});
            assertSame(neutral, SkyHost.get());
            assertNotSame(neutral, SkyHost.client());
            var installedClient = SkyHost.client();
            SkyHost.install(new SkyHost() {});
            assertSame(installedClient, SkyHost.client());
            assertThrows(NullPointerException.class, () -> SkyHost.install(null));
            assertThrows(NullPointerException.class, () -> SkyHost.installClient(null));
        } finally {
            SkyHost.install(server);
            SkyHost.installClient(client);
        }
    }
}
