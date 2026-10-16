package dev.starfold.mixin;

import dev.starfold.ground.GroundSeams;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

import java.util.*;
import java.util.function.Predicate;

/** Query true entities once, using rigid chart bounding boxes instead of proxy entities. */
@Mixin(Level.class)
public abstract class GroundEntityQueriesMixin {
    @Shadow
    protected abstract LevelEntityGetter<Entity> getEntities();

    /** Use the section index directly so looking up an image does not project it again. */
    @Unique
    private List<Entity> starfold$candidates(AABB query) {
        var level = (Level) (Object) this;
        var access = PlanetAccess.of(level);
        if (!PlanetLevels.canonical(level)
                || !access.inChart(BlockPos.containing(query.getCenter()))) return null;
        double radius = level.getMaxEntityRadius();
        var boxes = GroundSeams.queryBoxes(access, query);
        var storage =
                (dev.ryanhcode.sable.util.SubLevelInclusiveLevelEntityGetter<Entity>) getEntities();
        var found = new LinkedHashSet<Entity>();
        // A halo entity can still appear inside another face; retain it even for an interior query.
        for (int i = 1; i < boxes.size(); i++)
            storage.getIgnoringSubLevels(
                    boxes.get(i).inflate(radius),
                    entity -> {
                        var image =
                                GroundSeams.box(
                                        access,
                                        entity.getBoundingBox(),
                                        query.getCenter().x,
                                        query.getCenter().z);
                        if (!entity.isRemoved() && image != null && query.intersects(image))
                            found.add(entity);
                    });
        if (found.isEmpty()
                && GroundSeams.interior(access, query, radius)
                && level.getPartEntities().isEmpty()
                && !dev.ryanhcode.sable.Sable.HELPER
                        .getAllIntersecting(
                                level, new dev.ryanhcode.sable.companion.math.BoundingBox3d(query))
                        .iterator()
                        .hasNext()) return null;
        storage.getIgnoringSubLevels(
                query.inflate(radius),
                entity -> {
                    var image =
                            GroundSeams.box(
                                    access,
                                    entity.getBoundingBox(),
                                    query.getCenter().x,
                                    query.getCenter().z);
                    if (!entity.isRemoved() && image != null && query.intersects(image))
                        found.add(entity);
                });
        return new ArrayList<>(found);
    }

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
        var candidates = starfold$candidates(query);
        if (candidates == null) return;
        var result = new ArrayList<Entity>();
        for (var entity : candidates)
            if (entity != except && predicate.test(entity)) result.add(entity);
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
        var candidates = starfold$candidates(query);
        if (candidates == null) return;
        for (var entity : candidates) {
            if (result.size() >= limit) break;
            var cast = type.tryCast(entity);
            if (cast != null && predicate.test(cast)) result.add(cast);
        }
        ci.cancel();
    }
}
