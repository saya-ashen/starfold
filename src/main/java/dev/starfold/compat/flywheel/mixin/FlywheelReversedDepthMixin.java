package dev.starfold.compat.flywheel.mixin;

import com.llamalad7.mixinextras.sugar.Local;

import dev.starfold.render.ReversedDepth;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.backend.glsl.ShaderSources$SourceFinder", remap = false)
public abstract class FlywheelReversedDepthMixin {
    @ModifyArg(method = "readResource", index = 2, at = @At(value = "INVOKE", target =
            "Ldev/engine_room/flywheel/backend/glsl/SourceFile;parse(Ljava/util/function/Function;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)Ldev/engine_room/flywheel/backend/glsl/LoadResult;"))
    private String starfold$skipForwardDepthOcclusion(
            String source, @Local(argsOnly = true) ResourceLocation shader) {
        if (!ReversedDepth.ACTIVE || !shader.getNamespace().equals("flywheel")
                || !shader.getPath().equals("internal/indirect/cull.glsl")) return source;
        // Keep the frustum test and the real near plane used by the other shaders.
        return source.replace("isVisible = isVisible && depthSphere <= depth;", "");
    }
}
