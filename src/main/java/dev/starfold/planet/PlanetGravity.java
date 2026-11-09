package dev.starfold.planet;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Transient, idempotent modifiers compose with equipment/effects and are removed outside planets. */
public final class PlanetGravity {
    public static final ResourceLocation MODIFIER =
            ResourceLocation.parse("starfold:planet_gravity");

    public static void sync(LivingEntity entity) {
        double g = PlanetConditions.gravity(entity.level(), entity.blockPosition());
        update(entity, Attributes.GRAVITY, g);
        update(entity, Attributes.SAFE_FALL_DISTANCE, PlanetConditions.safeFallScale(g));
        update(entity, Attributes.FALL_DAMAGE_MULTIPLIER, PlanetConditions.fallScale(g));
        update(entity, Attributes.JUMP_STRENGTH, PlanetConditions.jumpScale(g));
    }

    private static void update(LivingEntity entity, Holder<Attribute> attribute, double factor) {
        update(entity.getAttribute(attribute), factor);
    }

    public static void update(AttributeInstance attribute, double factor) {
        if (attribute == null) return;
        var old = attribute.getModifier(MODIFIER);
        double amount = factor - 1;
        if (old != null && old.amount() == amount) return;
        if (old != null) attribute.removeModifier(MODIFIER);
        if (amount != 0)
            attribute.addTransientModifier(
                    new AttributeModifier(
                            MODIFIER, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private PlanetGravity() {}
}
