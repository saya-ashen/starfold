package dev.starfold.mixin;

import dev.starfold.NativeCloakMotion;
import dev.ryanhcode.sable.Sable;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every rendered player, not just the pilot. Off-hull and elytra flight use vanilla unchanged. */
@Mixin(Player.class)
public abstract class NativeCloakMixin {
    @Unique private Object starfold$body;
    @Unique private Vector3d starfold$cloak,starfold$feet;
    @Inject(method="moveCloak",at=@At("HEAD"),cancellable=true)
    private void starfold$relativeCloak(CallbackInfo ci){
        var p=(Player)(Object)this;var body=Sable.HELPER.getTrackingSubLevel(p);
        if(!p.level().isClientSide||body==null||p.isFallFlying()){starfold$body=null;return;}
        var pose=body.logicalPose();var feet=pose.transformPositionInverse(new Vector3d(p.getX(),p.getY(),p.getZ()));
        if(starfold$body!=body){starfold$body=body;starfold$cloak=new Vector3d(feet);starfold$feet=new Vector3d(feet);}
        var old=pose.orientation().transform(new Vector3d(starfold$cloak).sub(starfold$feet));
        starfold$cloak.set(NativeCloakMotion.follow(starfold$cloak.x,feet.x),NativeCloakMotion.follow(starfold$cloak.y,feet.y),NativeCloakMotion.follow(starfold$cloak.z,feet.z));
        var lag=pose.orientation().transform(new Vector3d(starfold$cloak).sub(feet));
        p.xCloakO=p.xo+old.x;p.yCloakO=p.yo+old.y;p.zCloakO=p.zo+old.z;
        p.xCloak=p.getX()+lag.x;p.yCloak=p.getY()+lag.y;p.zCloak=p.getZ()+lag.z;starfold$feet.set(feet);ci.cancel();
    }
}
