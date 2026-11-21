package dev.starfold.render;

import com.mojang.blaze3d.vertex.*;

import dev.starfold.geometry.BentStripGeometry.Point;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.util.*;

/** Twelve-byte chart vertices; the shared palette holds sprite bounds, tint and albedo. */
final class GroundCompact {
    static final String NAME = "starfold:ground_compact";
    static final VertexFormat FORMAT =
            VertexFormat.builder()
                    .add(
                            "PackedPosition",
                            VertexFormatElement.register(
                                    VertexFormatElement.findNextId(),
                                    0,
                                    VertexFormatElement.Type.USHORT,
                                    VertexFormatElement.Usage.POSITION,
                                    3))
                    .add(
                            "Packed",
                            VertexFormatElement.register(
                                    VertexFormatElement.findNextId(),
                                    0,
                                    VertexFormatElement.Type.USHORT,
                                    VertexFormatElement.Usage.UV,
                                    3))
                    .build();

    record Material(
            float u, float v, int bounds, int tint, int normal, float alpha, float heightOffset) {}

    private static final Map<Material, Integer> ids = new HashMap<>();
    private static final List<Material> materials = new ArrayList<>();

    enum Variant {
        SOLID("solid"),
        CUTOUT("cutout"),
        TRANSLUCENT("translucent"),
        MASKED("masked");
        final String name;

        Variant(String suffix) {
            name = NAME + "_" + suffix;
        }
    }

    private static final EnumMap<Variant, ShaderInstance> shaders = new EnumMap<>(Variant.class);
    private static final Palette palette = new Palette(new GlPalette());

    static void initialize(net.neoforged.bus.api.IEventBus bus) {
        bus.addListener(
                (net.neoforged.neoforge.client.event.RegisterShadersEvent event) -> {
                    try {
                        clear();
                        for (Variant variant : Variant.values()) {
                            event.registerShader(
                                    new ShaderInstance(
                                            event.getResourceProvider(),
                                            ResourceLocation.parse(variant.name),
                                            FORMAT) {
                                        private com.mojang.blaze3d.shaders.Program
                                                appearanceFragment;

                                        @Override
                                        public com.mojang.blaze3d.shaders.Program
                                                getFragmentProgram() {
                                            var actual = super.getFragmentProgram();
                                            if (actual == null) return null;
                                            if (appearanceFragment == null)
                                                appearanceFragment = appearanceFragment(actual);
                                            return appearanceFragment;
                                        }
                                    },
                                    shader -> shaders.put(variant, shader));
                        }
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
    }

    /** Existing appearance providers also inspect the runtime fragment program name for uniforms.
     * Keep compiled resources distinct and delegate GL lifetime to the actual variant program. */
    static com.mojang.blaze3d.shaders.Program appearanceFragment(
            com.mojang.blaze3d.shaders.Program actual) {
        return new com.mojang.blaze3d.shaders.Program(
                com.mojang.blaze3d.shaders.Program.Type.FRAGMENT, -1, NAME) {
            @Override
            public void attachToShader(com.mojang.blaze3d.shaders.Shader shader) {
                actual.attachToShader(shader);
            }

            @Override
            public void close() {
                actual.close();
            }
        };
    }

    static void clear() {
        ids.clear();
        materials.clear();
        palette.close();
    }

    static long gpuBytes() {
        return palette.bytes();
    }

    /** Peak of published and replacement storage, including the next growth admission. */
    static long requiredGpuBytes() {
        return palette.requiredBytes(materials.size());
    }

    static boolean materialsReady(int requiredCount) {
        return palette.ready(requiredCount);
    }

    /** CPU registration is separate; only this explicit entry may transfer palette data. */
    static boolean prepareMaterials(GroundUploadBudget budget, long gpuAllowance) {
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
        return palette.prepare(materials, budget, gpuAllowance);
    }

    static Variant variant(net.minecraft.client.renderer.RenderType layer, boolean masked) {
        if (masked) return Variant.MASKED;
        int index = GroundFaceArenas.layerIndex(layer);
        return index == 0 ? Variant.SOLID : index == 3 ? Variant.TRANSLUCENT : Variant.CUTOUT;
    }

    static ShaderInstance shader() {
        return shader(net.minecraft.client.renderer.RenderType.solid(), false);
    }

    static ShaderInstance shader(net.minecraft.client.renderer.RenderType layer, boolean masked) {
        ShaderInstance shader = shaders.get(variant(layer, masked));
        if (shader == null) throw new IllegalStateException("Compact shaders not registered");
        if (!palette.ready(1)) throw new IllegalStateException("Compact palette not prepared");
        shader.setSampler("CPMaterials", palette.texture);
        return shader;
    }

    /** GL seam also exercises the production admission/publication state machine offline. */
    interface PaletteGpu {
        int create(int height);

        void upload(int texture, int texel, java.nio.FloatBuffer data);

        void delete(int texture);
    }

    static final class Palette implements AutoCloseable {
        private final PaletteGpu gpu;
        private int texture, height, uploaded;
        private int pendingTexture, pendingHeight, pendingCount, cursor;

        Palette(PaletteGpu gpu) {
            this.gpu = gpu;
        }

        private static int heightFor(int count) {
            int rows = Math.max(1, (count * 3 + 255) / 256);
            return Math.max(1, Integer.highestOneBit(rows - 1) * 2);
        }

        private static int storage(int height) {
            return height * 256 * 16;
        }

        long bytes() {
            return (long) storage(height) + storage(pendingHeight);
        }

        long requiredBytes(int count) {
            if (pendingTexture != 0 || (texture != 0 && count * 3 <= height * 256)) return bytes();
            return bytes() + storage(heightFor(count));
        }

        boolean ready(int count) {
            return count >= 0 && count <= uploaded;
        }

        boolean prepare(List<Material> source, GroundUploadBudget budget, long allowance) {
            if (source.isEmpty()) return true;
            if (pendingTexture == 0 && (texture == 0 || source.size() * 3 > height * 256)) {
                int nextHeight = heightFor(source.size()), cost = storage(nextHeight);
                // Null-data storage allocation consumes GPU memory, not upload bytes (as with
                // arena VBO storage). Charge its actual call time; charge all initialized texels
                // below. Requiring capacity to fit the upload quota would deadlock growth >4 MiB.
                if (bytes() + cost > allowance || !budget.available()) return false;
                long started = budget.startTransfer();
                try {
                    pendingTexture = gpu.create(nextHeight);
                    pendingHeight = nextHeight;
                    pendingCount = source.size();
                    cursor = 0;
                } finally {
                    budget.endTransfer(started);
                }
            }
            boolean growing = pendingTexture != 0;
            int target = growing ? pendingCount : source.size();
            int next = cursor;
            while (next < target * 3) {
                // One row at a time; a material may straddle rows. Publish only complete triples.
                int texels = Math.min(target * 3 - next, 256 - next % 256);
                if (!budget.cpuAvailable()) return false;
                var data = MemoryUtil.memAllocFloat(texels * 4);
                try {
                    for (int i = next; i < next + texels; i++)
                        putMaterialTexel(data, source.get(i / 3), i % 3);
                    data.flip();
                    int admitted = budget.take(texels * 16, 16) / 16;
                    if (admitted == 0) return false;
                    data.limit(admitted * 4);
                    long started = budget.startTransfer();
                    try {
                        gpu.upload(growing ? pendingTexture : texture, next, data);
                    } finally {
                        budget.endTransfer(started);
                    }
                    next += admitted;
                    cursor = next;
                    if (!growing) uploaded = next / 3;
                } finally {
                    MemoryUtil.memFree(data);
                }
            }
            if (growing) {
                int old = texture;
                texture = pendingTexture;
                height = pendingHeight;
                uploaded = pendingCount;
                pendingTexture = pendingHeight = pendingCount = 0;
                cursor = uploaded * 3;
                if (old != 0) gpu.delete(old);
            }
            return uploaded == source.size();
        }

        public void close() {
            if (pendingTexture != 0) gpu.delete(pendingTexture);
            if (texture != 0) gpu.delete(texture);
            texture =
                    height = uploaded = pendingTexture = pendingHeight = pendingCount = cursor = 0;
        }
    }

    private static void putMaterialTexel(java.nio.FloatBuffer data, Material m, int texel) {
        switch (texel) {
            case 0 ->
                    data.put(m.u)
                            .put(m.v)
                            .put((m.bounds & 65535) / 65535f)
                            .put((m.bounds >>> 16) / 65535f);
            case 1 ->
                    data.put((m.tint & 255) / 255f)
                            .put((m.tint >> 8 & 255) / 255f)
                            .put((m.tint >> 16 & 255) / 255f)
                            .put(m.heightOffset);
            case 2 ->
                    data.put((byte) m.normal / 127f)
                            .put((byte) (m.normal >> 8) / 127f)
                            .put((byte) (m.normal >> 16) / 127f)
                            .put(m.alpha);
            default -> throw new AssertionError(texel);
        }
    }

    private static final class GlPalette implements PaletteGpu {
        public int create(int height) {
            if (height > GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE))
                throw new IllegalStateException("Compact palette exceeds GPU texture height");
            int texture = GL11.glGenTextures();
            try {
                withUnpack(
                        texture,
                        () -> {
                            GL11.glTexImage2D(
                                    GL11.GL_TEXTURE_2D,
                                    0,
                                    GL30.GL_RGBA32F,
                                    256,
                                    height,
                                    0,
                                    GL11.GL_RGBA,
                                    GL11.GL_FLOAT,
                                    0L);
                            GL11.glTexParameteri(
                                    GL11.GL_TEXTURE_2D,
                                    GL11.GL_TEXTURE_MIN_FILTER,
                                    GL11.GL_NEAREST);
                            GL11.glTexParameteri(
                                    GL11.GL_TEXTURE_2D,
                                    GL11.GL_TEXTURE_MAG_FILTER,
                                    GL11.GL_NEAREST);
                            if (GL11.glGetTexLevelParameteri(
                                            GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
                                    != height)
                                throw new IllegalStateException(
                                        "Compact palette allocation failed");
                        });
                return texture;
            } catch (RuntimeException | Error failure) {
                GL11.glDeleteTextures(texture);
                throw failure;
            }
        }

        public void upload(int texture, int texel, java.nio.FloatBuffer data) {
            withUnpack(
                    texture,
                    () ->
                            GL11.glTexSubImage2D(
                                    GL11.GL_TEXTURE_2D,
                                    0,
                                    texel % 256,
                                    texel / 256,
                                    data.remaining() / 4,
                                    1,
                                    GL11.GL_RGBA,
                                    GL11.GL_FLOAT,
                                    data));
        }

        public void delete(int texture) {
            GL11.glDeleteTextures(texture);
        }

        private static void withUnpack(int texture, Runnable action) {
            int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int[] unpack = {
                GL11.GL_UNPACK_ALIGNMENT,
                GL11.GL_UNPACK_ROW_LENGTH,
                GL11.GL_UNPACK_SKIP_ROWS,
                GL11.GL_UNPACK_SKIP_PIXELS
            };
            int[] saved = new int[unpack.length];
            for (int i = 0; i < unpack.length; i++) saved[i] = GL11.glGetInteger(unpack[i]);
            int buffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                for (int i = 0; i < unpack.length; i++)
                    GL11.glPixelStorei(unpack[i], i == 0 ? 4 : 0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                action.run();
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
                for (int i = 0; i < unpack.length; i++) GL11.glPixelStorei(unpack[i], saved[i]);
            }
        }
    }

    /** Reusable render-thread entry for staged mesher material resolution. No GL upload occurs. */
    static int registerMaterial(Material material) {
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
        return materialId(Objects.requireNonNull(material));
    }

    static int registerMaterial(
            float u, float v, int bounds, int tint, int normal, float alpha, float heightOffset) {
        return registerMaterial(new Material(u, v, bounds, tint, normal, alpha, heightOffset));
    }

    private static int materialId(Material material) {
        Integer existing = ids.get(material);
        if (existing != null) return existing;
        if (materials.size() >= 0x1000000)
            throw new IllegalStateException("Compact material palette exceeds 24-bit ids");
        int id = materials.size();
        materials.add(material);
        ids.put(material, id);
        return id;
    }

    static MeshData pack(
            MeshData source,
            ByteBufferBuilder memory,
            net.minecraft.client.renderer.RenderType layer,
            int face) {
        var packing = new Packing(source, memory, layer, face);
        while (!packing.step(new GroundUploadBudget(Long.MAX_VALUE))) {}
        return packing.finish();
    }

    /** Source-compatible overload for offline callers; the key has no packing semantics. */
    static MeshData pack(
            MeshData source,
            ByteBufferBuilder memory,
            net.minecraft.client.renderer.RenderType layer,
            int face,
            long ignoredLodKey) {
        return pack(source, memory, layer, face);
    }

    /** Material registration and live light reads stay on the render thread, in bounded slices. */
    static final class Packing implements AutoCloseable {
        private final MeshData source;
        private final ByteBufferBuilder memory;
        private final net.minecraft.client.renderer.RenderType layer;
        private final int face, count;
        private final long target;
        private final java.util.function.ToIntFunction<BlockPos> lightSampler;
        private boolean assertRenderThread;
        private final GroundFarDiagnostics.Collector diagnostics;
        private int cursor;
        private boolean released;

        Packing(
                MeshData source,
                ByteBufferBuilder memory,
                net.minecraft.client.renderer.RenderType layer,
                int face) {
            this(source, memory, layer, face, pos -> GroundRenderer.surfaceLight(face, pos));
            assertRenderThread = true;
        }

        /** The old key argument is ignored; all positions are face-local. */
        Packing(
                MeshData source,
                ByteBufferBuilder memory,
                net.minecraft.client.renderer.RenderType layer,
                int face,
                long ignoredLodKey) {
            this(source, memory, layer, face);
        }

        /** Source-compatible offline seam; no key or chart metadata is retained. */
        Packing(
                MeshData source,
                ByteBufferBuilder memory,
                net.minecraft.client.renderer.RenderType layer,
                int face,
                long ignoredLodKey,
                java.util.function.ToIntFunction<BlockPos> lightSampler) {
            this(source, memory, layer, face, lightSampler);
        }

        /** Injected live-light seam used by offline packing tests; production asserts render thread. */
        Packing(
                MeshData source,
                ByteBufferBuilder memory,
                net.minecraft.client.renderer.RenderType layer,
                int face,
                java.util.function.ToIntFunction<BlockPos> lightSampler) {
            this.source = source;
            this.memory = memory;
            this.layer = layer;
            this.face = face;
            this.lightSampler = lightSampler;
            diagnostics = GroundFarDiagnostics.captureCurrent();
            count = source.drawState().vertexCount();
            target = memory.reserve(count * 12);
        }

        boolean step(GroundUploadBudget budget) {
            if (assertRenderThread) com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
            var data = source.vertexBuffer();
            // A step may resume in another frame: never reuse live light from an earlier step.
            var lights = new HashMap<BlockPos, Integer>();
            while (cursor < count) {
                int vertices = budget.takePackingVertices(count - cursor);
                if (vertices == 0) return false;
                int end = cursor + vertices;
                while (cursor < end) {
                    if (!budget.cpuAvailable()) return false;
                    var sampledPosition = lightPosition(face, data, cursor * 32);
                    int light =
                            lights.computeIfAbsent(
                                    sampledPosition, pos -> lightSampler.applyAsInt(pos));
                    for (int q = 0; q < 4; q++, cursor++) {
                        int p = cursor * 32;
                        var material =
                                new Material(
                                        data.getFloat(p + 16),
                                        data.getFloat(p + 20),
                                        data.getInt(p + 24),
                                        data.getInt(p + 12) & 0xffffff,
                                        data.getInt(p + 28) & 0xffffff,
                                        layer
                                                        == net.minecraft.client.renderer.RenderType
                                                                .translucent()
                                                ? .7f
                                                : -1f,
                                        waterHeightOffset(
                                                data.getFloat(p + 4),
                                                data.getFloat(p + 20),
                                                layer
                                                        == net.minecraft.client.renderer.RenderType
                                                                .translucent()));
                        int id = materialId(material);
                        double x = data.getFloat(p),
                                y = data.getFloat(p + 4),
                                z = data.getFloat(p + 8);
                        put(
                                target + cursor * 12L,
                                x,
                                y,
                                z,
                                id,
                                Byte.toUnsignedInt(data.get(p + 15)),
                                light);
                    }
                    if (diagnostics != null)
                        diagnostics.packed(
                                face,
                                layer,
                                -1,
                                data,
                                (cursor - 4) * 32,
                                target + (cursor - 4) * 12L,
                                sampledPosition,
                                light);
                }
            }
            return cursor == count;
        }

        MeshData finish() {
            if (cursor != count) throw new IllegalStateException("Incomplete packed mesh");
            close();
            return new MeshData(
                    memory.build(),
                    new MeshData.DrawState(
                            FORMAT,
                            count,
                            count / 4 * 6,
                            VertexFormat.Mode.QUADS,
                            VertexFormat.IndexType.least(count)));
        }

        public void close() {
            if (!released) {
                released = true;
                if (diagnostics != null) diagnostics.close("PACKED_CPU", cursor == count);
                source.close();
            }
        }
    }

    static BlockPos lightPosition(int face, java.nio.ByteBuffer vertices, int start) {
        double x = 0, y = 0, z = 0, top = -Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            int p = start + i * 32;
            x += vertices.getFloat(p);
            y += vertices.getFloat(p + 4);
            z += vertices.getFloat(p + 8);
            top = Math.max(top, vertices.getFloat(p + 4));
        }
        var a =
                new Point(
                        vertices.getFloat(start),
                        vertices.getFloat(start + 4),
                        vertices.getFloat(start + 8));
        var b =
                new Point(
                        vertices.getFloat(start + 32),
                        vertices.getFloat(start + 36),
                        vertices.getFloat(start + 40));
        var c =
                new Point(
                        vertices.getFloat(start + 64),
                        vertices.getFloat(start + 68),
                        vertices.getFloat(start + 72));
        var normal = b.sub(a).cross(c.sub(a));
        // Closure walls extend underground; light their exposed rim, not their buried centre.
        return BlockPos.containing(
                face * 4096 + x / 4 + Math.signum(normal.x()) * .01,
                normal.y() == 0
                        ? Math.ceil(128 + top)
                        : normal.y() > 0 ? Math.ceil(128 + y / 4) : 128 + y / 4 - .01,
                z / 4 + Math.signum(normal.z()) * .01);
    }

    /** The palette's otherwise unused tint alpha carries sub-sixteenth water Y precision. */
    static float heightOffset(int material) {
        return materials.get(material).heightOffset;
    }

    static float waterHeightOffset(double y, float v, boolean translucent) {
        return GroundLodPacking.waterHeightOffset(y, v, translucent);
    }

    static void put(long p, double x, double y, double z, int material, int tag, int light) {
        MemoryUtil.memPutShort(p, (short) Math.round(x * 16));
        MemoryUtil.memPutShort(p + 2, (short) Math.round((y + 2048) * 16));
        MemoryUtil.memPutShort(p + 4, (short) Math.round(z * 16));
        MemoryUtil.memPutShort(p + 6, (short) material);
        MemoryUtil.memPutShort(p + 8, (short) (tag | (material >>> 16) << 8));
        MemoryUtil.memPutShort(p + 10, (short) light);
    }

    /** Decode standard face coordinates before the shared projection, lighting and mip wrapper. */
    static String unpack(String source) {
        return source.replace(
                        "cpNativeTextureAlpha=1.0;",
                        "cpNativeTextureAlpha=cpMaterial((int(Packed.x)+int(Packed.y>>8u)*65536)*3+2).w;")
                .replace("in vec3 Position;", "in vec3 PackedPosition;\nvec3 Position;")
                .replace("in vec4 Color;", "in uvec3 Packed;\nvec4 Color;")
                .replace("in vec2 UV0;", "vec2 UV0;")
                .replace("in ivec2 UV2;", "ivec2 UV2;")
                .replace("in vec3 Normal;", "vec3 Normal;")
                .replace(
                        "vec4 cpNativeModelView;",
                        """
                        uniform sampler2D CPMaterials;
                        vec4 cpMaterial(int i){return texelFetch(CPMaterials,ivec2(i%256,i/256),0);}
                        vec4 cpNativeModelView;
                        """)
                .replace(
                        "cpNativePackedBounds=UV2;cpNativeLightUV2=UV2;",
                        """
                        Position=PackedPosition/16.0-vec3(0,2048,0);
                        if((Packed.y&255u)==255u){
                            if(Position.x>3072.0)Position.x-=4096.0;
                            if(Position.z>3072.0)Position.z-=4096.0;
                        }
                        int material=(int(Packed.x)+int(Packed.y>>8u)*65536)*3;
                        vec4 bounds=cpMaterial(material);
                        Position.y+=cpMaterial(material+1).w;
                        Color=vec4(cpMaterial(material+1).rgb,float(Packed.y&255u)/255.0);
                        UV0=bounds.xy;UV2=ivec2(round(bounds.zw*65535.0));Normal=cpMaterial(material+2).xyz;
                        cpNativePackedBounds=UV2;
                        cpNativeLightUV2=ivec2(int(Packed.z)&255,int(Packed.z)>>8);
                        """);
    }

    private GroundCompact() {}
}
