package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

class GroundNativeCoverageTest {
    private static BitSet bits(int... values) {
        var out = new BitSet();
        for (int v : values) out.set(v);
        return out;
    }

    @Test
    void missingOrUncompiledSurfaceKeepsFallback() {
        assertFalse(GroundNativeCoverage.surfaceReady(bits(), bits(), all()));
        assertFalse(GroundNativeCoverage.surfaceReady(bits(0, 1, 10, 11), bits(0, 1), all()));
        assertFalse(GroundNativeCoverage.surfaceReady(bits(0, 1, 10, 11), bits(11), all()));
        assertTrue(GroundNativeCoverage.surfaceReady(bits(0, 1, 10, 11), bits(10, 11), all()));
        assertTrue(GroundNativeCoverage.surfaceReady(bits(40), bits(40), all()));
    }

    @Test
    void highAltitudeAndNearPlayerAirNeverOwnUndrawnGround() {
        // The camera is 200 blocks above ground in the same horizontal chunk. Vanilla's
        // visible list can contain compiled EMPTY air without drawing the ground section.
        var ground = bits(0, 1, 10, 11);
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(24), all()));
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(), all()));
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(10), all()));
        assertFalse(GroundNativeCoverage.surfaceReady(bits(), bits(24), all()));
        assertTrue(GroundNativeCoverage.surfaceReady(ground, bits(10, 11), all()));
        // Next frame the ground is culled: last frame's successful draw grants no ownership.
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(), all()));
    }

    @Test
    void buriedSectionBelowTheDrawnSurfaceNeedNotBeDrawn() {
        var ground = bits(0, 1, 10, 11);
        // Ground and canopy share section 11; solid section 10 is occluded, never listed or drawn.
        assertTrue(GroundNativeCoverage.surfaceReady(ground, bits(11), bits(11)));
        // Listed as visible but not drawn yet: it would show a hole, so keep the fallback.
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(11), bits(10, 11)));
        // An unlisted section never stands in for the top surface itself.
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(10), bits()));
    }

    @Test
    void canopyAboveTheCameraOutOfViewDoesNotHandTheChunkBack() {
        // Looking down in the player's own chunk: canopy section 11 is outside the frustum, unlisted.
        var ground = bits(0, 1, 10, 11);
        assertTrue(GroundNativeCoverage.surfaceReady(ground, bits(10), bits(10)));
        assertTrue(GroundNativeCoverage.surfaceReady(ground, bits(1, 10), bits(1, 10)));
        // The listed ground itself, and a listed section below it, still have to be drawn.
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(), bits(10)));
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(10), bits(1, 10)));
    }

    @Test
    void checksSubmittedMeshesWithoutRequestingChunks() throws Exception {
        var source = Sources.text("GroundNativeCoverage");
        // The visible list only excuses an undrawn section below; it never grants ownership.
        assertTrue(source.contains("!listed.get(below)"));
        assertTrue(source.contains("CompiledSection.UNCOMPILED"));
        assertTrue(source.contains("compiled.hasNoRenderableLayers()"));
        assertFalse(source.contains("booleannear="));
        var hook = Sources.text("GroundNativeDrawMixin");
        assertTrue(hook.contains("method=\"renderLevel\",at=@At(\"HEAD\")"));
        assertTrue(hook.contains("GroundNativeCoverage.beginFrame()"));
        assertTrue(hook.contains("VertexBuffer;draw()V"));
        assertTrue(hook.indexOf("original.call(buffer)") < hook.indexOf("GroundNativeCoverage.drawn(section)"));
        var renderer = Sources.text("GroundRenderer");
        assertTrue(renderer.contains("refreshNativeDraws();draw(c,false)"));
        assertTrue(renderer.contains("refreshNativeDraws();draw(c,true)"));
        assertTrue(source.contains("getChunkNow(pos.x,pos.z)"));
        assertFalse(source.contains("getChunk("));
        assertTrue(source.contains("GroundTopology.address(GroundClient.shape(),local)"));
        assertTrue(Sources.text("GroundRenderer").contains("GroundNativeCoverage.visible()"));
    }

    @Test
    void translucentSubmissionMayCompleteButNeverPrecedeTheSurface() {
        var occupied = bits(10, 11);
        var listed = bits(10, 11);
        var submitted = bits(10);
        assertFalse(GroundNativeCoverage.surfaceReady(occupied, submitted, listed));
        // Section 11 has only translucent geometry, submitted after AFTER_ENTITIES.
        submitted.set(11);
        assertTrue(GroundNativeCoverage.surfaceReady(occupied, submitted, listed));
        assertFalse(GroundNativeCoverage.surfaceReady(occupied, bits(), listed));
    }

    private static BitSet all() {
        var out = new BitSet();
        out.set(0, 64);
        return out;
    }
}
