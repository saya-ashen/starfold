package dev.starfold;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

class SourcesTest {
    @Test void explicitPathAndPreviouslyUnsearchedModuleResolve() throws Exception {
        var path = Path.of("biotech/src/main/java/dev/starfold/biotech/terrain/HiveLandform.java");
        assertEquals(path.toAbsolutePath().normalize(), Sources.path("HiveLandform").toAbsolutePath().normalize());
        assertEquals(Files.readString(path).replaceAll("\\s+", ""), Sources.text(path.toString()).toString());
        assertThrows(IOException.class, () -> Sources.text("src/main/java/AbsentSource.java"));
    }

    @Test void duplicateShortNameReportsBothCandidatesAndExplicitPathsStillWork() throws Exception {
        var module = Files.createTempDirectory(Path.of("."), "source-lookup-test-");
        var source = module.resolve("src/main/java/duplicate/Starfold.java");
        try {
            Files.createDirectories(source.getParent());
            Files.writeString(source, "class Starfold {}");
            var error = assertThrows(IOException.class, () -> Sources.path("Starfold"));
            assertTrue(error.getMessage().contains("src/main/java/dev/starfold/Starfold.java"));
            assertTrue(error.getMessage().contains(source.toString()));
            assertEquals("classStarfold{}", Sources.text(source.toString()).toString());
        } finally {
            try (var files = Files.walk(module)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
