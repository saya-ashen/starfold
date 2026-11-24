package dev.starfold.compat.create.mixin;

import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.belt.BeltBlockEntity", remap = false)
public abstract class CreateBeltEntityMixin {
    @ModifyVariable(method = "setController", at = @At("HEAD"), argsOnly = true)
    private BlockPos starfold$storeCanonicalController(BlockPos pos) {
        var level = ((BlockEntity) (Object) this).getLevel();
        if (pos == null || level == null || !PlanetLevels.canonical(level)) return pos;
        var access = PlanetAccess.of(level);
        return access.inChart(pos) ? access.address(pos).owner() : pos;
    }
}
