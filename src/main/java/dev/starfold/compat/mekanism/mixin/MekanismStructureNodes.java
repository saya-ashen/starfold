package dev.starfold.compat.mekanism.mixin;

import mekanism.common.lib.multiblock.IMultiblockBase;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Pseudo
@Mixin(targets = "mekanism.common.lib.multiblock.Structure", remap = false)
public interface MekanismStructureNodes {
    @Accessor("nodes")
    Map<BlockPos, IMultiblockBase> starfold$nodes();
}
