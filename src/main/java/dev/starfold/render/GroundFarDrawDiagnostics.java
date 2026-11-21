package dev.starfold.render;

import dev.starfold.api.FarViewDiagnostics;
import dev.starfold.api.FarViewDiagnostics.Watch;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import org.lwjgl.opengl.*;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Render-thread-only, one-shot evidence. Never changes a draw decision or shader value. */
public final class GroundFarDrawDiagnostics {
    enum Source {
        CHART,
        PARENT,
        STITCH,
        RESIDENT_SETTLED,
        RESIDENT_CURRENT,
        RESIDENT_PREVIOUS
    }

    private static final int SOURCES = Source.values().length;

    // Five chunk layers, six sources, two actual stages. Only the watch's source face qualifies.
    static final class Gate {
        static final int SLOTS = 2 * 5 * SOURCES;
        private long epoch;
        private final BitSet captured = new BitSet(SLOTS);

        boolean claim(long current, int slot) {
            if (current == 0 || slot < 0 || slot >= SLOTS) return false;
            if (epoch != current) {
                epoch = current;
                captured.clear();
            }
            if (captured.get(slot)) return false;
            captured.set(slot);
            return true;
        }
    }

    private record Pending(
            Watch watch,
            ShaderInstance shader,
            Source source,
            int layer,
            boolean translucent,
            String path) {}

    private static final Gate GATE = new Gate();
    private static Pending pending;
    private static boolean translucent;

    static void pass(boolean value) {
        translucent = value;
    }

    /** Called only for a mesh/batch known to include the center tile's patch or resident range. */
    static void begin(
            ShaderInstance shader, int face, RenderType layer, Source source, String path) {
        pending = null;
        var w = FarViewDiagnostics.currentWatch(GroundRenderer.diagnosticEpoch());
        if (w == null || face != w.face()) return;
        int index = RenderType.chunkBufferLayers().indexOf(layer);
        if (index < 0 || index >= 5) return;
        int slot = ((translucent ? 5 : 0) + index) * SOURCES + source.ordinal();
        if (!GATE.claim(w.epoch(), slot)) return;
        pending = new Pending(w, shader, source, index, translucent, path);
    }

    static void end() {
        pending = null;
    }

    /**
     * At the end of our whole-apply wrapper: includes vanilla and custom uniform/sampler writes.
     */
    public static void applied(ShaderInstance shader) {
        var p = pending;
        if (p == null || p.shader() != shader) return;
        pending = null; // Consume even an unsuccessful observation; never poll GL each frame.
        if (FarViewDiagnostics.currentWatch(p.watch().epoch()) == null) return;
        var fields = new LinkedHashMap<String, Object>();
        fields.put("renderStage", p.translucent() ? "AFTER_PARTICLES" : "AFTER_ENTITIES");
        fields.put("pass", p.translucent() ? "translucent" : "opaque");
        fields.put("layer", RenderType.chunkBufferLayers().get(p.layer()).toString());
        fields.put("source", p.source().name());
        fields.put("path", p.path());
        fields.put(
                "scope", "watch-center; patch or face batch inclusion is not primitive visibility");
        fields.put("shaderIdentity", System.identityHashCode(shader));
        fields.put("shaderProgram", shader.getId());
        fields.put("vertexShader", shader.getVertexProgram().getName());
        fields.put("fragmentShader", shader.getFragmentProgram().getName());
        var frame = GroundClient.renderFrame;
        fields.put("sourceFace", frame.source);
        fields.put("frameActive", frame.active);
        fields.put("derived", frame.derived);
        fields.put("coarse", frame.coarse);
        fields.put("pipeline", "quadtree");
        fields.put("cpu", GroundRenderer.diagnosticDrawInputs(p.watch()));
        try {
            fields.put("gpu", readGpu(LWJGL));
        } catch (RuntimeException error) {
            // Diagnostics must not turn an unavailable GL observation into a failed render.
            fields.put("gpu", Map.of("status", "UNOBSERVED", "reason", error.toString()));
        }
        var w = p.watch();
        FarViewDiagnostics.record(
                w.epoch(),
                w.planet(),
                w.face(),
                w.x(),
                w.z(),
                FarViewDiagnostics.DRAW_GPU_STATE,
                fields);
    }

    /**
     * Kept injectable so state restoration and inactive uniforms can be tested without a GL
     * context.
     */
    interface Gpu {
        int integer(int name);

        void activeTexture(int unit);

        int location(int program, String name);

        int uniformInt(int program, int location);

        float uniformFloat(int program, int location);

        boolean samplerObjects();
    }

    private static final Gpu LWJGL =
            new Gpu() {
                public int integer(int name) {
                    return GL11.glGetInteger(name);
                }

                public void activeTexture(int unit) {
                    GL13.glActiveTexture(unit);
                }

                public int location(int program, String name) {
                    return GL20.glGetUniformLocation(program, name);
                }

                public int uniformInt(int program, int location) {
                    return GL20.glGetUniformi(program, location);
                }

                public float uniformFloat(int program, int location) {
                    return GL20.glGetUniformf(program, location);
                }

                public boolean samplerObjects() {
                    return GL.getCapabilities().OpenGL33
                            || GL.getCapabilities().GL_ARB_sampler_objects;
                }
            };

    static Map<String, Object> readGpu(Gpu gl) {
        var result = new LinkedHashMap<String, Object>();
        int program = gl.integer(GL20.GL_CURRENT_PROGRAM);
        result.put("currentProgram", program);
        result.put("textureContents", "UNOBSERVED: no GPU texture readback");
        if (program == 0) {
            result.put("status", "UNOBSERVED: no active program");
            return result;
        }
        var uniforms = new LinkedHashMap<String, Object>();
        for (var name :
                new String[] {
                    "CPNativeActive",
                    "CPNativeTerrain",
                    "CPNativeDerived",
                    "CPNativeCoarse",
                    "CPNativeFace"
                }) {
            int location = gl.location(program, name);
            if (location < 0) uniforms.put(name, "INACTIVE");
            else {
                float value = gl.uniformFloat(program, location);
                uniforms.put(name, Float.isFinite(value) ? value : Float.toString(value));
            }
        }
        result.put("uniforms", uniforms);
        int palette = gl.location(program, "CPMaterials");
        result.put("CPMaterials", palette < 0 ? "INACTIVE" : gl.uniformInt(program, palette));
        int coverage = gl.location(program, GroundFaceArenas.MASK_UNIFORM);
        if (coverage < 0) result.put("coverage", Map.of("status", "INACTIVE"));
        else {
            int unit = gl.uniformInt(program, coverage);
            int max = gl.integer(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
            if (unit < 0 || unit >= max)
                result.put("coverage", Map.of("unit", unit, "status", "INVALID_UNIT"));
            else result.put("coverage", textureBinding(gl, unit));
        }
        return result;
    }

    static Map<String, Object> textureBinding(Gpu gl, int unit) {
        int active = gl.integer(GL13.GL_ACTIVE_TEXTURE);
        try {
            // Direct GL calls: restore actual GL state; do not modify Minecraft's state cache.
            gl.activeTexture(GL13.GL_TEXTURE0 + unit);
            return Map.of(
                    "unit",
                    unit,
                    "texture2D",
                    gl.integer(GL11.GL_TEXTURE_BINDING_2D),
                    "samplerObject",
                    gl.samplerObjects() ? gl.integer(GL33.GL_SAMPLER_BINDING) : "UNAVAILABLE");
        } finally {
            gl.activeTexture(active);
        }
    }

    private GroundFarDrawDiagnostics() {}
}
