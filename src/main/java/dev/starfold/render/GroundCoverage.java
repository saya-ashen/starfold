package dev.starfold.render;

import org.lwjgl.opengl.*;
import dev.starfold.chart.PlanetShape;
import java.util.*;

/** Owns published coverage, cached atlas bytes, and their GPU texture. */
final class GroundCoverage implements AutoCloseable {
    private int texture;
    GroundOwnership.Snapshot ownership = GroundOwnership.empty();
    private Set<Long> publishedWindow = Set.of();
    private final Map<Set<Long>, byte[]> packedAtlases = new HashMap<>();

    int texture() { return texture; }

    void publish(PlanetShape shape, GroundOwnership.Snapshot next, Set<Long> window,
                 BitSet complete, boolean dirty) {
        boolean sameOwner = ownership.sameAs(next);
        if (!dirty && texture != 0 && sameOwner
                && (publishedWindow == window || publishedWindow.equals(window))) return;
        if (dirty || !sameOwner) packedAtlases.clear();
        ownership = next;
        publishedWindow = window;
        var data = packedAtlases.get(window);
        if (data == null) {
            data = GroundLodAtlas.pack(shape, GroundOwnership.atlas(shape, next, window), complete);
            if (packedAtlases.size() >= 4) packedAtlases.clear();
            packedAtlases.put(window, data);
        }
        upload(data);
    }

    private void upload(byte[] data) {
        var bytes = org.lwjgl.system.MemoryUtil.memAlloc(data.length);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
                alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT),
                rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH),
                skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS),
                skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        try {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            bytes.put(data).flip();
            if (texture == 0) {
                texture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(
                        GL11.GL_TEXTURE_2D,
                        0,
                        GL30.GL_RG8,
                        GroundOwnership.SIDE,
                        GroundOwnership.HEIGHT,
                        0,
                        GL30.GL_RG,
                        GL11.GL_UNSIGNED_BYTE,
                        bytes);
            } else {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glTexSubImage2D(
                        GL11.GL_TEXTURE_2D,
                        0,
                        0,
                        0,
                        GroundOwnership.SIDE,
                        GroundOwnership.HEIGHT,
                        GL30.GL_RG,
                        GL11.GL_UNSIGNED_BYTE,
                        bytes);
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            org.lwjgl.system.MemoryUtil.memFree(bytes);
        }
    }

    @Override public void close() {
        if (texture != 0) GL11.glDeleteTextures(texture);
        texture = 0;
        ownership = GroundOwnership.empty();
        publishedWindow = Set.of();
        packedAtlases.clear();
    }
}
