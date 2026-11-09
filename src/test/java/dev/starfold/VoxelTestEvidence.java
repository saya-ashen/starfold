package dev.starfold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** fresh output directory for each evidence-producing test invocation. */
public final class VoxelTestEvidence {
    private VoxelTestEvidence() {}

    public static Path directory(String label) throws IOException {
        if (!label.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("invalid evidence label");
        Path parent = Path.of("build/ca-voxel/test-runs");
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, label + "-");
    }
}
