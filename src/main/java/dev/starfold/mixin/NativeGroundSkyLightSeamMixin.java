package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.starfold.NativeLightSeams;
import net.minecraft.world.level.lighting.SkyLightEngine;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Sideways sky light across face seams (under overhangs). Column source bookkeeping stays per chunk. */
@Mixin(SkyLightEngine.class)
public abstract class NativeGroundSkyLightSeamMixin {
    @WrapOperation(method={"propagateIncrease","propagateDecrease"},at=@At(value="INVOKE",target="Lnet/minecraft/world/level/lighting/SkyLightSectionStorage;getStoredLevel(J)I"))
    private int starfold$get(SkyLightSectionStorage storage,long pos,Operation<Integer> original){return NativeLightSeams.get(this,storage,pos,owner->original.call(storage,owner));}
    @WrapOperation(method={"propagateIncrease","propagateDecrease"},at=@At(value="INVOKE",target="Lnet/minecraft/world/level/lighting/SkyLightSectionStorage;setStoredLevel(JI)V"))
    private void starfold$set(SkyLightSectionStorage storage,long pos,int level,Operation<Void> original){NativeLightSeams.set(this,storage,pos,level,owner->original.call(storage,owner,level));}
    @WrapOperation(method={"propagateIncrease","propagateDecrease"},at=@At(value="INVOKE",target="Lnet/minecraft/world/level/lighting/SkyLightSectionStorage;storingLightForSection(J)Z"))
    private boolean starfold$stored(SkyLightSectionStorage storage,long section,Operation<Boolean> original){return original.call(storage,NativeLightSeams.section(this,section));}
}
