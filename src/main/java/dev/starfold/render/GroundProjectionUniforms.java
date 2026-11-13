package dev.starfold.render;

import static dev.starfold.render.GroundClient.*;

import dev.starfold.chart.*;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/** Projection uniform writes for the explicitly active ground frame. */
final class GroundProjectionUniforms {
    /** Vanilla shaders hold managed uniforms; Sodium's chunk program only has raw locations. */
    interface Sink {
        void set(String name, float x);

        void set(String name, float x, float y);

        void set(String name, float x, float y, float z);

        void set(String name, float x, float y, float z, float w);

        void setInt(String name, int value);
    }

    public static void apply(ShaderInstance shader) {
        if (!GroundShaderSource.supported(shader.getVertexProgram().getName())) return;
        write(new Managed(shader), shader);
    }

    /** Sodium's chunk program, already bound by its own shader interface. */
    static void applyNative() {
        write(new Program(), null);
    }

    private static void write(Sink out, ShaderInstance shader) {
        boolean sodium = shader == null;
        boolean projected = renderFrame.active && ready();
        out.set("CPNativeActive", projected ? 1 : 0);
        if (!projected) return;
        var e = eye();
        var gap = gap();
        out.set("CPNativeGapCorner", (float) gap.corner.x(), (float) gap.corner.z());
        out.set("CPNativeGapSigns", (float) gap.sx, (float) gap.sz);
        out.set(
                "CPNativeGapLimits",
                (float) gap.strength,
                (float) gap.outer,
                (float) GroundTransition.clip(e.y(), gap.clip, view(), shape().radius()));
        var observer = Face.VALUES[face()];
        if (shader != null)
            dev.starfold.api.PlanetSurfaceRender.nearUniforms(
                    shader,
                    new dev.starfold.api.PlanetSurfaceRender.Context(
                            model(),
                            planet(),
                            time(),
                            observer,
                            GroundViewClient.terrain() == null
                                    ? null
                                    : GroundViewClient.terrain().terrainPlan()));
        var owner = Face.VALUES[sodium || renderFrame.source < 0 ? face() : renderFrame.source];
        var tile = gap.chart.tile(owner);
        set(out, "CPNativeSourcePlot", origin(owner.ordinal()).sub(origin()));
        set(out, "CPAtmosphereGrade", GroundAtmosphere.grade());
        out.set(
                "CPAtmosphereFog",
                GroundAtmosphere.enabled() ? (float) GroundAtmosphere.strength() : 1f);
        set(out, "CPNativeSourceNormal", faceLocal(observer, owner.normal));
        set(out, "CPNativeSourceU", faceLocal(observer, owner.u));
        set(out, "CPNativeSourceV", faceLocal(observer, owner.v));
        var flat = tile == null ? new Point(0, 0, 0) : tile.point(new Point(0, 0, 0));
        set(out, "CPNativeFlatOrigin", flat);
        set(
                out,
                "CPNativeFlatU",
                tile == null ? new Point(1, 0, 0) : tile.point(new Point(1, 0, 0)).sub(flat));
        set(
                out,
                "CPNativeFlatV",
                tile == null ? new Point(0, 0, 1) : tile.point(new Point(0, 0, 1)).sub(flat));
        String name =
                shader == null ? "" : shader.getVertexProgram().getName().replace("minecraft:", "");
        boolean terrain = sodium || GroundCompact.NAME.equals(name) || terrain(name);
        out.set("CPNativeTerrain", terrain ? 1 : 0);
        out.set("CPNativeDerived", !sodium && renderFrame.derived ? 1 : 0);
        out.set("CPNativeCoarse", !sodium && renderFrame.coarse ? 1 : 0);
        var mask = GroundFlightServer.mask(Minecraft.getInstance().level);
        out.set("CPNativeMask", mask.core(), mask.height());
        int corners = GroundRenderer.realCorners(owner.ordinal());
        out.set(
                "CPNativeClosure",
                (corners & 1) != 0 ? 1 : 0,
                (corners & 2) != 0 ? 1 : 0,
                (corners & 4) != 0 ? 1 : 0,
                (corners & 8) != 0 ? 1 : 0);
        out.set("CPNativeLowFrequency", (float) GroundPipeline.lowFrequency(e.y()));
        out.set("CPNativeCover", !sodium && renderFrame.cover ? 1 : 0);
        out.set("CPNativeLodPass", !sodium && renderFrame.lodKey >= 0 ? 1 : 0);
        out.set("CPNativeLodFade", sodium ? 1f : renderFrame.lodFade);
        out.set("CPNativePatchFade", sodium ? 1f : renderFrame.patchFade);
        out.set("CPNativeFace", owner.ordinal());
        // Ground-level ownership is unchanged; altitude dithering requires committed fallback.
        var fade = fade(view());
        out.set("CPNativeFade", fade[0], fade[1], fade[2], fade[3]);
        out.set(
                "CPNativeFadeMix",
                GroundRenderer.selectionReady() && GroundRenderer.covered()
                        ? (float) GroundTransition.fade(e.y())
                        : 0f);
        out.set("CPNativeVolume", GroundRenderer.nativeVolume(owner.ordinal()) ? 1 : 0);
        out.set("CPNativeSelection", GroundRenderer.selectionReady() ? 1 : 0);
        out.set("CPNativeStitch", !sodium && renderFrame.stitch ? 1 : 0);
        if (shader != null
                && (renderFrame.cover
                        || renderFrame.stitch
                        || terrain && GroundRenderer.selectionReady())) {
            // Runs on every shader apply: read Minecraft's tracked unit, a GL query stalls the
            // driver.
            int active = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(
                    org.lwjgl.opengl.GL13.GL_TEXTURE3);
            org.lwjgl.opengl.GL11.glBindTexture(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, GroundRenderer.coverageTexture());
            if (org.lwjgl.opengl.GL.getCapabilities().OpenGL33
                    || org.lwjgl.opengl.GL.getCapabilities().GL_ARB_sampler_objects)
                org.lwjgl.opengl.ARBSamplerObjects.glBindSampler(3, 0);
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(active);
            out.setInt("CPNativeCoverage", 3);
        }
        applyViewUniforms(out, e, gap);
    }

    private static boolean terrain(String name) {
        return name.equals("rendertype_solid")
                || name.equals("rendertype_cutout")
                || name.equals("rendertype_cutout_mipped")
                || name.equals("rendertype_translucent")
                || name.equals("rendertype_translucent_moving_block")
                || name.equals("rendertype_tripwire");
    }

    private static void applyViewUniforms(Sink out, Point e, PlanetGap gap) {
        set(out, "CPNativeEye", e);
        set(out, "CPNativeViewOffset", renderFrame.viewOffset);
        set(out, "CPNativeWorldX", renderFrame.X);
        set(out, "CPNativeWorldY", renderFrame.Y);
        set(out, "CPNativeWorldZ", renderFrame.Z);
        out.set("CPNativeSize", shape().size());
        out.set(
                "CPNativeAirLimits",
                (float) gap.air.weight,
                (float) shape().radius(),
                (float) gap.air.offset.x(),
                (float) gap.air.offset.z());
        set(out, "CPNativeAirNormal", gap.air.normal);
        set(out, "CPNativeAirU", gap.air.basisU);
        set(out, "CPNativeAirV", gap.air.basisV);
        var orbit = orbit();
        var face = Face.VALUES[face()];
        set(out, "CPNativeOrbitNormal", faceLocal(face, orbit.normal));
        set(out, "CPNativeOrbitU", faceLocal(face, orbit.basisU));
        set(out, "CPNativeOrbitV", faceLocal(face, orbit.basisV));
        set(out, "CPNativeOrbitOffset", orbit.offset);
        out.set(
                "CPNativeOrbitLimits",
                (float) orbit.weight,
                (float) shape().radius(),
                (float) GroundTransition.clip(e.y(), orbit.clip, view(), shape().radius()),
                (float) orbit.minimumFacing);
        double range = range(e, orbit, gap);
        out.set("CPNativeRange", (float) (range * .75), (float) range);
        var lens =
                GroundSpaceOptics.lens(
                        com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix(), e.y());
        out.set("CPNativeLens", (float) lens.weight(), (float) lens.x(), (float) lens.y());
    }

    private static Point faceLocal(Face face, Point p) {
        return new Point(p.dot(face.u), p.dot(face.normal), p.dot(face.v));
    }

    private static void set(Sink out, String name, Point p) {
        out.set(name, (float) p.x(), (float) p.y(), (float) p.z());
    }

    private static void set(Sink out, String name, Vector3f p) {
        out.set(name, p.x, p.y, p.z);
    }

    private record Managed(ShaderInstance shader) implements Sink {
        @Override
        public void set(String name, float x) {
            ShaderUniforms.set(shader, name, x);
        }

        @Override
        public void set(String name, float x, float y) {
            ShaderUniforms.set(shader, name, x, y);
        }

        @Override
        public void set(String name, float x, float y, float z) {
            ShaderUniforms.set(shader, name, x, y, z);
        }

        @Override
        public void set(String name, float x, float y, float z, float w) {
            ShaderUniforms.set(shader, name, x, y, z, w);
        }

        @Override
        public void setInt(String name, int value) {
            ShaderUniforms.setInt(shader, name, value);
        }
    }

    /** Sodium links one program per terrain pass; locations belong to the bound program. */
    private static final class Program implements Sink {
        private static final java.util.Map<Integer, java.util.Map<String, Integer>> LOCATIONS =
                new java.util.HashMap<>();
        private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

        private int location(String name) {
            if (program == 0) return -1;
            return LOCATIONS
                    .computeIfAbsent(program, id -> new java.util.HashMap<>())
                    .computeIfAbsent(name, n -> GL20.glGetUniformLocation(program, n));
        }

        @Override
        public void set(String name, float x) {
            int location = location(name);
            if (location >= 0) GL20.glUniform1f(location, x);
        }

        @Override
        public void set(String name, float x, float y) {
            int location = location(name);
            if (location >= 0) GL20.glUniform2f(location, x, y);
        }

        @Override
        public void set(String name, float x, float y, float z) {
            int location = location(name);
            if (location >= 0) GL20.glUniform3f(location, x, y, z);
        }

        @Override
        public void set(String name, float x, float y, float z, float w) {
            int location = location(name);
            if (location >= 0) GL20.glUniform4f(location, x, y, z, w);
        }

        @Override
        public void setInt(String name, int value) {
            int location = location(name);
            if (location >= 0) GL20.glUniform1i(location, value);
        }
    }

    private GroundProjectionUniforms() {}
}
