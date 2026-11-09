package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.textures.FluidSpriteCache;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep NeoForge's fluid hooks; only vanilla water defers appearance until the existing visible branch. */
@Mixin(LiquidBlockRenderer.class)
public abstract class WaterAppearanceMixin {
    private static boolean starfold$water(FluidState fluid) {
        return fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER;
    }

    @WrapOperation(method = "tesselate", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/textures/FluidSpriteCache;getFluidSprites(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/FluidState;)[Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
    private TextureAtlasSprite[] starfold$sprites(BlockAndTintGetter level, BlockPos pos, FluidState fluid,
            Operation<TextureAtlasSprite[]> original) {
        return starfold$water(fluid) ? null : original.call(level, pos, fluid);
    }

    @WrapOperation(method = "tesselate", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/extensions/common/IClientFluidTypeExtensions;getTintColor(Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"))
    private int starfold$tint(IClientFluidTypeExtensions extension, FluidState fluid, BlockAndTintGetter level,
            BlockPos pos, Operation<Integer> original) {
        return starfold$water(fluid) ? 0 : original.call(extension, fluid, level, pos);
    }

    @Inject(method = "tesselate", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockAndTintGetter;getShade(Lnet/minecraft/core/Direction;Z)F", ordinal = 0))
    private void starfold$visible(BlockAndTintGetter level, BlockPos pos, VertexConsumer vertices,
            BlockState state, FluidState fluid, CallbackInfo ci,
            @Local LocalRef<TextureAtlasSprite[]> sprites,
            @Local(ordinal = 0) LocalFloatRef alpha, @Local(ordinal = 1) LocalFloatRef red,
            @Local(ordinal = 2) LocalFloatRef green, @Local(ordinal = 3) LocalFloatRef blue) {
        if (!starfold$water(fluid)) return;
        sprites.set(FluidSpriteCache.getFluidSprites(level, pos, fluid));
        int color = IClientFluidTypeExtensions.of(fluid).getTintColor(fluid, level, pos);
        alpha.set((float) (color >> 24 & 255) / 255.0F);
        red.set((float) (color >> 16 & 255) / 255.0F);
        green.set((float) (color >> 8 & 255) / 255.0F);
        blue.set((float) (color & 255) / 255.0F);
    }
}
