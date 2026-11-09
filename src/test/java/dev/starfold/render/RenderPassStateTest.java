package dev.starfold.render;

import net.neoforged.neoforge.client.GlStateBackup;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RenderPassStateTest {
    static final class Recording implements RenderPassState.Operations {
        final List<String> calls = new ArrayList<>();
        int mappedDepth;
        public void depthMask(boolean value) { calls.add("write:" + value); }
        public void depthFunc(int value) {
            calls.add("logical:" + value);
            mappedDepth = ReversedDepth.depthFunc(value);
        }
        public void depth(boolean value) { calls.add("depth:" + value); }
        public void cull(boolean value) { calls.add("cull:" + value); }
        public void blendFunction(int sr, int dr, int sa, int da) {
            calls.add("factors:" + sr + ":" + dr + ":" + sa + ":" + da);
        }
        public void blend(boolean value) { calls.add("blend:" + value); }
    }

    @Test void restoreUsesLogicalDepthOnceAndOnlyChangedCategories() {
        for (int logical : new int[] {GL11.GL_LESS, GL11.GL_LEQUAL}) {
            var saved = new GlStateBackup();
            saved.depthFunc = logical;
            saved.depthMask = true;
            saved.cullEnabled = true;
            saved.blendEnabled = true;
            saved.blendSrcRgb = 1; saved.blendDestRgb = 2;
            saved.blendSrcAlpha = 3; saved.blendDestAlpha = 4;
            var output = new Recording();
            RenderPassState.restore(saved, true, true, output);
            assertEquals(List.of("write:true", "logical:" + logical, "depth:false", "cull:true",
                    "factors:1:2:3:4", "blend:true"), output.calls);
            assertEquals(logical == GL11.GL_LESS ? GL11.GL_GREATER : GL11.GL_GEQUAL, output.mappedDepth);
            output.calls.clear();
            RenderPassState.restore(saved, false, false, output);
            assertEquals(List.of("write:true", "depth:false", "cull:true", "blend:true"), output.calls);
        }
    }

    @Test void throwingUniformCleanupStillRestoresStateInOrder() {
        var calls = new ArrayList<String>();
        var error = new IllegalStateException("uniform");
        assertSame(error, assertThrows(IllegalStateException.class, () -> RenderPassState.finish(
                () -> { calls.add("cleanup"); throw error; }, () -> calls.add("restore"))));
        assertEquals(List.of("cleanup", "restore"), calls);
        calls.clear();
        RenderPassState.finish(() -> calls.add("cleanup"), () -> calls.add("restore"));
        assertEquals(List.of("cleanup", "restore"), calls);
    }
}
