package dev.starfold.render;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.*;

/** Unit 3 is borrowed by coverage uniforms for one pass or projected frame. */
final class CoverageBinding implements AutoCloseable {
    private final int texture, sampler;
    private final boolean samplers;

    CoverageBinding() {
        int active = GlStateManager._getActiveTexture();
        try {
            GlStateManager._activeTexture(GL13.GL_TEXTURE3);
            texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            samplers = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects;
            sampler = samplers ? GL30.glGetIntegeri(ARBSamplerObjects.GL_SAMPLER_BINDING, 3) : 0;
        } finally {
            GlStateManager._activeTexture(active);
        }
    }

    @Override public void close() {
        int active = GlStateManager._getActiveTexture();
        try {
            GlStateManager._activeTexture(GL13.GL_TEXTURE3);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            if (samplers) ARBSamplerObjects.glBindSampler(3, sampler);
        } finally {
            GlStateManager._activeTexture(active);
        }
    }
}
