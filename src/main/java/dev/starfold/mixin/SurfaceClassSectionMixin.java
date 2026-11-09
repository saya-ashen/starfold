package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.starfold.api.SurfaceClass;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.render.SurfaceClassVertices;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SectionCompiler.class)
public abstract class SurfaceClassSectionMixin {
    @WrapOperation(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V"))
    private void starfold$surfaceClass(BlockRenderDispatcher renderer, BlockState state, BlockPos pos,
            BlockAndTintGetter view, PoseStack pose, VertexConsumer vertices, boolean cull, RandomSource random,
            ModelData data, RenderType layer, Operation<Void> original) {
        if ((layer == RenderType.solid() || layer == RenderType.cutout() || layer == RenderType.cutoutMipped())
                && PlanetLevels.is(((SurfaceClassRegionAccess)(RenderChunkRegion)view).starfold$level()))
            vertices = new SurfaceClassVertices(vertices, SurfaceClass.of(state, view, pos));
        original.call(renderer,state,pos,view,pose,vertices,cull,random,data,layer);
    }
}
