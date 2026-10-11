package dev.starfold.mixin;
import dev.starfold.*;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Home-only old tint context; gated out of ordinary runs. */
@Mixin(value=NativeGroundRenderer.class,remap=false)
public abstract class DevPortTintMixin {
    @Inject(method="surfaceTint",at=@At("HEAD"),cancellable=true)
    private static void legacy(CallbackInfoReturnable<BlockAndTintGetter> ci){if(DevPortProbe.enabled()&&DevPortProbe.legacyTint)ci.setReturnValue(net.minecraft.client.Minecraft.getInstance().level);}
}
