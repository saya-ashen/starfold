package dev.starfold.render;

import dev.starfold.universe.PlanetPackets;
import dev.starfold.worldgen.PlanetTerrain;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemotePlanetMeshesTest {
    @Test void changedSessionRetainsResidentAsFallbackUntilExplicitClose() {
        var meshes = new RemotePlanetMeshes();
        var profile = SurfaceProfiles.current(new PlanetPackets.Profile(17, 42, PlanetTerrain.VERSION));
        meshes.view.summary(new PlanetRemotePackets.Summary(0, profile, 1, new long[0], new long[0]));
        var resident = meshes.view.snapshot();
        meshes.resident = resident;
        meshes.residentStep = 32;
        meshes.session(17);
        assertSame(resident, meshes.resident);
        assertNull(meshes.fallback);
        meshes.session(18);
        assertNull(meshes.resident);
        assertSame(resident, meshes.fallback);
        assertEquals(0, meshes.view.session());
        meshes.session(19);
        assertSame(resident, meshes.fallback, "a second transition must retain the last complete cohort");
        meshes.close();
        meshes.close();
        assertNull(meshes.fallback);
        assertNull(meshes.building);
        assertNull(meshes.inputSource);
        assertNull(meshes.inputHeights);
        assertNull(meshes.inputCover);
    }
}
