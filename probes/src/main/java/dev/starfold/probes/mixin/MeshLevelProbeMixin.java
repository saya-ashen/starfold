package dev.starfold.probes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.starfold.dev.DevMeshProbe;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class MeshLevelProbeMixin {
    @Shadow private SectionRenderDispatcher sectionRenderDispatcher;
    @WrapMethod(method = "compileSections")
    private void starfold$frame(Camera camera, Operation<Void> original) {
        DevMeshProbe.frame(sectionRenderDispatcher);
        long start = System.nanoTime();
        original.call(camera);
        DevMeshProbe.FRAME_COMPILE.add(System.nanoTime() - start);
    }
    @WrapOperation(method = "compileSections", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;rebuildSectionSync(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;Lnet/minecraft/client/renderer/chunk/RenderRegionCache;)V"))
    private void starfold$sync(SectionRenderDispatcher dispatcher, SectionRenderDispatcher.RenderSection section,
            RenderRegionCache cache, Operation<Void> original) {
        long start = System.nanoTime();
        original.call(dispatcher, section, cache);
        DevMeshProbe.SYNC.add(System.nanoTime() - start);
    }
    @WrapOperation(method = "renderSectionLayer", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V"))
    private void starfold$draw(VertexBuffer buffer, Operation<Void> original,
            @Local SectionRenderDispatcher.RenderSection section) {
        original.call(buffer);
        ((DevMeshProbe.SectionAccess) section).starfold$drawn();
    }
}
