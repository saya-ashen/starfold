package dev.starfold.render;

import net.minecraft.client.renderer.RenderType;
import java.util.function.IntPredicate;

/** CPU ordering shared by the live pass and recording tests. No GL or mesh publication. */
final class GroundDrawPlan {
    interface Draw {
        void begin(RenderType layer);
        void mask(int face, boolean residual);
        void stitch(int face);
        void surface(int face, int patch, boolean ready);
        void lod(int face);
        void end(RenderType layer);
    }

    static void draw(boolean translucent, boolean orbital, int observerFace,
                     boolean ready, IntPredicate visible, Draw draw) {
        for (var layer : RenderType.chunkBufferLayers()) {
            if ((layer == RenderType.translucent() || layer == RenderType.tripwire()) != translucent)
                continue;
            draw.begin(layer);
            try {
                for (int face = 0; face < 6; face++) {
                    if (!orbital && !visible.test(face)) continue;
                    draw.mask(face, false);
                    if (!orbital && face == observerFace) draw.mask(face, true);
                    draw.stitch(face);
                    for (int patch = face * 64; patch < (face + 1) * 64; patch++)
                        draw.surface(face, patch, ready);
                    draw.lod(face);
                }
            } finally {
                draw.end(layer);
            }
        }
    }

    private GroundDrawPlan() {}
}
