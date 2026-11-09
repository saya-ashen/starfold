package dev.starfold.render;

import dev.starfold.worldgen.PlanetTerrainTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GroundSurfaceDiskTest {
    @TempDir Path directory;

    @BeforeAll static void boot() { PlanetTerrainTest.registry(); }

    @Test
    void roundTripKeepsSparseRunsAndDropsRemovedKeys() throws Exception {
        var image = GroundSurfaceRunsTest.forest();
        var file = directory.resolve("123-0.bin");
        GroundSurfaceDisk.save(file, Map.of(7L, image, 9L, image));
        var changed = new BitSet();
        changed.set(7);
        var loaded = GroundSurfaceDisk.load(file, changed);
        assertEquals(Set.of(7L), loaded.keySet());
        assertTrue(image.sameAs(loaded.get(7L)));
        GroundSurfaceDisk.save(file, loaded);
        changed.set(9);
        assertEquals(Set.of(7L), GroundSurfaceDisk.load(file, changed).keySet());
        GroundSurfaceDisk.save(file, Map.of());
        assertTrue(GroundSurfaceDisk.load(file, changed).isEmpty());
        Files.write(file, new byte[] {0, 0, 0, 0});
        assertThrows(java.io.IOException.class, () -> GroundSurfaceDisk.load(file, changed));
    }
}
