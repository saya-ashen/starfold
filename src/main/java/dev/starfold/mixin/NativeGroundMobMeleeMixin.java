package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.*;
import dev.starfold.BentStripGeometry.Point;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import java.util.HashSet;
import java.util.Set;

/** Mob AI across face seams: targets given in canonical coordinates on another face are re-expressed in the mob's own chart, where the neighbouring faces appear as ghost chunks. */
@Mixin(Mob.class)
public abstract class NativeGroundMobMeleeMixin {
    @Shadow protected abstract net.minecraft.world.phys.AABB getAttackBoundingBox();
    @WrapMethod(method="isWithinMeleeAttackRange")
    private boolean starfold$chartMelee(net.minecraft.world.entity.LivingEntity target,Operation<Boolean> original){
        var self=(Mob)(Object)this;if(!NativeGroundServer.canonical(self.level()))return original.call(target);
        var box=NativeGroundSeams.box(self.level(),target.getBoundingBox(),self.getX(),self.getZ());return box!=null&&getAttackBoundingBox().intersects(box);
    }
}
