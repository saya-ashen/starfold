package dev.starfold.devtools.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.planet.PlanetLevels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
abstract class SectionCompilerMixin {
    @Unique private long starfold$created;
    @Unique private ChunkWorkDiagnostics.Listener starfold$trace;

    @Inject(method = "<init>(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;DLnet/minecraft/client/renderer/chunk/RenderChunkRegion;ZLjava/util/List;)V", at = @At("RETURN"))
    private void starfold$rebuildCreated(CallbackInfo ci) {
        var trace = ChunkWorkDiagnostics.listener();
        if (trace == null) return;
        var level = Minecraft.getInstance().level;
        if (level == null || !PlanetLevels.is(level)) return;
        starfold$trace = trace;
        starfold$created = System.nanoTime();
    }

    @WrapOperation(method = "doTask", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/chunk/SectionCompiler;compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;"))
    private SectionCompiler.Results starfold$traceCompile(SectionCompiler compiler, SectionPos section, RenderChunkRegion region,
            VertexSorting sorting, SectionBufferBuilderPack buffers,
            List<AddSectionGeometryEvent.AdditionalSectionRenderer> renderers,
            Operation<SectionCompiler.Results> original) {
        var trace = starfold$trace;
        if (trace == null || trace != ChunkWorkDiagnostics.listener())
            return original.call(compiler, section, region, sorting, buffers, renderers);
        long start = System.nanoTime();
        var result = original.call(compiler, section, region, sorting, buffers, renderers);
        long end = System.nanoTime();
        if (!result.renderedLayers.isEmpty()) {
            long chunk = section.chunk().toLong();
            trace.meshScheduled(chunk, section.y(), starfold$created);
            trace.meshCompiled(chunk, section.y(), end, end - start);
        }
        return result;
    }
}
