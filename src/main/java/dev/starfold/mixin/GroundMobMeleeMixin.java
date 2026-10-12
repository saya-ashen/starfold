package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.ground.GroundSeams;
import dev.starfold.ground.GroundServer;

import net.minecraft.world.entity.Mob;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Mob AI across face seams: targets given in canonical coordinates on another face are re-expressed in the mob's own chart, where the neighbouring faces appear as ghost chunks. */
@Mixin(Mob.class)
public abstract class GroundMobMeleeMixin {
    @Shadow
    protected abstract net.minecraft.world.phys.AABB getAttackBoundingBox();

    @WrapMethod(method = "isWithinMeleeAttackRange")
    private boolean starfold$chartMelee(
            net.minecraft.world.entity.LivingEntity target, Operation<Boolean> original) {
        var self = (Mob) (Object) this;
        if (!GroundServer.canonical(self.level())) return original.call(target);
        var box = GroundSeams.box(self.level(), target.getBoundingBox(), self.getX(), self.getZ());
        return box != null && getAttackBoundingBox().intersects(box);
    }
}
