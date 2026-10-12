package dev.starfold.mixin;

import dev.starfold.ground.GroundSeams;
import dev.starfold.ground.GroundServer;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

import java.util.*;
import java.util.function.Predicate;

/** Query true entities once, using rigid chart bounding boxes instead of proxy entities. */
@Mixin(Level.class)
public abstract class GroundEntityQueriesMixin {
    @Inject(
            method =
                    "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true)
    private void starfold$nativeEntities(
            Entity except,
            AABB query,
            Predicate<? super Entity> predicate,
            CallbackInfoReturnable<List<Entity>> ci) {
        var level = (Level) (Object) this;
        if (!GroundServer.canonical(level)
                || !PlanetAccess.of(level)
                        .inChart(net.minecraft.core.BlockPos.containing(query.getCenter()))) return;
        var result = new ArrayList<Entity>();
        for (var entity : GroundSeams.entities(level)) {
            var box =
                    GroundSeams.box(
                            level,
                            entity.getBoundingBox(),
                            query.getCenter().x,
                            query.getCenter().z);
            if (entity != except
                    && !entity.isRemoved()
                    && box != null
                    && query.intersects(box)
                    && predicate.test(entity)) result.add(entity);
        }
        ci.setReturnValue(result);
    }

    @Inject(
            method =
                    "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V",
            at = @At("HEAD"),
            cancellable = true)
    private <T extends Entity> void starfold$nativeTyped(
            EntityTypeTest<Entity, T> type,
            AABB query,
            Predicate<? super T> predicate,
            List<? super T> result,
            int limit,
            CallbackInfo ci) {
        var level = (Level) (Object) this;
        if (!GroundServer.canonical(level)
                || !PlanetAccess.of(level)
                        .inChart(net.minecraft.core.BlockPos.containing(query.getCenter()))) return;
        for (var entity : GroundSeams.entities(level)) {
            if (result.size() >= limit) break;
            var cast = type.tryCast(entity);
            var box =
                    GroundSeams.box(
                            level,
                            entity.getBoundingBox(),
                            query.getCenter().x,
                            query.getCenter().z);
            if (cast != null
                    && !entity.isRemoved()
                    && box != null
                    && query.intersects(box)
                    && predicate.test(cast)) result.add(cast);
        }
        ci.cancel();
    }
}
