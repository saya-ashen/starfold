package dev.starfold.render;

import dev.starfold.chart.GasOcclusion;
import dev.starfold.chart.GroundEphemeris;

import net.minecraft.client.renderer.ShaderInstance;

/** Managed CPU uniform values only; ShaderInstance.apply owns GL program binding/upload. */
final class GasLighting {
    static void initialize(ShaderInstance shader) {
        shader.getUniform("GasCount").set(0);
        shader.getUniform("GasSpheres").set(new float[256]);
    }

    static void apply(
            ShaderInstance shader,
            GroundEphemeris.Model model,
            GroundEphemeris.Frame frame,
            double tick,
            int omitBodyIndex) {
        var spheres = GasOcclusion.relative(model, tick, frame, omitBodyIndex);
        shader.getUniform("GasSpheres").set(GasOcclusion.packed(spheres));
        shader.getUniform("GasCount").set(spheres.size());
    }

    static void clear(ShaderInstance shader) {
        shader.getUniform("GasCount").set(0);
    }

    private GasLighting() {}
}
