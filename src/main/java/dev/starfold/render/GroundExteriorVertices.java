package dev.starfold.render;

import com.mojang.blaze3d.vertex.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.PlanetSpace;
import org.lwjgl.system.MemoryUtil;
import java.util.List;

/** Body-local eighth-block positions, RGBA and octahedral normal: 12 bytes, indexed quads. */
final class GroundExteriorVertices implements AutoCloseable {
    static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.register(VertexFormatElement.findNextId(), 0,
                    VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.POSITION, 3))
            .add("Color", VertexFormatElement.COLOR)
            .add("Normal", VertexFormatElement.register(VertexFormatElement.findNextId(), 0,
                    VertexFormatElement.Type.BYTE, VertexFormatElement.Usage.NORMAL, 2)).build();
    final ByteBufferBuilder memory = new ByteBufferBuilder(65536);
    int count;
    private double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX, maxX = -minX, maxY = -minX, maxZ = -minX;

    /** Body-local bounding sphere {x, y, z, radius} of every vertex written; null when empty. */
    float[] sphere() {
        if (count == 0) return null;
        double dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        // Eighth-block rounding moves a vertex by at most 1/16 per axis.
        return new float[] {(float) ((minX + maxX) / 2), (float) ((minY + maxY) / 2), (float) ((minZ + maxZ) / 2),
                (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) / 2 + 1)};
    }

    void quad(List<Point> points, int rgb, int alpha, boolean radial) {
        var normal = PlanetSpace.unit(points.get(1).sub(points.getFirst()).cross(points.get(2).sub(points.getFirst())));
        for (var p : points) vertex(p, radial ? PlanetSpace.unit(p) : normal, rgb, alpha);
    }

    // 128..137: class in bits 1..3, fade generation in bit 0; 0 stays self-lit.
    static int surfaceTag(int surfaceClass, boolean old) { return 128 + surfaceClass * 2 + (old ? 1 : 0); }

    void glow(Point[] t, int rgb) {
        quad(List.of(t[0], t[1], t[2], t[2]), rgb, 0, true);
    }

    private void vertex(Point p, Point normal, int rgb, int alpha) {
        minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
        minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
        minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
        long target = memory.reserve(12);
        MemoryUtil.memPutShort(target, (short)Math.round(p.x() * 8));
        MemoryUtil.memPutShort(target + 2, (short)Math.round(p.y() * 8));
        MemoryUtil.memPutShort(target + 4, (short)Math.round(p.z() * 8));
        MemoryUtil.memPutByte(target + 6, (byte)(rgb >> 16));
        MemoryUtil.memPutByte(target + 7, (byte)(rgb >> 8));
        MemoryUtil.memPutByte(target + 8, (byte)rgb);
        MemoryUtil.memPutByte(target + 9, (byte)alpha);
        int packed = oct(normal);
        MemoryUtil.memPutShort(target + 10, (short)packed);
        count++;
    }

    static int oct(Point n) {
        double length = Math.abs(n.x()) + Math.abs(n.y()) + Math.abs(n.z());
        double x = n.x() / length, y = n.y() / length;
        if (n.z() < 0) {
            double old = x;
            x = (1 - Math.abs(y)) * (x >= 0 ? 1 : -1);
            y = (1 - Math.abs(old)) * (y >= 0 ? 1 : -1);
        }
        return (int)Math.round(x * 127) & 255 | ((int)Math.round(y * 127) & 255) << 8;
    }

    MeshData build() {
        return new MeshData(memory.build(), new MeshData.DrawState(FORMAT, count, count / 4 * 6,
                VertexFormat.Mode.QUADS, VertexFormat.IndexType.least(count)));
    }

    public void close() { memory.close(); }
}
