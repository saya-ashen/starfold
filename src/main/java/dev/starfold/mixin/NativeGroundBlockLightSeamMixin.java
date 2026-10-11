package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.starfold.NativeLightSeams;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.BlockLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Block light propagation across face seams (see NativeGroundLightSeamMixin). */
@Mixin(BlockLightEngine.class)
public abstract class NativeGroundBlockLightSeamMixin {
    @WrapOperation(method={"checkNode","propagateIncrease","propagateDecrease"},at=@At(value="INVOKE",target="Lnet/minecraft/world/level/lighting/BlockLightSectionStorage;getStoredLevel(J)I"))
    private int starfold$get(BlockLightSectionStorage storage,long pos,Operation<Integer> original){return NativeLightSeams.get(this,storage,pos,owner->original.call(storage,owner));}
    @WrapOperation(method={"checkNode","propagateIncrease","propagateDecrease"},at=@At(value="INVOKE",target="Lnet/minecraft/world/level/lighting/BlockLightSectionStorage;setStoredLevel(JI)V"))
    private void starfold$set(BlockLightSectionStorage storage,long pos,int level,Operation<Void> original){NativeLightSeams.set(this,storage,pos,level,owner->original.call(storage,owner,level));}
    @WrapOperation(method={"checkNode","propagateIncrease","propagateDecrease"},at=@At(value="INVOKE",target="Lnet/minecraft/world/level/lighting/BlockLightSectionStorage;storingLightForSection(J)Z"))
    private boolean starfold$stored(BlockLightSectionStorage storage,long section,Operation<Boolean> original){return original.call(storage,NativeLightSeams.section(this,section));}
    @WrapOperation(method="getEmission",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/lighting/BlockLightSectionStorage;lightOnInSection(J)Z"))
    private boolean starfold$on(BlockLightSectionStorage storage,long section,Operation<Boolean> original){return original.call(storage,NativeLightSeams.section(this,section));}
}
