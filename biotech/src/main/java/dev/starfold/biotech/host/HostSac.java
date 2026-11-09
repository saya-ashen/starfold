package dev.starfold.biotech.host;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

public final class HostSac extends Monster implements GeoEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private UUID host;
    private int spawnTicks;

    public HostSac(EntityType<? extends HostSac> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setNoGravity(true);
        xpReward = 3;
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 40)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.MOVEMENT_SPEED, 0);
    }

    public void setHost(UUID id) { host = id; }
    @Override protected void registerGoals() {}
    @Override protected boolean shouldDespawnInPeaceful() { return false; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public void knockback(double strength, double x, double z) {}
    @Override public void travel(Vec3 movement) { setDeltaMovement(Vec3.ZERO); }

    @Override protected void customServerAiStep() {
        if (host != null) {
            var data = HostSave.get((ServerLevel) level());
            if (data.owns(host) && data.defeated()) return;
        }
        if (++spawnTicks < 160 + getId() % 40) return;
        spawnTicks = 0;
        if (level().getNearestPlayer(getX(), getY(), getZ(), 80, p -> p instanceof net.minecraft.world.entity.player.Player player && player.isAlive() && !player.isCreative() && !player.isSpectator()) == null
                || level().getEntitiesOfClass(HostDrone.class, getBoundingBox().inflate(HostRules.DRONE_RANGE)).size() >= HostRules.DRONE_CAP) return;
        var drone = HostEntities.DRONE.get().create(level());
        drone.moveTo(getX(), getY() + 3.5, getZ(), getYRot(), 0);
        level().addFreshEntity(drone);
        playSound(SoundEvents.SLIME_SQUISH, 1, .5f);
    }

    @Override public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof HostEntity || source.getEntity() instanceof HostDrone) return false;
        return super.hurt(source, amount);
    }

    @Override public void die(DamageSource source) {
        super.die(source);
        if (level() instanceof ServerLevel level) {
            if (host != null) HostSave.get(level).sacDied(host, getUUID());
            triggerAnim("body", "rupture");
            playSound(SoundEvents.SLIME_DEATH, 2, .6f);
        }
    }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (host != null) tag.putUUID("Host", host);
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        host = tag.hasUUID("Host") ? tag.getUUID("Host") : null;
    }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "body", 3,
                state -> state.setAndContinue(RawAnimation.begin().thenLoop("idle")))
                .triggerableAnim("rupture", RawAnimation.begin().thenPlay("rupture")));
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
