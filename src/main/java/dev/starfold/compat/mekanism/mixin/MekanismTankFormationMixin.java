package dev.starfold.compat.mekanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.starfold.compat.mekanism.MekanismTankChart;
import mekanism.common.lib.math.voxel.IShape;
import mekanism.common.lib.multiblock.IStructureValidator;
import mekanism.common.lib.multiblock.MultiblockData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "mekanism.common.lib.multiblock.FormationProtocol", remap = false)
public abstract class MekanismTankFormationMixin {
    @WrapOperation(method = "buildStructure", at = @At(value = "INVOKE", target =
            "Lmekanism/common/lib/multiblock/MultiblockData;setShape(Lmekanism/common/lib/math/voxel/IShape;)Z"))
    private boolean starfold$rememberTankChart(MultiblockData data, IShape shape, Operation<Boolean> original,
            IStructureValidator<?> validator) {
        if (validator instanceof MekanismTankChart.View view && view.starfold$tankChart() != null) {
            ((MekanismTankChart.Data) data).starfold$setTankChart(view.starfold$tankChart());
        }
        return original.call(data, shape);
    }
}
