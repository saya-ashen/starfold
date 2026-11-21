package dev.starfold.devtools.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.starfold.api.RenderWorkDiagnostics;
import dev.starfold.devtools.Capture;
import dev.starfold.devtools.Profiler;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityShadowMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/Entity;FFLnet/minecraft/world/level/LevelReader;F)V"), require = 1)
    private void sfprofile$shadow(PoseStack pose, MultiBufferSource buffers, Entity entity,
            float strength, float partial, LevelReader level, float radius, Operation<Void> original) {
        Profiler.begin(Capture.ENTITY_SHADOWS);
        RenderWorkDiagnostics.add(RenderWorkDiagnostics.Metric.ENTITY_SHADOW_CALLS, 1);
        try { original.call(pose, buffers, entity, strength, partial, level, radius); }
        finally { Profiler.end(Capture.ENTITY_SHADOWS); }
    }
}
