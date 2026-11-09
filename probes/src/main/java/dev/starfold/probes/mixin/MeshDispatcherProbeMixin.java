package dev.starfold.probes.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import dev.starfold.dev.DevMeshProbe;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.class)
public abstract class MeshDispatcherProbeMixin {
    @Inject(method = "schedule", at = @At("HEAD"))
    private void starfold$scheduled(@Coerce Object task, CallbackInfo ci) {
        if (task instanceof DevMeshProbe.TaskAccess access) {
            var t = access.starfold$trace();
            t.scheduled = System.nanoTime();
        }
    }
    @Inject(method = "pollTask", at = @At("RETURN"))
    private void starfold$polled(CallbackInfoReturnable<Object> cir) {
        if (cir.getReturnValue() instanceof DevMeshProbe.TaskAccess access) {
            var t = access.starfold$trace();
            t.polled = System.nanoTime();
            if (DevMeshProbe.active(t)) DevMeshProbe.QUEUE.add(t.polled - t.scheduled);
        }
    }
    @WrapOperation(method = "uploadSectionLayer", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> starfold$upload(Runnable task, Executor executor, Operation<CompletableFuture<Void>> original,
            @Local(argsOnly = true) MeshData mesh) {
        long size = mesh.vertexBuffer().remaining() + (mesh.indexBuffer() == null ? 0 : mesh.indexBuffer().remaining());
        return original.call(DevMeshProbe.upload(task, size, false), executor);
    }
    @WrapOperation(method = "uploadSectionIndexBuffer", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> starfold$sortUpload(Runnable task, Executor executor, Operation<CompletableFuture<Void>> original,
            @Local(argsOnly = true) ByteBufferBuilder.Result data) {
        return original.call(DevMeshProbe.upload(task, data.byteBuffer().remaining(), true), executor);
    }
}
