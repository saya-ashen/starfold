package dev.starfold.mixin;
import dev.starfold.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.*;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(GameRenderer.class)
public abstract class NativeGroundFlightRenderMixin {
    @Inject(method="bobView",at=@At("HEAD"),cancellable=true)
    private void starfold$nativeNoBob(PoseStack pose,float partial,CallbackInfo ci){if(NativeGroundFlightClient.controlled())ci.cancel();}
    @Inject(method="getDepthFar",at=@At("RETURN"),cancellable=true)
    private void starfold$nativeFar(CallbackInfoReturnable<Float> ci){if(NativeSpaceClient.active()){ci.setReturnValue(Math.max(ci.getReturnValue(),NativeSpaceView.FAR));return;}if(NativeGroundClient.active())ci.setReturnValue(Math.max(ci.getReturnValue(),NativeSpaceView.chartFar(NativeGroundClient.ready()?NativeGroundClient.eye().y():0,NativeGroundClient.view())));}
    @Inject(method="pick(F)V",at=@At("HEAD"),cancellable=true)
    private void starfold$nativeNoBlockPick(float partial,CallbackInfo ci){if(!NativeGroundFlightClient.controlled())return;var mc=Minecraft.getInstance();mc.crosshairPickEntity=null;mc.hitResult=BlockHitResult.miss(mc.gameRenderer.getMainCamera().getPosition(),Direction.UP,BlockPos.ZERO);ci.cancel();}
}
