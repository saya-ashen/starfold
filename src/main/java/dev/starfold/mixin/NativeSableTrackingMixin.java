package dev.starfold.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.starfold.NativeGroundSeams;
import dev.starfold.NativeGroundServer;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** How far a body is from a player is measured over the planet's surface, as between two entities: a body just
 *  over a seam is in another chart, thousands of blocks away by its numbers. */
@Mixin(value=SubLevelTrackingSystem.class,remap=false)
public abstract class NativeSableTrackingMixin {
    @WrapOperation(method="shouldLoad",at=@At(value="INVOKE",target="Lorg/joml/Vector3dc;distanceSquared(DDD)D",remap=false),remap=false)
    private double starfold$nativeDistance(Vector3dc body,double x,double y,double z,Operation<Double> original,@Local(argsOnly=true) Player player){
        return NativeGroundServer.canonical(player.level())?NativeGroundSeams.distance(player,body.x(),body.y(),body.z()):original.call(body,x,y,z);
    }
}
