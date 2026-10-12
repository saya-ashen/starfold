package dev.starfold.mixin;

import dev.starfold.ship.Airstair;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityGetter.class)
public interface AirstairCollisionMixin {
    @Inject(method = "getEntityCollisions", at = @At("RETURN"), cancellable = true)
    private void starfold$airstair(
            Entity entity, AABB query, CallbackInfoReturnable<java.util.List<VoxelShape>> ci) {
        if ((Object) this instanceof Level level)
            ci.setReturnValue(Airstair.collisions(level, entity, query, ci.getReturnValue()));
    }
}
