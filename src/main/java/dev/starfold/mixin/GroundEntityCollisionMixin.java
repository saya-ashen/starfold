package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;

import dev.starfold.ground.GroundSeams;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityGetter.class)
public interface GroundEntityCollisionMixin {
    @WrapOperation(
            method = "getEntityCollisions",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
    private AABB starfold$nativeEntityBox(
            Entity entity, Operation<AABB> original, Entity mover, AABB query) {
        var box = original.call(entity);
        if ((Object) this instanceof Level level && PlanetLevels.canonical(level)) {
            var image = GroundSeams.box(level, box, query.getCenter().x, query.getCenter().z);
            if (image != null) return image;
        }
        return box;
    }
}
