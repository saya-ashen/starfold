package dev.starfold.render;

import static dev.starfold.render.GroundClient.*;
import dev.starfold.chart.*;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Vector3f;

/** Projection uniform writes for the explicitly active ground frame. */
final class GroundProjectionUniforms {
    public static void apply(ShaderInstance shader) {
        if (!GroundShaderSource.supported(shader.getVertexProgram().getName())) return;
        boolean projected = renderFrame.active && ready();
        ShaderUniforms.set(shader, "CPNativeActive", projected ? 1 : 0);
        if (!projected) return;
        var e = eye();
        var gap = gap();
        ShaderUniforms.set(
                shader, "CPNativeGapCorner", (float) gap.corner.x(), (float) gap.corner.z());
        ShaderUniforms.set(shader, "CPNativeGapSigns", (float) gap.sx, (float) gap.sz);
        ShaderUniforms.set(
                shader,
                "CPNativeGapLimits",
                (float) gap.strength,
                (float) gap.outer,
                (float) GroundTransition.clip(e.y(), gap.clip, view(), shape().radius()));
        var observer = Face.VALUES[face()];
        dev.starfold.api.PlanetSurfaceRender.nearUniforms(shader,
                new dev.starfold.api.PlanetSurfaceRender.Context(model(), planet(), time(), observer,
                        GroundViewClient.terrain()==null?null:GroundViewClient.terrain().terrainPlan()));
        var owner = Face.VALUES[renderFrame.source < 0 ? face() : renderFrame.source];
        var tile = gap.chart.tile(owner);
        set(shader, "CPNativeSourcePlot", origin(owner.ordinal()).sub(origin()));
        set(shader, "CPAtmosphereGrade", GroundAtmosphere.grade());
        ShaderUniforms.set(
                shader,
                "CPAtmosphereFog",
                GroundAtmosphere.enabled() ? (float) GroundAtmosphere.strength() : 1f);
        set(shader, "CPNativeSourceNormal", faceLocal(observer, owner.normal));
        set(shader, "CPNativeSourceU", faceLocal(observer, owner.u));
        set(shader, "CPNativeSourceV", faceLocal(observer, owner.v));
        var flat = tile == null ? new Point(0, 0, 0) : tile.point(new Point(0, 0, 0));
        set(shader, "CPNativeFlatOrigin", flat);
        set(
                shader,
                "CPNativeFlatU",
                tile == null ? new Point(1, 0, 0) : tile.point(new Point(1, 0, 0)).sub(flat));
        set(
                shader,
                "CPNativeFlatV",
                tile == null ? new Point(0, 0, 1) : tile.point(new Point(0, 0, 1)).sub(flat));
        String name = shader.getVertexProgram().getName().replace("minecraft:", "");
        boolean terrain =
                name.equals("rendertype_solid") || name.equals(GroundCompact.NAME)
                        || name.equals("rendertype_cutout")
                        || name.equals("rendertype_cutout_mipped")
                        || name.equals("rendertype_translucent")
                        || name.equals("rendertype_translucent_moving_block")
                        || name.equals("rendertype_tripwire");
        ShaderUniforms.set(shader, "CPNativeTerrain", terrain ? 1 : 0);
        ShaderUniforms.set(shader, "CPNativeDerived", renderFrame.derived ? 1 : 0);
        ShaderUniforms.set(shader, "CPNativeCoarse", renderFrame.coarse ? 1 : 0);
        var mask = GroundFlightServer.mask(Minecraft.getInstance().level);
        ShaderUniforms.set(
                shader,
                "CPNativeMask",
                mask.core(),
                mask.height());
        int corners = GroundRenderer.realCorners(owner.ordinal());
        ShaderUniforms.set(
                shader,
                "CPNativeClosure",
                (corners & 1) != 0 ? 1 : 0,
                (corners & 2) != 0 ? 1 : 0,
                (corners & 4) != 0 ? 1 : 0,
                (corners & 8) != 0 ? 1 : 0);
        ShaderUniforms.set(
                shader, "CPNativeLowFrequency", (float) GroundPipeline.lowFrequency(e.y()));
        ShaderUniforms.set(shader, "CPNativeCover", renderFrame.cover ? 1 : 0);
        ShaderUniforms.set(shader, "CPNativeLodPass", renderFrame.lodKey >= 0 ? 1 : 0);
        ShaderUniforms.set(shader, "CPNativeLodFade", renderFrame.lodFade);
        ShaderUniforms.set(shader, "CPNativePatchFade", renderFrame.patchFade);
        ShaderUniforms.set(shader, "CPNativeFace", owner.ordinal());
        // Ground-level ownership is unchanged; altitude dithering requires committed fallback.
        var fade = fade(view());
        ShaderUniforms.set(shader, "CPNativeFade", fade[0], fade[1], fade[2], fade[3]);
        ShaderUniforms.set(
                shader,
                "CPNativeFadeMix",
                GroundRenderer.selectionReady() && GroundRenderer.covered()
                        ? (float) GroundTransition.fade(e.y()) : 0f);
        ShaderUniforms.set(
                shader, "CPNativeVolume", GroundRenderer.nativeVolume(owner.ordinal()) ? 1 : 0);
        ShaderUniforms.set(shader, "CPNativeSelection", GroundRenderer.selectionReady() ? 1 : 0);
        ShaderUniforms.set(shader, "CPNativeStitch", renderFrame.stitch ? 1 : 0);
        if (renderFrame.cover || renderFrame.stitch || terrain && GroundRenderer.selectionReady()) {
            // Runs on every shader apply: read Minecraft's tracked unit, a GL query stalls the driver.
            int active = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE3);
            org.lwjgl.opengl.GL11.glBindTexture(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, GroundRenderer.coverageTexture());
            if (org.lwjgl.opengl.GL.getCapabilities().OpenGL33
                    || org.lwjgl.opengl.GL.getCapabilities().GL_ARB_sampler_objects)
                org.lwjgl.opengl.ARBSamplerObjects.glBindSampler(3, 0);
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(active);
            ShaderUniforms.setInt(shader, "CPNativeCoverage", 3);
        }
        applyViewUniforms(shader, e, gap);
    }

    private static void applyViewUniforms(ShaderInstance shader, Point e, PlanetGap gap) {
        set(shader, "CPNativeEye", e);
        set(shader, "CPNativeViewOffset", renderFrame.viewOffset);
        set(shader, "CPNativeWorldX", renderFrame.X);
        set(shader, "CPNativeWorldY", renderFrame.Y);
        set(shader, "CPNativeWorldZ", renderFrame.Z);
        ShaderUniforms.set(shader, "CPNativeSize", shape().size());
        ShaderUniforms.set(
                shader,
                "CPNativeAirLimits",
                (float) gap.air.weight,
                (float) shape().radius(),
                (float) gap.air.offset.x(),
                (float) gap.air.offset.z());
        set(shader, "CPNativeAirNormal", gap.air.normal);
        set(shader, "CPNativeAirU", gap.air.basisU);
        set(shader, "CPNativeAirV", gap.air.basisV);
        var orbit = orbit();
        var face = Face.VALUES[face()];
        set(shader, "CPNativeOrbitNormal", faceLocal(face, orbit.normal));
        set(shader, "CPNativeOrbitU", faceLocal(face, orbit.basisU));
        set(shader, "CPNativeOrbitV", faceLocal(face, orbit.basisV));
        set(shader, "CPNativeOrbitOffset", orbit.offset);
        ShaderUniforms.set(
                shader,
                "CPNativeOrbitLimits",
                (float) orbit.weight,
                (float) shape().radius(),
                (float) GroundTransition.clip(e.y(), orbit.clip, view(), shape().radius()),
                (float) orbit.minimumFacing);
        double range = range(e, orbit, gap);
        ShaderUniforms.set(shader, "CPNativeRange", (float) (range * .75), (float) range);
        var lens =
                GroundSpaceOptics.lens(
                        com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix(), e.y());
        ShaderUniforms.set(
                shader, "CPNativeLens", (float) lens.weight(), (float) lens.x(), (float) lens.y());
    }

    private static Point faceLocal(Face face, Point p) {
        return new Point(p.dot(face.u), p.dot(face.normal), p.dot(face.v));
    }

    private static void set(ShaderInstance s, String n, Point p) {
        ShaderUniforms.set(s, n, (float) p.x(), (float) p.y(), (float) p.z());
    }

    private static void set(ShaderInstance s, String n, Vector3f p) {
        ShaderUniforms.set(s, n, p.x, p.y, p.z);
    }

    private GroundProjectionUniforms() {}
}
