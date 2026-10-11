package dev.starfold.mixin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.starfold.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(targets="net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class NativeGroundEntityTrackingMixin {
    @Shadow @Final private Entity entity;
    @ModifyExpressionValue(method="updatePlayer",at=@At(value="INVOKE",target="Lnet/minecraft/world/phys/Vec3;subtract(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 starfold$nativeRange(Vec3 delta){if(!NativeGroundServer.is(entity.level())||!NativePlanetAccess.of(entity.level()).inChart(entity.blockPosition()))return delta;double x=entity.getX()+delta.x;var box=NativeGroundSeams.box(entity.level(),entity.getBoundingBox(),x,entity.getZ()+delta.z);if(box==null)return new Vec3(1e20,delta.y,1e20);var center=box.getCenter();return new Vec3(x-center.x,delta.y,entity.getZ()+delta.z-center.z);}
    @WrapOperation(method="updatePlayer",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ChunkMap;isChunkTracked(Lnet/minecraft/server/level/ServerPlayer;II)Z"))
    private boolean starfold$nativeAliasTracked(ChunkMap chunks,ServerPlayer player,int x,int z,Operation<Boolean> original){var access=NativePlanetAccess.of(entity.level());return entity.level()==player.level()&&NativeGroundServer.is(entity.level())&&access.inChart(entity.blockPosition())&&access.inChart(player.blockPosition())&&access.samePlanet(entity.getX(),entity.getZ(),player.getX(),player.getZ())||original.call(chunks,player,x,z);}
}
