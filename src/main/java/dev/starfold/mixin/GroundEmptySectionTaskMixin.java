package dev.starfold.mixin;

import dev.starfold.render.EmptySectionTask;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent.AdditionalSectionRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
abstract class GroundEmptySectionTaskMixin implements EmptySectionTask {
    @Shadow protected RenderChunkRegion region;
    @Shadow @Final private List<AdditionalSectionRenderer> additionalRenderers;
    @Shadow @Final private RenderSection this$1;
    @Shadow public abstract CompletableFuture<?> doTask(SectionBufferBuilderPack buffers);

    @Override
    public boolean starfold$publishEmpty() {
        if (region != null || !additionalRenderers.isEmpty() || !this$1.hasAllNeighbors()) return false;
        // The vanilla empty branch publishes visibility without using a mesh buffer.
        doTask(null);
        return true;
    }
}
