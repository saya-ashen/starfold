package dev.starfold.mixin;
import dev.starfold.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Opt-in held interpolated pose/time for raster comparisons. */
@Mixin(value=NativeGroundFlightClient.class,remap=false)
public abstract class DevPortCameraMixin {
    @Shadow private static NativeGroundTopology.Pose renderPose;
    @Shadow private static double renderTime;
    @Unique private static NativeGroundTopology.Pose held;
    @Unique private static double heldTime;
    @Inject(method="prepare",at=@At("HEAD"),cancellable=true)
    private static void hold(float delta,CallbackInfo ci){
        if(!DevPortProbe.enabled()||!DevPortProbe.hold){held=null;return;}
        if(held!=null){renderPose=held;renderTime=heldTime;ci.cancel();}
    }
    @Inject(method="prepare",at=@At("RETURN"))
    private static void capture(float delta,CallbackInfo ci){if(DevPortProbe.enabled()&&DevPortProbe.hold&&held==null){held=renderPose;heldTime=renderTime;}}
}
