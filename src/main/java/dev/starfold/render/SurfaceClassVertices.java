package dev.starfold.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.starfold.api.SurfaceClass;

/** Only section solid/cutout consumers are wrapped; texture alpha remains untouched. */
public record SurfaceClassVertices(VertexConsumer target, int surfaceClass) implements VertexConsumer {
    @Override public VertexConsumer addVertex(float x, float y, float z) { target.addVertex(x,y,z); return this; }
    // Keep BufferBuilder's whole-vertex fast path: section meshing is already the slow half of loading.
    @Override public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
        target.addVertex(x,y,z,color & 0xFFFFFF | SurfaceClass.alpha(surfaceClass) << 24,u,v,overlay,light,nx,ny,nz);
    }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { target.setColor(r,g,b,SurfaceClass.alpha(surfaceClass)); return this; }
    @Override public VertexConsumer setUv(float u, float v) { target.setUv(u,v); return this; }
    @Override public VertexConsumer setUv1(int u, int v) { target.setUv1(u,v); return this; }
    @Override public VertexConsumer setUv2(int u, int v) { target.setUv2(u,v); return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { target.setNormal(x,y,z); return this; }
}
