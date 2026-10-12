package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.starfold.render.CoreShaderSource;
import dev.starfold.render.RendererGuard;

import foundry.veil.impl.client.render.shader.processor.VanillaShaderProcessor;

import net.minecraft.resources.ResourceLocation;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Map;

/** Sable's bundled Veil recompiles from resources without Program.compileShaderInternal.
 * Its processors read and extend upstream's main, and its serializer is lossy (bare return, do-while,
 * ternary grouping, prototypes): they get upstream's own entry point with our hooks only, and our
 * wrappers go around what they return. */
@Mixin(value = VanillaShaderProcessor.class, remap = false)
public abstract class CoreShaderVeilProcessorMixin {
    @WrapMethod(method = "modify", remap = false)
    private static String starfold$veilSource(
            Map<String, Object> custom,
            String name,
            ResourceLocation id,
            VertexFormat format,
            int buffers,
            int shaderType,
            String source,
            GLCapabilities capabilities,
            Operation<String> original) {
        boolean vertex = shaderType == GL20.GL_VERTEX_SHADER;
        boolean ours =
                (vertex || shaderType == GL20.GL_FRAGMENT_SHADER)
                        && RendererGuard.pipelineSupported();
        if (ours) source = CoreShaderSource.hooks(name, vertex, source);
        String processed =
                original.call(custom, name, id, format, buffers, shaderType, source, capabilities);
        return ours ? CoreShaderSource.wrap(name, vertex, processed) : processed;
    }
}
