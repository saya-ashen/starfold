package dev.starfold.compat.mekanism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.compat.mekanism.MekanismTankChart;
import dev.starfold.planet.PlanetAccess;
import mekanism.common.lib.multiblock.Structure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "mekanism.common.lib.multiblock.MultiblockData", remap = false)
public abstract class MekanismTankDataMixin implements MekanismTankChart.Data {
    @Unique private MekanismTankChart starfold$tankChart;

    @Override
    public void starfold$setTankChart(MekanismTankChart chart) {
        starfold$tankChart = chart;
    }

    @WrapMethod(method = "isPositionInsideBounds")
    private boolean starfold$insideTank(Structure structure, BlockPos pos, Operation<Boolean> original) {
        return original.call(structure, starfold$tankChart == null ? pos : starfold$tankChart.image(pos));
    }

    @WrapMethod(method = "isPositionOutsideBounds")
    private boolean starfold$outsideTank(BlockPos pos, Operation<Boolean> original) {
        return original.call(starfold$tankChart == null ? pos : starfold$tankChart.image(pos));
    }

    @WrapMethod(method = "getOutsideSide")
    private Direction starfold$tankOutsideSide(BlockPos pos, Operation<Direction> original) {
        if (starfold$tankChart == null) return original.call(pos);
        var image = starfold$tankChart.image(pos);
        var side = original.call(image);
        return side == null || !PlanetAccess.SHARED.inChart(image) ? side
                : PlanetAccess.SHARED.address(image).ownerDirection(side);
    }
}
