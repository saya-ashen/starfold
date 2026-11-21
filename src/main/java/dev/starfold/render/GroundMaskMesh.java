package dev.starfold.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Original anomaly vertices, staged into the same face arena as opaque terrain. */
final class GroundMaskMesh implements AutoCloseable {
    static final int TAG = 255;
    private ByteBuffer source, packed;
    private int cursor;
    private GroundFaceArenas.Upload upload;
    private GroundFaceArenas.Slot slot;

    static boolean fits(MeshData data) {
        var raw = data.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        if (raw.remaining() != data.drawState().vertexCount() * 32) return false;
        for (int i = 0; i < data.drawState().vertexCount(); i++) {
            float x = raw.getFloat(i * 32), y = raw.getFloat(i * 32 + 4), z = raw.getFloat(i * 32 + 8);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || x < -1000 || x > 3048 || z < -1000 || z > 3048
                    || y < -2048 || y >= 2048) return false;
        }
        return true;
    }

    GroundMaskMesh(MeshData data) {
        // The caller's builder is about to close; retain only an independent bounded CPU copy.
        try (data) {
            var vertices = data.vertexBuffer();
            source = ByteBuffer.allocateDirect(vertices.remaining()).order(ByteOrder.nativeOrder());
            source.put(vertices.duplicate()).flip();
            packed = ByteBuffer.allocateDirect(data.drawState().vertexCount() * 12)
                    .order(ByteOrder.nativeOrder());
        }
    }

    void prepare(GroundFaceArenas arenas, int face, GroundUploadBudget budget) {
        if (slot != null) return;
        RenderSystem.assertOnRenderThread();
        if (source != null) {
            int count = packed.capacity() / 12;
            while (cursor < count) {
                if (!budget.cpuAvailable() || budget.takePackingVertices(4) != 4) return;
                for (int q = 0; q < 4; q++, cursor++) {
                    int at = cursor * 32;
                    int material = GroundCompact.registerMaterial(
                            source.getFloat(at + 16), source.getFloat(at + 20),
                            source.getInt(at + 24), source.getInt(at + 12) & 0xffffff,
                            source.getInt(at + 28) & 0xffffff, -1, 0);
                    int uv2 = source.getInt(at + 24);
                    int light = (uv2 & 255) | (uv2 >>> 16 & 255) << 8;
                    GroundCompact.put(MemoryUtil.memAddress(packed) + cursor * 12L,
                            source.getFloat(at), source.getFloat(at + 4), source.getFloat(at + 8),
                            material, TAG, light);
                }
            }
            source = null;
            upload = arenas.beginUpload(face, RenderType.solid(), packed);
        }
        if (upload.step(budget)) {
            slot = upload.finish();
            upload = null;
            packed = null;
        }
    }

    GroundFaceArenas.Slot slot() {
        return slot;
    }

    int indices() {
        return slot == null ? 0 : slot.vertices() / 4 * 6;
    }

    @Override
    public void close() {
        if (upload != null) upload.close();
        if (slot != null) slot.close();
        upload = null;
        slot = null;
        source = packed = null;
    }
}
