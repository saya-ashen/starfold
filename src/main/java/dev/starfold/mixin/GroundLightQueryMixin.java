package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.ground.GroundSeams;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockAndTintGetter.class)
public interface GroundLightQueryMixin {
    @WrapMethod(method = "getBrightness")
    private int starfold$nativeLight(LightLayer layer, BlockPos pos, Operation<Integer> original) {
        if (!((Object) this instanceof Level level)
                || !PlanetLevels.canonical(level)
                || !PlanetAccess.of(level).inChart(pos)) return original.call(layer, pos);
        var owner = PlanetAccess.of(level).address(pos).owner();
        if (level.isClientSide
                && !level.getChunkSource().hasChunk(owner.getX() >> 4, owner.getZ() >> 4)) {
            var value = GroundSeams.clientBrightness(level, owner, layer);
            if (value != null) return value;
        }
        return original.call(layer, owner);
    }
}
