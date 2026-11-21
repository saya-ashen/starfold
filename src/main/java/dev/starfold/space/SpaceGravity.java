package dev.starfold.space;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.starfold.ground.GroundSeams;
import dev.starfold.ship.SableFrames;
import dev.starfold.sky.Space;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Maintains the gravity override for living entities in a space region. */
public final class SpaceGravity {
    private static final ResourceLocation WEIGHTLESS =
            ResourceLocation.parse("starfold:space_weightless");
    static final Map<LivingEntity, ServerLevel> weightless = new WeakHashMap<>();

    static void clear() {
        weightless.clear();
    }

    static void release(ServerLevel level) {
        weightless.values().removeIf(value -> value == level);
    }

    static void tick(ServerLevel level) {
        // Only those previously made free need their gravity restored after leaving the region.
        weightless
                .entrySet()
                .removeIf(
                        e -> {
                            var living = e.getKey();
                            if (e.getValue() != level) return false;
                            if (!living.isRemoved() && living.level() == level && Space.in(living))
                                return false;
                            weight(living.getAttribute(Attributes.GRAVITY), false);
                            return true;
                        });
        var cabins = cabins(level);
        for (var entity : regionEntities(level))
            if (entity instanceof LivingEntity living) {
                boolean free = Space.in(entity) && !weighted(entity, cabins);
                weight(living.getAttribute(Attributes.GRAVITY), free);
                if (free) weightless.put(living, level);
                else weightless.remove(living);
            }
    }

    /** Membership ignores height, including entities above or below the terrain storage band. */
    static List<Entity> regionEntities(ServerLevel level) {
        return GroundSeams.entities(
                level,
                new AABB(
                        Space.X - Space.REACH,
                        Double.NEGATIVE_INFINITY,
                        Space.Z - Space.REACH,
                        Space.X + Space.REACH,
                        Double.POSITIVE_INFINITY,
                        Space.Z + Space.REACH));
    }

    /** Visit each cabin's local entities once, rather than visiting every hull for each space mob. */
    private static Set<Entity> cabins(ServerLevel level) {
        var found = new HashSet<Entity>();
        var container = SubLevelContainer.getContainer(level);
        if (container != null)
            for (var candidate : container.getAllSubLevels())
                if (candidate instanceof ServerSubLevel body && !body.isRemoved()) {
                    var bounds = SableFrames.cabinBounds(body);
                    if (bounds == null
                            || bounds.maxX < Space.X - Space.REACH
                            || bounds.minX > Space.X + Space.REACH
                            || bounds.maxZ < Space.Z - Space.REACH
                            || bounds.minZ > Space.Z + Space.REACH) continue;
                    for (var entity : GroundSeams.entities(level, bounds))
                        if (Space.in(entity) && SableFrames.inside(body, entity)) found.add(entity);
                }
        return found;
    }

    /** Weight uses contact NOW or cabin feet, never the passage/handover grace. */
    private static boolean weighted(Entity entity, Set<Entity> cabins) {
        return Sable.HELPER.getTrackingSubLevel(entity) != null || cabins.contains(entity);
    }

    /** Zeroes the final attribute while preserving the base and other mods' modifiers. */
    public static void weight(AttributeInstance gravity, boolean free) {
        if (gravity == null) return;
        if (free) {
            if (!gravity.hasModifier(WEIGHTLESS))
                gravity.addTransientModifier(
                        new AttributeModifier(
                                WEIGHTLESS, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else gravity.removeModifier(WEIGHTLESS);
    }

    private SpaceGravity() {}
}
