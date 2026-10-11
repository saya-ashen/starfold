package dev.starfold.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;

/** Vanilla movement's block-collision provider, not a replacement physics integrator. */
@Mixin(CollisionGetter.class)
public interface NativeGroundTopologyCollisionMixin {
    @WrapMethod(method="getBlockCollisions")
    private Iterable<VoxelShape> starfold$nativeCollision(Entity entity,AABB box,Operation<Iterable<VoxelShape>> original){
        if((Object)this instanceof Level level&&NativeGroundServer.is(level)){
            if(level.isClientSide)return new NativeGroundCollisions(level,entity,box);
            if(NativePlanetAccess.of(level).inChart(net.minecraft.core.BlockPos.containing(box.getCenter())))return new NativeGroundCollisions(level,entity,box,NativeGroundFlightServer.mask(level));
        }
        return original.call(entity,box);
    }
}
