package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class GroundGpuBudgetTest {
    @TempDir Path directory;

    @Test
    void startupFileSelectsEachCompleteBudgetPairWithoutChangingTheDefault() throws Exception {
        var file = directory.resolve("starfold-ground.properties");
        assertEquals(GroundGpuBudget.Profile.COMPACT, GroundGpuBudget.load(file));
        long[][] expected = {{768, 1024}, {768, 1280}, {1536, 2304}};
        for (var profile : GroundGpuBudget.Profile.values()) {
            Files.writeString(file, "# Restart to apply\ngpuBudget = " + profile.name().toLowerCase(java.util.Locale.ROOT) + " \n");
            assertEquals(profile, GroundGpuBudget.load(file));
            assertEquals(expected[profile.ordinal()][0] << 20, profile.detailBytes);
            assertEquals(expected[profile.ordinal()][1] << 20, profile.arenaBytes);
            assertTrue(profile.arenaBytes > profile.detailBytes);
        }
    }

    @Test
    void missingUnknownMalformedOrUnreadableSelectionKeepsTheSmallBudget() throws Exception {
        var file = directory.resolve("starfold-ground.properties");
        for (String text : new String[] {"# no choice", "gpuBudget=\n", "gpuBudget=unlimited", "gpuBudget=\\uZZZZ"}) {
            Files.writeString(file, text);
            assertEquals(GroundGpuBudget.Profile.COMPACT, GroundGpuBudget.load(file));
        }
        assertEquals(GroundGpuBudget.Profile.COMPACT, GroundGpuBudget.load(directory));
    }
}
