package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.ground.GroundSeams;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.HashSet;
import java.util.Set;

/** Mob AI across face seams: targets given in canonical coordinates on another face are re-expressed in the mob's own chart, where the neighbouring faces appear as ghost chunks. */
@Mixin(PathNavigation.class)
public abstract class GroundMobNavigationMixin {
    @Shadow @Final protected Mob mob;

    @WrapMethod(
            method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;")
    private Path starfold$chartTargets(
            Set<BlockPos> targets,
            int region,
            boolean offset,
            int reach,
            float range,
            Operation<Path> original) {
        if (!PlanetLevels.canonical(mob.level()))
            return original.call(targets, region, offset, reach, range);
        var mapped = new HashSet<BlockPos>();
        for (var t : targets) {
            var p = GroundSeams.rechart(mob.level(), t, mob.getX(), mob.getZ());
            if (p != null) mapped.add(p);
        }
        return mapped.isEmpty() ? null : original.call(mapped, region, offset, reach, range);
    }
}
