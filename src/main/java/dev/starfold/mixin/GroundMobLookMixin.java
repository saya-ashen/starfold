package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.GroundSeams;
import dev.starfold.ground.GroundServer;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.LookControl;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Mob AI across face seams: targets given in canonical coordinates on another face are re-expressed in the mob's own chart, where the neighbouring faces appear as ghost chunks. */
@Mixin(LookControl.class)
public abstract class GroundMobLookMixin {
    @Shadow @Final protected Mob mob;

    @WrapMethod(method = "setLookAt(DDDFF)V")
    private void starfold$chartLook(
            double x, double y, double z, float yaw, float pitch, Operation<Void> original) {
        if (GroundServer.canonical(mob.level())) {
            var p = GroundSeams.rechart(mob.level(), new Point(x, y, z), mob.getX(), mob.getZ());
            if (p == null) return;
            x = p.x();
            y = p.y();
            z = p.z();
        }
        original.call(x, y, z, yaw, pitch);
    }
}
