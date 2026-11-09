package dev.starfold;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class VoxelTestEvidenceTest {
    @Test
    void repeatedInvocationsNeverReuseEvidenceDirectory() throws Exception {
        Path first = VoxelTestEvidence.directory("isolation"),
                second = VoxelTestEvidence.directory("isolation");
        assertNotEquals(first, second);
        assertEquals(Path.of("build/ca-voxel/test-runs"), first.getParent());
        Files.writeString(first.resolve("cost.json"), "original");
        Files.writeString(second.resolve("cost.json"), "new run");
        assertEquals("original", Files.readString(first.resolve("cost.json")));
        assertEquals("new run", Files.readString(second.resolve("cost.json")));
        for (String invalid : new String[] {"../review-fix-1", "", "a/b", "a\\b"})
            assertThrows(
                    IllegalArgumentException.class, () -> VoxelTestEvidence.directory(invalid));
    }
}
