package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;

import dev.starfold.render.CoreShaderSource;
import dev.starfold.render.RendererGuard;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.InputStream;
import java.util.List;

@Mixin(Program.class)
public abstract class CoreShaderProgramMixin {
    @WrapOperation(
            method = "compileShaderInternal",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;process(Ljava/lang/String;)Ljava/util/List;"))
    private static List<String> starfold$capSource(
            GlslPreprocessor processor,
            String source,
            Operation<List<String>> original,
            Program.Type type,
            String name,
            InputStream input,
            String pack,
            GlslPreprocessor preprocessor) {
        return original.call(
                processor,
                RendererGuard.pipelineSupported()
                        ? CoreShaderSource.patch(name, type == Program.Type.VERTEX, source)
                        : source);
    }
}
