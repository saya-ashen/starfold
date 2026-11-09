package dev.starfold.mixin;

import dev.starfold.planet.PlanetConditions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CampfireBlockEntity.class)
public abstract class PlanetCampfireMixin {
    @Inject(
            method = {"cookTick", "particleTick"},
            at = @At("HEAD"),
            cancellable = true)
    private static void starfold$unlit(
            Level level,
            BlockPos pos,
            BlockState state,
            CampfireBlockEntity entity,
            CallbackInfo ci) {
        if (PlanetConditions.airless(level, pos)) {
            if (!level.isClientSide)
                level.setBlock(pos, state.setValue(CampfireBlock.LIT, false), 3);
            ci.cancel();
        }
    }
}
