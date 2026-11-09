package dev.starfold.atlas;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/** A Screen-local 3D pass; restore the engine projection without applying its depth hook twice. */
final class MapRender {
    static void draw(GuiGraphics g, MapCamera camera, int x, int y, int width, int height,
                     double near, double far, Runnable geometry) {
        g.flush();
        var savedShader = RenderSystem.getShader();
        int texture = RenderSystem.getShaderTexture(0);
        float[] color = RenderSystem.getShaderColor().clone();
        int[] viewport = new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                blend = GL11.glIsEnabled(GL11.GL_BLEND), write = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        var modelView = RenderSystem.getModelViewStack();
        RenderSystem.backupProjectionMatrix();
        modelView.pushMatrix();
        try {
            var window = Minecraft.getInstance().getWindow();
            double scale = window.getGuiScale();
            RenderSystem.viewport((int) (x * scale), window.getHeight() - (int) ((y + height) * scale),
                    (int) (width * scale), (int) (height * scale));
            modelView.identity(); RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(camera.projection(near, far), VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.enableDepthTest(); RenderSystem.depthMask(true);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.disableCull(); RenderSystem.disableBlend(); RenderSystem.setShaderColor(1, 1, 1, 1);
            geometry.run();
        } finally {
            modelView.popMatrix(); RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.depthMask(write);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.setShaderTexture(0, texture);
            RenderSystem.setShader(() -> savedShader);
        }
    }
    private MapRender() {}
}
