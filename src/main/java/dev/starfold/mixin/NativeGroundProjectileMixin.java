package dev.starfold.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.starfold.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Pick/collision intersects a rigid image; the hit still names the original real entity. */
@Mixin(ProjectileUtil.class)
public abstract class NativeGroundProjectileMixin {
    private static AABB image(Entity entity,Operation<AABB> original,Vec3 start){var box=original.call(entity);if(!NativeGroundServer.canonical(entity.level())||!NativePlanetAccess.of(entity.level()).inChart(net.minecraft.core.BlockPos.containing(start)))return box;var image=NativeGroundSeams.box(entity.level(),box,start.x,start.z);return image==null?box:image;}
    @WrapOperation(method="getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
    private static AABB starfold$pick(Entity entity,Operation<AABB> original,Entity mover,Vec3 start,Vec3 end,AABB query,Predicate<Entity> predicate,double range){return image(entity,original,start);}
    @WrapOperation(method="getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
    private static AABB starfold$projectile(Entity entity,Operation<AABB> original,Level level,Entity mover,Vec3 start,Vec3 end,AABB query,Predicate<Entity> predicate,float padding){return image(entity,original,start);}
}
