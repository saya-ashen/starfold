package dev.starfold.biotech.host;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.joml.Vector3f;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

public final class HostEntity extends Monster implements GeoEntity {
    public static final int GROUND = 0, SHELL_BREAK = 1, TAKEOFF = 2, AIR = 3;
    private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(HostEntity.class, EntityDataSerializers.INT);
    // Hurt sounds play on the client, which cannot read the sac save data.
    private static final EntityDataAccessor<Boolean> SHIELDED = SynchedEntityData.defineId(HostEntity.class, EntityDataSerializers.BOOLEAN);
    private static final DustParticleOptions ENGINE_GLOW = new DustParticleOptions(new Vector3f(1, .35f, .65f), 2.5f);
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final ServerBossEvent boss = new ServerBossEvent(getDisplayName(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
    private Vec3 arena;
    private Vec3 diveTarget;
    private int phaseTicks, attackTicks, ventTicks, diveTicks, trailTicks;
    private boolean slurryNext;
    private final HostHazards hazards = new HostHazards(this);

    public HostEntity(EntityType<? extends HostEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 80;
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, HostRules.HEALTH)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.MOVEMENT_SPEED, .15)
                .add(Attributes.FOLLOW_RANGE, 160).add(Attributes.ATTACK_DAMAGE, 10);
    }

    @Override protected void registerGoals() {}
    @Override protected boolean shouldDespawnInPeaceful() { return false; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public void knockback(double strength, double x, double z) {}
    @Override public boolean causeFallDamage(float distance, float multiplier, DamageSource source) { return false; }

    public int phase() { return entityData.get(PHASE); }
    public void setArena(Vec3 point) { arena = point; }
    Vec3 arenaPoint() { return arena; }
    void slurry(Vec3 point) { hazards.add(HostHazards.Kind.SLURRY, point, 0); }

    @Override protected SoundEvent getAmbientSound() {
        return (phase() >= TAKEOFF ? HostSounds.CRY : HostSounds.IDLE).get();
    }
    // Hits on the shielded shell clunk, open flesh groans, the shell-less flyer shrieks.
    @Override protected SoundEvent getHurtSound(DamageSource source) {
        return (phase() >= TAKEOFF ? HostSounds.HURT_OPEN : entityData.get(SHIELDED) ? HostSounds.HURT_SHELL : HostSounds.HURT).get();
    }
    @Override protected float getSoundVolume() { return 4; }

    // Include the model's 24 x 20 visible bounds, not just its collision body.
    @Override public AABB getBoundingBoxForCulling() { return getBoundingBox().inflate(7 * HostRules.SCALE); }

    @Override public void tick() {
        super.tick();
        if (!level().isClientSide || phase() < TAKEOFF || isDeadOrDying()) return;
        // Short-lived flames shot from the open nozzles read as a thruster jet; a pink glow sits in the mouth.
        int bursts = phase() == TAKEOFF ? 10 : 6;
        for (int side : new int[]{-1, 1}) {
            var nozzle = position().add(new Vec3(side * 6.25, .75, -.6).scale(HostRules.SCALE).yRot((float) Math.toRadians(-yBodyRot)));
            for (int i = 0; i < bursts; i++)
                level().addParticle(ParticleTypes.FLAME, nozzle.x + random.nextGaussian() * .2 * HostRules.SCALE, nozzle.y,
                        nozzle.z + random.nextGaussian() * .2 * HostRules.SCALE, random.nextGaussian() * .03 * HostRules.SCALE,
                        (-.45 - random.nextDouble() * .2) * HostRules.SCALE, random.nextGaussian() * .03 * HostRules.SCALE);
            level().addParticle(ENGINE_GLOW, nozzle.x + random.nextGaussian() * .3 * HostRules.SCALE, nozzle.y + .2 * HostRules.SCALE,
                    nozzle.z + random.nextGaussian() * .3 * HostRules.SCALE, 0, 0, 0);
            if (random.nextInt(4) == 0)
                level().addParticle(ParticleTypes.DRAGON_BREATH, nozzle.x, nozzle.y - 3 * HostRules.SCALE, nozzle.z, 0, -.1 * HostRules.SCALE, 0);
        }
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(PHASE, GROUND);
        builder.define(SHIELDED, false);
    }

    private int livingSacs() {
        var save = HostSave.get((ServerLevel) level());
        // Unloaded sacs remain alive in SavedData, not inferred from an entity search.
        return save.owns(getUUID()) ? save.livingSacs() : 0;
    }

    @Override public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof HostEntity || source.getEntity() instanceof HostSac
                || source.getEntity() instanceof HostDrone) return false;
        if (!level().isClientSide && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY))
            amount = HostRules.damage(amount, phase() == GROUND, livingSacs());
        float before = getHealth();
        boolean hurt = super.hurt(source, amount);
        // A long cry at every lost quarter of health marks progress through the fight.
        if (hurt && !level().isClientSide && isAlive()
                && (int) (getHealth() / getMaxHealth() * 4) < (int) (before / getMaxHealth() * 4))
            playSound(HostSounds.CRY.get(), 4, 1);
        return hurt;
    }

    @Override protected void customServerAiStep() {
        super.customServerAiStep();
        boss.setName(getDisplayName());
        boss.setProgress(getHealth() / getMaxHealth());
        // /summon has no nest data: capture its placed position once, not the moving orbit.
        if (arena == null) arena = position();
        entityData.set(SHIELDED, phase() == GROUND && livingSacs() > 0);
        if (phase() == GROUND && HostRules.secondPhase(livingSacs(), getHealth(), getMaxHealth())) {
            entityData.set(PHASE, SHELL_BREAK);
            phaseTicks = 0;
            ventTicks = attackTicks = 0;
            hazards.cancelWarnings();
            triggerAnim("action", "shell_break");
            playSound(HostSounds.SHELL_BREAK.get(), 3, 1);
            playSound(HostSounds.CRY.get(), 3, 1);
            for (int i = 0; i < HostRules.FRAGMENT_COUNT; i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double radius = Math.sqrt(random.nextDouble()) * HostRules.FRAGMENT_SPREAD;
                hazards.add(HostHazards.Kind.FRAGMENT, position().add(Math.cos(angle) * radius, 2 * HostRules.SCALE, Math.sin(angle) * radius),
                        i * (HostRules.SHELL_TICKS - HostRules.FRAGMENT_WARN_TICKS) / (HostRules.FRAGMENT_COUNT - 1));
            }
        }
        phaseTicks++;
        hazards.tick();
        if (phase() == SHELL_BREAK && phaseTicks >= HostRules.SHELL_TICKS) {
            entityData.set(PHASE, TAKEOFF);
            phaseTicks = 0;
            triggerAnim("action", "takeoff");
        }
        setNoGravity(phase() >= TAKEOFF);
        if (phase() >= TAKEOFF && phaseTicks % HostRules.ENGINE_SOUND_TICKS == 0)
            playSound(HostSounds.ENGINE.get(), 2, 1);
        if (phase() == TAKEOFF) {
            setDeltaMovement(0, .45 * HostRules.SCALE, 0);
            if (phaseTicks >= HostRules.TAKEOFF_TICKS) {
                entityData.set(PHASE, AIR);
                phaseTicks = 0;
            }
            return;
        }
        if (phase() != AIR) setDeltaMovement(0, getDeltaMovement().y, 0);
        if (phase() == SHELL_BREAK) return;

        Player target = level().getNearestPlayer(getX(), getY(), getZ(), 160,
                p -> p instanceof Player candidate && !candidate.isSpectator() && !candidate.isCreative() && candidate.isAlive()
                        && p.position().distanceToSqr(arena) < HostRules.FLIGHT_RADIUS * HostRules.FLIGHT_RADIUS);
        setTarget(target);
        if (target != null) {
            getLookControl().setLookAt(target, 20, 20);
            if (phase() == GROUND) {
                var toward = target.position().subtract(position());
                setYRot((float) Math.toDegrees(Math.atan2(-toward.x, toward.z)));
                yBodyRot = getYRot();
            }
        }
        if (ventTicks > 0) {
            if (--ventTicks == 0) vent();
            else if (ventTicks == HostRules.VENT_CHARGE_TICKS / 2)
                playSound(HostSounds.VENT_CHARGE.get(), 2, 1.2f);
        }
        if (phase() == AIR) fly(target);
        if (target == null || ++attackTicks < (phase() == AIR ? HostRules.AIR_ATTACK_TICKS : HostRules.GROUND_ATTACK_TICKS)) return;
        if (phase() == AIR && diveTicks > 0) return;
        attackTicks = 0;
        if (phase() == AIR) {
            triggerAnim("action", "egg_drop");
            playSound(HostSounds.EGG_DROP.get(), 2, 1);
            int eggs = HostRules.EGG_MIN + random.nextInt(HostRules.EGG_MAX - HostRules.EGG_MIN + 1);
            for (int i = 0; i < eggs; i++) {
                double angle = i * Math.PI * 2 / eggs;
                hazards.add(HostHazards.Kind.EGG, position().add(Math.cos(angle) * HostRules.EGG_SPREAD, 0,
                        Math.sin(angle) * HostRules.EGG_SPREAD), 0);
            }
        } else switch (HostRules.groundAttack(distanceToSqr(target), slurryNext)) {
            case VENT -> {
                ventTicks = HostRules.VENT_CHARGE_TICKS;
                triggerAnim("action", "vent");
                playSound(HostSounds.VENT_CHARGE.get(), 2, .7f);
            }
            case ROOTS -> {
                triggerAnim("action", "roots");
                hazards.add(HostHazards.Kind.ROOT, target.position(), 0);
                slurryNext = true;
            }
            case SLURRY -> {
                triggerAnim("action", "spit");
                spit(target);
                slurryNext = false;
            }
        }
    }

    private void fly(Player target) {
        if (diveTicks > 0) {
            diveTicks--;
            if (diveTicks == HostRules.DIVE_TICKS - HostRules.DIVE_PITCH_TICKS)
                playSound(HostSounds.DIVE.get(), 3, 1.2f);
            steer(diveTarget, 1.3 * HostRules.SCALE);
            if (target != null && getBoundingBox().inflate(HostRules.SCALE).intersects(target.getBoundingBox())
                    && target.hurt(damageSources().mobAttack(this), HostRules.DIVE_DAMAGE))
                target.push(0, .5, 0);
        } else {
            steer(HostRules.orbitPoint(arena, phaseTicks), .65 * HostRules.SCALE);
            if (trailTicks > 0) {
                if (trailTicks % HostRules.TRAIL_INTERVAL == 0) hazards.add(HostHazards.Kind.FIRE, position(), 0);
                trailTicks--;
            } else if (target != null && phaseTicks % HostRules.TRAIL_PERIOD == 0) trailTicks = HostRules.TRAIL_TICKS;
            if (target != null && phaseTicks % HostRules.DIVE_PERIOD == 0) {
                // Freeze the aim for a dodgeable dive; do not chase a player out of the arena.
                diveTarget = target.position().subtract(0, 3 * HostRules.SCALE, 0);
                diveTicks = HostRules.DIVE_TICKS;
                trailTicks = 0;
                triggerAnim("action", "dive");
                playSound(HostSounds.DIVE.get(), 3, .8f);
            }
        }
        if (position().distanceToSqr(arena) > HostRules.FLIGHT_RADIUS * HostRules.FLIGHT_RADIUS) {
            diveTicks = 0;
            steer(arena.add(0, 28 * HostRules.SCALE, 0), HostRules.SCALE);
        }
    }

    private void steer(Vec3 destination, double speed) {
        setDeltaMovement(HostRules.flightVelocity(position(), getDeltaMovement(), destination, speed));
        if (horizontalCollision || verticalCollision) setDeltaMovement(getDeltaMovement().add(0, .3 * HostRules.SCALE, 0));
        setYRot((float) (Math.toDegrees(Math.atan2(-getDeltaMovement().x, getDeltaMovement().z))));
        yBodyRot = getYRot();
    }

    private void vent() {
        playSound(HostSounds.VENT.get(), 3, 1);
        var level = (ServerLevel) level();
        var side = Vec3.directionFromRotation(0, getYRot() + 90).scale(getBbWidth() * .5);
        for (int sign : new int[]{-1, 1}) {
            var flank = position().add(side.scale(sign)).add(0, 3 * HostRules.SCALE, 0);
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, flank.x, flank.y, flank.z, 60,
                    HostRules.SCALE, HostRules.SCALE, HostRules.SCALE, .15);
            level.sendParticles(ParticleTypes.FLAME, flank.x, flank.y, flank.z, 30,
                    HostRules.SCALE, .5 * HostRules.SCALE, HostRules.SCALE, .1);
        }
        for (var player : level.getEntitiesOfClass(Player.class, getBoundingBox().inflate(HostRules.VENT_RADIUS),
                p -> !p.isCreative() && !p.isSpectator() && p.isAlive()
                        && p.position().subtract(position()).horizontalDistanceSqr() <= HostRules.VENT_RADIUS * HostRules.VENT_RADIUS)) {
            if (player.hurt(damageSources().mobAttack(this), HostRules.VENT_DAMAGE)) {
                var away = player.position().subtract(position()).multiply(1, 0, 1).normalize();
                player.push(away.x * HostRules.VENT_KNOCKBACK, HostRules.VENT_LIFT, away.z * HostRules.VENT_KNOCKBACK);
                player.igniteForSeconds(HostRules.VENT_FIRE_SECONDS);
                player.hurtMarked = true;
            }
        }
    }

    private void spit(LivingEntity target) {
        var acid = HostEntities.ACID.get().create(level());
        acid.setOwner(this);
        var front = target.position().subtract(position()).multiply(1, 0, 1).normalize();
        acid.setPos(position().add(front.scale(HostRules.MOUTH_FORWARD)).add(0, HostRules.MOUTH_HEIGHT, 0));
        acid.setDeltaMovement(HostRules.lobVelocity(acid.position(), target.position()));
        level().addFreshEntity(acid);
        playSound(HostSounds.SPIT.get(), 2, 1);
    }

    @Override public void die(DamageSource source) {
        super.die(source);
        if (level() instanceof ServerLevel level) {
            HostSave.get(level).defeat(getUUID());
            boss.removeAllPlayers();
            triggerAnim("action", "death");
            hazards.cancelWarnings();
            setNoGravity(false);
            setDeltaMovement(getDeltaMovement().multiply(.25, 0, .25));
            playSound(HostSounds.DEATH.get(), 3, 1);
        }
    }

    @Override protected void tickDeath() {
        deathTime++;
        setNoGravity(false);
        if (level().isClientSide) return;
        if (phase() >= TAKEOFF) {
            if (!onGround() && deathTime < HostRules.DEATH_CRASH_TICKS) return;
            playSound(HostSounds.CRASH.get(), 4, 1);
            ((ServerLevel) level()).sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + HostRules.SCALE, getZ(), 1, 0, 0, 0, 0);
            ((ServerLevel) level()).sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY() + HostRules.SCALE, getZ(), 100,
                    4 * HostRules.SCALE, 2 * HostRules.SCALE, 4 * HostRules.SCALE, .15);
            remove(RemovalReason.KILLED);
        } else if (deathTime >= HostRules.GROUND_DEATH_TICKS) remove(RemovalReason.KILLED);
    }

    @Override public void startSeenByPlayer(ServerPlayer player) { super.startSeenByPlayer(player); boss.addPlayer(player); }
    @Override public void stopSeenByPlayer(ServerPlayer player) { super.stopSeenByPlayer(player); boss.removePlayer(player); }
    @Override public void remove(RemovalReason reason) { super.remove(reason); boss.removeAllPlayers(); }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("HostPhase", phase());
        tag.putInt("HostPhaseTicks", phaseTicks);
        Vec3 point = arena == null ? position() : arena;
        tag.putDouble("ArenaX", point.x); tag.putDouble("ArenaY", point.y); tag.putDouble("ArenaZ", point.z);
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        int phase = tag.getInt("HostPhase");
        entityData.set(PHASE, Math.clamp(phase, GROUND, AIR));
        setNoGravity(phase() >= TAKEOFF);
        phaseTicks = Math.clamp(tag.getInt("HostPhaseTicks"), 0, 1000000);
        if (tag.contains("ArenaX", 6) && tag.contains("ArenaY", 6) && tag.contains("ArenaZ", 6)) {
            double x = tag.getDouble("ArenaX"), y = tag.getDouble("ArenaY"), z = tag.getDouble("ArenaZ");
            if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Math.abs(x) <= 30000000 && Math.abs(z) <= 30000000 && Math.abs(y) <= 2048)
                arena = new Vec3(x, y, z);
        }
    }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 5, state ->
                isDeadOrDying() ? software.bernie.geckolib.animation.PlayState.STOP : state.setAndContinue(
                        RawAnimation.begin().thenLoop(phase() >= TAKEOFF ? "fly" : "idle"))));
        var action = new AnimationController<>(this, "action", 0, state -> software.bernie.geckolib.animation.PlayState.STOP);
        for (String name : new String[]{"roots", "vent", "spit", "shell_break", "takeoff", "dive", "egg_drop", "death"})
            action.triggerableAnim(name, RawAnimation.begin().thenPlay(name));
        controllers.add(action);
    }

    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
