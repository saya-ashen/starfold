package dev.starfold.biotech.host;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

public final class HostAcid extends ThrowableItemProjectile {
    public HostAcid(EntityType<? extends HostAcid> type, Level level) { super(type, level); }
    @Override protected Item getDefaultItem() { return Items.SLIME_BALL; }
    @Override protected double getDefaultGravity() { return HostRules.SLURRY_GRAVITY; }
    @Override protected boolean canHitEntity(Entity entity) {
        return !(entity instanceof HostEntity || entity instanceof HostSac || entity instanceof HostDrone)
                && super.canHitEntity(entity);
    }
    @Override public void tick() {
        super.tick();
        if (!level().isClientSide && tickCount > HostRules.PROJECTILE_TICKS) discard();
        if (level().isClientSide) level().addParticle(ParticleTypes.DRAGON_BREATH, getX(), getY(), getZ(), 0, 0, 0);
    }
    @Override protected void onHit(HitResult hit) {
        super.onHit(hit);
        if (level().isClientSide) return;
        var point = hit.getLocation();
        if (getOwner() instanceof HostEntity host && host.isAlive()) host.slurry(point);
        level().playSound(null, point.x, point.y, point.z, HostSounds.SLURRY_LAND.get(), SoundSource.HOSTILE, 2, 1);
        ((ServerLevel) level()).sendParticles(ParticleTypes.DRAGON_BREATH, point.x, point.y, point.z, 40, 1, .2, 1, .1);
        discard();
    }
}
