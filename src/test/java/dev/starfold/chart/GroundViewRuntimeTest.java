package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.SourceText;
import dev.starfold.Sources;
import dev.starfold.render.GroundChunkSnapshot;
import dev.starfold.render.GroundShaderSource;
import dev.starfold.render.GroundStreaming;
import dev.starfold.render.GroundViewCache;
import dev.starfold.render.GroundViewPackets;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.*;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

class GroundViewRuntimeTest {
    @BeforeAll
    static void registry() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void nativeShaderAddsOrbitalAndRoundLensButRetainsVanillaMain() throws Exception {
        String s =
                GroundShaderSource.patch(
                        "rendertype_solid",
                        true,
                        "#version 150\nuniform mat4 ProjMat;uniform mat4 ModelViewMat;in vec3 Position;void main(){gl_Position=ProjMat*ModelViewMat*vec4(Position,1);}");
        assertTrue(s.contains("cpNativeOrbitRepair(point)"));
        assertTrue(s.contains("cpNativeProject(view)"));
        assertTrue(s.contains("CPNativeOrbitLimits.w"));
        assertTrue(s.contains("-view.z-r*cosine"));
        assertTrue(s.contains("cpNativeOriginalMain();"));
        assertEquals(s, GroundShaderSource.patch("rendertype_solid", true, s));
    }
}
