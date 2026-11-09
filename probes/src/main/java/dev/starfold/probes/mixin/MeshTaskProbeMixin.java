package dev.starfold.probes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.starfold.dev.DevMeshProbe;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
public abstract class MeshTaskProbeMixin implements DevMeshProbe.TaskAccess {
    @Unique private DevMeshProbe.Trace starfold$trace;
    @Override public DevMeshProbe.Trace starfold$trace() { return starfold$trace; }
    @Override public void starfold$trace(DevMeshProbe.Trace trace) { starfold$trace = trace; }

    @Inject(method = "doTask", at = @At("HEAD"))
    private void starfold$started(SectionBufferBuilderPack buffers, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<CompletableFuture<?>> cir) {
        if (DevMeshProbe.active(starfold$trace) && starfold$trace.polled != 0)
            DevMeshProbe.EXECUTOR.add(System.nanoTime() - starfold$trace.polled);
    }
    @WrapOperation(method = "doTask", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionCompiler;compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;"))
    private SectionCompiler.Results starfold$compile(SectionCompiler compiler, SectionPos pos, RenderChunkRegion region,
            VertexSorting sorting, SectionBufferBuilderPack buffers, List<?> extra, Operation<SectionCompiler.Results> original) {
        long start = System.nanoTime();
        var result = original.call(compiler, pos, region, sorting, buffers, extra);
        starfold$trace.built = System.nanoTime();
        if (DevMeshProbe.active(starfold$trace)) DevMeshProbe.COMPILE.add(starfold$trace.built - start);
        return result;
    }
    @WrapOperation(method = {"doTask", "lambda$doTask$1"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;setCompiled(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$CompiledSection;)V"))
    private void starfold$published(SectionRenderDispatcher.RenderSection section,
            SectionRenderDispatcher.CompiledSection compiled, Operation<Void> original) {
        original.call(section, compiled);
        DevMeshProbe.published(starfold$trace);
        ((DevMeshProbe.SectionAccess) section).starfold$published(starfold$trace);
    }
    @Inject(method = "cancel", at = @At("HEAD"))
    private void starfold$cancelled(CallbackInfo ci) { DevMeshProbe.cancelled(starfold$trace); }
}
