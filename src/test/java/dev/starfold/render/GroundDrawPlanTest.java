package dev.starfold.render;

import net.minecraft.client.renderer.RenderType;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundDrawPlanTest {
    static class Recording implements GroundDrawPlan.Draw {
        final List<String> events = new ArrayList<>();
        final List<RenderType> layers = new ArrayList<>();
        public void begin(RenderType layer) { layers.add(layer); events.add("begin"); }
        public void mask(int face, boolean residual) { events.add("mask:" + face + ":" + residual); }
        public void stitch(int face) { events.add("stitch:" + face); }
        public void surface(int face, int patch, boolean ready) {
            events.add((ready ? "fine:" : "parent:") + patch);
        }
        public void lod(int face) { events.add("lod:" + face); }
        public void end(RenderType layer) { events.add("end"); }
    }

    @Test void opaquePassOrdersMasksStripsFallbackAndLodWithinEveryLayer() {
        var draw = new Recording();
        GroundDrawPlan.draw(false, false, 2, false, face -> face == 2, draw);
        assertEquals(List.of(RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout()), draw.layers);
        var expected = new ArrayList<String>();
        for (int layer = 0; layer < 3; layer++) {
            expected.addAll(List.of("begin", "mask:2:false", "mask:2:true", "stitch:2"));
            for (int patch = 128; patch < 192; patch++) expected.add("parent:" + patch);
            expected.addAll(List.of("lod:2", "end"));
        }
        assertEquals(expected, draw.events);
    }

    @Test void orbitalTranslucentPassUsesCompleteFineCohortAndAllSixFaces() {
        var draw = new Recording();
        GroundDrawPlan.draw(true, true, 2, true, face -> fail("orbital pass has no planar cull"), draw);
        assertEquals(List.of(RenderType.translucent(), RenderType.tripwire()), draw.layers);
        assertEquals(768, draw.events.stream().filter(e -> e.startsWith("fine:")).count());
        assertFalse(draw.events.stream().anyMatch(e -> e.startsWith("parent:") || e.endsWith(":true")));
        assertTrue(draw.events.indexOf("fine:63") < draw.events.indexOf("lod:0"));
        assertTrue(draw.events.indexOf("lod:0") < draw.events.indexOf("mask:1:false"));
    }

    @Test void failedDrawStillEndsTheLayer() {
        var draw = new Recording() {
            @Override public void stitch(int face) { throw new IllegalStateException("draw failed"); }
        };
        assertThrows(IllegalStateException.class,
                () -> GroundDrawPlan.draw(false, false, 0, true, face -> true, draw));
        assertEquals(List.of("begin", "mask:0:false", "mask:0:true", "end"), draw.events);
    }
}
