package dev.starfold.mixin;
import dev.starfold.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(Entity.class)
public abstract class NativeGroundTopologyEntityMixin {
    @Inject(method="move",at=@At("RETURN"))
    // A move made for a client (the player itself, or the vehicle it steers) is finished by its network handler,
    // which goes on to compare with the client's coordinates: the chart changes after that, not inside it.
    private void starfold$nativeSeam(MoverType type,Vec3 delta,CallbackInfo ci){if(type!=MoverType.PLAYER&&!((Object)this instanceof net.minecraft.server.level.ServerPlayer))NativeGroundSeams.moved((Entity)(Object)this);}
    @Inject(method="distanceToSqr(DDD)D",at=@At("HEAD"),cancellable=true)
    private void starfold$nativeDistance(double x,double y,double z,CallbackInfoReturnable<Double> ci){var e=(Entity)(Object)this;if(NativeGroundServer.canonical(e.level()))ci.setReturnValue(NativeGroundSeams.distance(e,x,y,z));}
    @Inject(method="distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D",at=@At("HEAD"),cancellable=true)
    private void starfold$nativeVectorDistance(Vec3 p,CallbackInfoReturnable<Double> ci){var e=(Entity)(Object)this;if(NativeGroundServer.canonical(e.level()))ci.setReturnValue(NativeGroundSeams.distance(e,p.x,p.y,p.z));}
    @Inject(method="distanceToSqr(Lnet/minecraft/world/entity/Entity;)D",at=@At("HEAD"),cancellable=true)
    private void starfold$nativeEntityDistance(Entity other,CallbackInfoReturnable<Double> ci){var e=(Entity)(Object)this;if(NativeGroundServer.canonical(e.level()))ci.setReturnValue(NativeGroundSeams.distance(e,other.getX(),other.getY(),other.getZ()));}
}
