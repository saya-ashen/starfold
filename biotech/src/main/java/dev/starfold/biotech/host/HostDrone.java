package dev.starfold.biotech.host;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

/** Steering and contact attacks are original; vanilla navigation does not walk this flying mob. */
public final class HostDrone extends Monster implements GeoEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private Vec3 home;
    private int age, cooldown;

    public HostDrone(EntityType<? extends HostDrone> type, Level level) {
        super(type, level);
        setNoGravity(true);
        xpReward = 1;
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 8)
                .add(Attributes.MOVEMENT_SPEED, .3).add(Attributes.ATTACK_DAMAGE, 2).add(Attributes.FOLLOW_RANGE, 48);
    }

    @Override protected void registerGoals() {}
    @Override public boolean causeFallDamage(float distance, float multiplier, DamageSource source) { return false; }

    @Override protected void customServerAiStep() {
        if (++age > 1200) { discard(); return; }
        if (home == null) home = position();
        Player target = level().getNearestPlayer(getX(), getY(), getZ(), 48,
                p -> p instanceof Player player && player.isAlive() && !player.isCreative() && !player.isSpectator() && p.position().distanceToSqr(home) < 96 * 96);
        setTarget(target);
        double angle = (age + getId() * 17) * .06;
        Vec3 destination = target == null ? home.add(Math.cos(angle) * 8, 4, Math.sin(angle) * 8)
                : target.getEyePosition().add(Math.cos(angle) * (age % 100 < 55 ? 4 : 0),
                        age % 100 < 55 ? 2 : 0, Math.sin(angle) * (age % 100 < 55 ? 4 : 0));
        Vec3 desired = destination.subtract(position()).normalize().scale(.38);
        setDeltaMovement(getDeltaMovement().scale(.85).add(desired.scale(.15)));
        if (horizontalCollision || verticalCollision) setDeltaMovement(getDeltaMovement().add(0, .2, 0));
        if (target != null) {
            getLookControl().setLookAt(target, 30, 30);
            if (--cooldown <= 0 && getBoundingBox().inflate(.5).intersects(target.getBoundingBox())) {
                target.hurt(damageSources().mobAttack(this), 2);
                cooldown = 30;
            }
        }
        yBodyRot = getYHeadRot();
    }

    @Override public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof HostEntity || source.getEntity() instanceof HostDrone) return false;
        return super.hurt(source, amount);
    }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("DroneAge", age);
        if (home != null) {
            tag.putDouble("HomeX", home.x); tag.putDouble("HomeY", home.y); tag.putDouble("HomeZ", home.z);
        }
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        age = Math.clamp(tag.getInt("DroneAge"), 0, 1200);
        double x = tag.getDouble("HomeX"), y = tag.getDouble("HomeY"), z = tag.getDouble("HomeZ");
        if (tag.contains("HomeX", 6) && tag.contains("HomeY", 6) && tag.contains("HomeZ", 6)
                && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(x) <= 30000000 && Math.abs(z) <= 30000000 && Math.abs(y) <= 2048)
            home = new Vec3(x, y, z);
    }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "body", 2,
                state -> state.setAndContinue(RawAnimation.begin().thenLoop("fly"))));
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
