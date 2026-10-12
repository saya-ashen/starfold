package dev.starfold.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;

import org.lwjgl.opengl.*;

/** Scoped atlas sampling, not geometry or a global Minecraft graphics-setting change.
 * textureGrad alone does not enable mip filtering on a texture left in GL_NEAREST state.
 * Restore both texture parameters and a pre-existing sampler override, including on failure. */
public final class GroundTextureFilter implements AutoCloseable {
    public static int mode; // 0 filtered (default), 1 flat diagnostic, 2 original diagnostic
    private static int cachedAtlas = -1, cachedLast;
    public final int lastMip;
    private final int atlas, min, mag, sampler, maxLevel;
    private final boolean anisotropic, samplers, changed;
    private final float anisotropy, minLod, maxLod, bias;

    public static GroundTextureFilter open() {
        return new GroundTextureFilter();
    }

    private GroundTextureFilter() {
        atlas =
                Minecraft.getInstance()
                        .getTextureManager()
                        .getTexture(TextureAtlas.LOCATION_BLOCKS)
                        .getId();
        var caps = GL.getCapabilities();
        anisotropic =
                caps.GL_EXT_texture_filter_anisotropic || caps.GL_ARB_texture_filter_anisotropic;
        samplers = caps.OpenGL33 || caps.GL_ARB_sampler_objects;
        sampler = samplers ? GL30.glGetIntegeri(ARBSamplerObjects.GL_SAMPLER_BINDING, 0) : 0;
        changed = mode != 2;
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
        try {
            maxLevel = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL);
            min = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
            mag = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
            minLod = GL11.glGetTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD);
            maxLod = GL11.glGetTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD);
            bias = GL11.glGetTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS);
            anisotropy =
                    anisotropic
                            ? GL11.glGetTexParameterf(
                                    GL11.GL_TEXTURE_2D,
                                    EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT)
                            : 1;
            if (cachedAtlas != atlas) {
                cachedAtlas = atlas;
                cachedLast = 0;
                int max = Math.min(16, maxLevel);
                for (int i = 1; i <= max; i++) {
                    if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, i, GL11.GL_TEXTURE_WIDTH)
                            == 0) break;
                    cachedLast = i;
                }
            }
            lastMip = cachedLast;
            if (changed) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, lastMip);
                GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0);
                GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, lastMip);
                GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 0);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D,
                        GL11.GL_TEXTURE_MIN_FILTER,
                        lastMip > 0 ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                if (anisotropic)
                    GL11.glTexParameterf(
                            GL11.GL_TEXTURE_2D,
                            EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT,
                            Math.min(
                                    8,
                                    GL11.glGetFloat(
                                            EXTTextureFilterAnisotropic
                                                    .GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT)));
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
        }
        if (changed && samplers) ARBSamplerObjects.glBindSampler(0, 0);
    }

    @Override
    public void close() {
        if (!changed) return;
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
        try {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, maxLevel);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, minLod);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, maxLod);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, bias);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, min);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, mag);
            if (anisotropic)
                GL11.glTexParameterf(
                        GL11.GL_TEXTURE_2D,
                        EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT,
                        anisotropy);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
            if (samplers) ARBSamplerObjects.glBindSampler(0, sampler);
        }
    }
}
