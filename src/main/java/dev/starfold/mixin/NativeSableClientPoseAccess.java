package dev.starfold.mixin;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=ClientSubLevel.class,remap=false)
public interface NativeSableClientPoseAccess {
    @Accessor(value="lastRenderPosePartialTick",remap=false) void starfold$renderPartial(float value);
}
