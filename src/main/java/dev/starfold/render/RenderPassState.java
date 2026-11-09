package dev.starfold.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.GlStateBackup;

/** Saves Minecraft's logical state once per pass; program/VAO bindings stay with their pass. */
final class RenderPassState implements AutoCloseable {
    interface Operations {
        void depthMask(boolean enabled);
        void depthFunc(int logicalFunction);
        void depth(boolean enabled);
        void cull(boolean enabled);
        void blendFunction(int sr, int dr, int sa, int da);
        void blend(boolean enabled);
    }

    private static final Operations GL = new Operations() {
        public void depthMask(boolean value) { RenderSystem.depthMask(value); }
        public void depthFunc(int value) { RenderSystem.depthFunc(value); }
        public void depth(boolean value) {
            if (value) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        }
        public void cull(boolean value) {
            if (value) RenderSystem.enableCull(); else RenderSystem.disableCull();
        }
        public void blendFunction(int sr, int dr, int sa, int da) {
            RenderSystem.blendFuncSeparate(sr, dr, sa, da);
        }
        public void blend(boolean value) {
            if (value) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        }
    };

    private final GlStateBackup saved = new GlStateBackup();
    private final ShaderInstance shader;
    private final float[] color;
    private final boolean blendFunction, depthFunction;

    RenderPassState(boolean saveColor, boolean blendFunction, boolean depthFunction) {
        GlStateManager._backupGlState(saved);
        shader = RenderSystem.getShader();
        color = saveColor ? RenderSystem.getShaderColor().clone() : null;
        this.blendFunction = blendFunction;
        this.depthFunction = depthFunction;
    }

    static void restore(GlStateBackup saved, boolean blendFunction, boolean depthFunction,
                        Operations operations) {
        operations.depthMask(saved.depthMask);
        if (depthFunction) operations.depthFunc(saved.depthFunc);
        operations.depth(saved.depthEnabled);
        operations.cull(saved.cullEnabled);
        if (blendFunction) operations.blendFunction(saved.blendSrcRgb, saved.blendDestRgb,
                saved.blendSrcAlpha, saved.blendDestAlpha);
        operations.blend(saved.blendEnabled);
    }

    /** Uniform cleanup may fail; state restoration must still execute. */
    static void finish(Runnable cleanup, Runnable restore) {
        try { cleanup.run(); } finally { restore.run(); }
    }

    @Override public void close() {
        try {
            restore(saved, blendFunction, depthFunction, GL);
        } finally {
            if (color != null) RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.setShader(() -> shader);
        }
    }
}
