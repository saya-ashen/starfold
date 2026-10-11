package dev.starfold.mixin;

import dev.starfold.NativePilotPoseProbe;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read the actual fit test, including Sable's standing-box collision wrapper, on both sides. */
@Mixin(Player.class)
public abstract class NativePilotPoseProbeMixin {
    @Shadow protected abstract boolean canPlayerFitWithinBlocksAndEntitiesWhen(Pose pose);
    @Inject(method="updatePlayerPose",at=@At("RETURN"))
    private void starfold$poseProbe(CallbackInfo ci){
        if(!Boolean.getBoolean("starfold.proneProbe")&&!Boolean.getBoolean("starfold.carryProbe")&&System.getProperty("starfold.probe")==null)return;
        var player=(Player)(Object)this;
        boolean fits=canPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING);
        if(Boolean.getBoolean("starfold.proneProbe")){
            var previous=dev.starfold.NativeRiderPoseContext.BASELINE.get();boolean before;
            dev.starfold.NativeRiderPoseContext.BASELINE.set(true);
            try{before=canPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING);}finally{if(previous==null)dev.starfold.NativeRiderPoseContext.BASELINE.remove();else dev.starfold.NativeRiderPoseContext.BASELINE.set(previous);}
            org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE FITCOMPARE side {} tick {} pose {} tracked {} liveHelm {} before {} after {} swimming {} sneak {} ground {} uuid {}",player.level().isClientSide?"client":"server",player.level().getGameTime(),player.getPose(),dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(player)!=null,dev.starfold.NativeRiderPoseContext.liveHelm(player),before,fits,player.isSwimming(),player.isShiftKeyDown(),player.onGround(),player.getUUID());
        }
        NativePilotPoseProbe.sample(player,fits);
    }
}
