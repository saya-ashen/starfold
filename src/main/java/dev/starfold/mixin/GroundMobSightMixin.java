package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.GroundSeams;
import dev.starfold.ground.GroundServer;

import org.spongepowered.asm.mixin.Mixin;

/** Mob AI across face seams: targets given in canonical coordinates on another face are re-expressed in the mob's own chart, where the neighbouring faces appear as ghost chunks. */
@Mixin(net.minecraft.world.entity.LivingEntity.class)
public abstract class GroundMobSightMixin {
    @WrapMethod(method = "hasLineOfSight")
    private boolean starfold$chartSight(
            net.minecraft.world.entity.Entity other, Operation<Boolean> original) {
        var self = (net.minecraft.world.entity.LivingEntity) (Object) this;
        if (!GroundServer.canonical(self.level()) || other.level() != self.level())
            return original.call(other);
        var eye = new net.minecraft.world.phys.Vec3(self.getX(), self.getEyeY(), self.getZ());
        var p =
                GroundSeams.rechart(
                        self.level(),
                        new Point(other.getX(), other.getEyeY(), other.getZ()),
                        self.getX(),
                        self.getZ());
        if (p == null) return false;
        var seen = new net.minecraft.world.phys.Vec3(p.x(), p.y(), p.z());
        return seen.distanceTo(eye) <= 128
                && self.level()
                                .clip(
                                        new net.minecraft.world.level.ClipContext(
                                                eye,
                                                seen,
                                                net.minecraft.world.level.ClipContext.Block
                                                        .COLLIDER,
                                                net.minecraft.world.level.ClipContext.Fluid.NONE,
                                                self))
                                .getType()
                        == net.minecraft.world.phys.HitResult.Type.MISS;
    }
}
