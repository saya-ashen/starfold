package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import dev.starfold.planet.PlanetWaterSpawning;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.level.LevelAccessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WaterAnimal.class)
public abstract class WaterAnimalSpawnMixin {
    @ModifyExpressionValue(
            method = "checkSurfaceWaterAnimalSpawnRules",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/level/LevelAccessor;getSeaLevel()I"))
    private static int starfold$spawnSeaLevel(
            int vanilla,
            EntityType<? extends WaterAnimal> type,
            LevelAccessor world,
            MobSpawnType reason,
            BlockPos pos,
            RandomSource random) {
        return PlanetWaterSpawning.seaLevel(world, pos, vanilla);
    }
}
