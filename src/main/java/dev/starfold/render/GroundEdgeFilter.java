package dev.starfold.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/** Sample native and derived geometric edges before their coverage is lost. */
public final class GroundEdgeFilter implements AutoCloseable {
    private static final int SAMPLES = 4;
    private static int framebuffer, color, depth, width, height, colorFormat, depthFormat;
    private final int draw, read;
    private final boolean multisample;
    private final boolean active;

    static GroundEdgeFilter open(boolean translucent) {
        return new GroundEdgeFilter(translucent);
    }

    private static GroundEdgeFilter nativeEdges;

    public static void beginNative(net.minecraft.client.renderer.RenderType layer) {
        if (layer != net.minecraft.client.renderer.RenderType.solid()) return;
        endNative();
        if (GroundClient.projectedFrame()) nativeEdges = open(false);
    }

    public static void endNative() {
        var edges = nativeEdges;
        nativeEdges = null;
        if (edges != null) edges.close();
    }

    private GroundEdgeFilter(boolean translucent) {
        draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        multisample = GL11.glIsEnabled(GL13.GL_MULTISAMPLE);
        // shortcut: only a single color/depth texture target is supported; custom MRT pipelines
        // retain their original framebuffer until all of their attachments can be resolved
        // together.
        if (translucent
                || draw == 0
                || GL11.glGetInteger(GL13.GL_SAMPLES) != 0
                || GL11.glGetInteger(GL11.GL_DRAW_BUFFER) != GL30.GL_COLOR_ATTACHMENT0
                || attachment(GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)
                        != GL11.GL_TEXTURE
                || attachment(GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)
                        != GL11.GL_TEXTURE
                || attachment(
                                GL30.GL_STENCIL_ATTACHMENT,
                                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)
                        != GL11.GL_NONE
                || !singleColor()) {
            active = false;
            return;
        }
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        boolean ready = false;
        try {
            GL11.glBindTexture(
                    GL11.GL_TEXTURE_2D,
                    attachment(
                            GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME));
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            int cf =
                    GL11.glGetTexLevelParameteri(
                            GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            GL11.glBindTexture(
                    GL11.GL_TEXTURE_2D,
                    attachment(
                            GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME));
            int df =
                    GL11.glGetTexLevelParameteri(
                            GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if (w > 0 && h > 0 && GL11.glGetInteger(GL30.GL_MAX_SAMPLES) >= SAMPLES) {
                if (framebuffer == 0
                        || width != w
                        || height != h
                        || colorFormat != cf
                        || depthFormat != df) {
                    clear();
                    framebuffer = GL30.glGenFramebuffers();
                    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
                    color = buffer(w, h, cf, GL30.GL_COLOR_ATTACHMENT0);
                    depth = buffer(w, h, df, GL30.GL_DEPTH_ATTACHMENT);
                    width = w;
                    height = h;
                    colorFormat = cf;
                    depthFormat = df;
                } else GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
                ready =
                        GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)
                                == GL30.GL_FRAMEBUFFER_COMPLETE;
                if (ready) {
                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, draw);
                    blit(
                            0,
                            0,
                            w,
                            h,
                            0,
                            0,
                            w,
                            h,
                            GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT,
                            GL11.GL_NEAREST);
                    GL11.glEnable(GL13.GL_MULTISAMPLE);
                }
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
            if (!ready) GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        }
        active = ready;
    }

    private static void blit(int x0, int y0, int x1, int y1, int a0, int b0, int a1, int b1,
            int mask, int filter) {
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        try {
            GL30.glBlitFramebuffer(x0, y0, x1, y1, a0, b0, a1, b1, mask, filter);
        } finally {
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }

    private static int attachment(int point, int parameter) {
        return GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_DRAW_FRAMEBUFFER, point, parameter);
    }

    private static boolean singleColor() {
        int count = GL11.glGetInteger(GL30.GL_MAX_COLOR_ATTACHMENTS);
        for (int i = 1; i < count; i++)
            if (attachment(
                            GL30.GL_COLOR_ATTACHMENT0 + i,
                            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)
                    != GL11.GL_NONE) return false;
        return true;
    }

    private static int buffer(int w, int h, int format, int attachment) {
        int id = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, id);
        GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, SAMPLES, format, w, h);
        GL30.glFramebufferRenderbuffer(
                GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_RENDERBUFFER, id);
        return id;
    }

    @Override
    public void close() {
        if (!active) return;
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            blit(
                    0,
                    0,
                    width,
                    height,
                    0,
                    0,
                    width,
                    height,
                    GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT,
                    GL11.GL_NEAREST);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            if (!multisample) GL11.glDisable(GL13.GL_MULTISAMPLE);
        }
    }

    static void clear() {
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        if (color != 0) GL30.glDeleteRenderbuffers(color);
        if (depth != 0) GL30.glDeleteRenderbuffers(depth);
        framebuffer = color = depth = 0;
    }
}
