package dev.starfold.render;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.Properties;

/** Startup-only limits; caps never preallocate GPU storage. */
final class GroundGpuBudget {
    enum Profile {
        COMPACT(768, 1024),
        HEADROOM(768, 1280),
        ROOMY(1536, 2304);

        final long detailBytes, arenaBytes;

        Profile(long detailMiB, long arenaMiB) {
            detailBytes = detailMiB << 20;
            arenaBytes = arenaMiB << 20;
        }
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GroundGpuBudget.class);
    static final Profile SELECTED = load(Path.of("config", "starfold-ground.properties"));

    static Profile load(Path path) {
        if (!Files.exists(path)) return Profile.COMPACT;
        try (var reader = Files.newBufferedReader(path)) {
            var properties = new Properties();
            properties.load(reader);
            return Profile.valueOf(properties.getProperty("gpuBudget", "compact").trim().toUpperCase(Locale.ROOT));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Cannot load ground GPU budget from {}; using compact (768/1024 MiB)", path, e);
            return Profile.COMPACT;
        }
    }

    private GroundGpuBudget() {}
}
