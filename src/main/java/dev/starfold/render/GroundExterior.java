package dev.starfold.render;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

/**
 * Shader for the existing remote planet meshes; ground quadtree nodes allocate no exterior copy.
 */
public final class GroundExterior {
    private static ShaderInstance shader;

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterShadersEvent c) -> {
                    try {
                        c.registerShader(
                                new ShaderInstance(
                                        c.getResourceProvider(),
                                        ResourceLocation.parse("starfold:native_ground_exterior"),
                                        GroundExteriorVertices.FORMAT),
                                s -> {
                                    shader = s;
                                    GasLighting.initialize(s);
                                    clear();
                                });
                    } catch (java.io.IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                });
    }

    public static ShaderInstance shader() {
        return shader;
    }

    public static void clear() {
        GroundAlbedo.clear();
    }

    static long gpuBytes() {
        return 0;
    }

    private GroundExterior() {}
}
