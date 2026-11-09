package dev.starfold.biotech.host;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Short-lived attacks belong to the encounter, not the terrain or its save data. */
final class HostHazards {
    enum Kind { ROOT, FRAGMENT, SLURRY, FIRE, EGG }
    private static final class Spot {
        final Kind kind;
        final Vec3 start, point;
        int age;
        Spot(Kind kind, Vec3 start, Vec3 point, int delay) {
            this.kind = kind; this.start = start; this.point = point; age = -delay;
        }
    }
    private final HostEntity host;
    private final List<Spot> spots = new ArrayList<>();

    HostHazards(HostEntity host) { this.host = host; }

    Vec3 ground(Vec3 point) {
        return host.level().clip(new ClipContext(point.add(0, .5, 0), point.subtract(0, HostRules.DRONE_RANGE, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, host)).getLocation().add(0, .1, 0);
    }

    void add(Kind kind, Vec3 point, int delay) {
        spots.add(new Spot(kind, point, ground(point), delay));
    }

    void cancelWarnings() { spots.removeIf(spot -> spot.kind == Kind.ROOT || spot.kind == Kind.EGG); }

    private void sound(Vec3 point, SoundEvent sound) {
        host.level().playSound(null, point.x, point.y, point.z, sound, SoundSource.HOSTILE, 2, 1);
    }

    void tick() {
        var level = (ServerLevel) host.level();
        var iterator = spots.iterator();
        while (iterator.hasNext()) {
            var spot = iterator.next();
            if (++spot.age <= 0) continue;
            var point = spot.point;
            boolean done = false;
            switch (spot.kind) {
                case ROOT, FRAGMENT -> {
                    boolean root = spot.kind == Kind.ROOT;
                    int warning = root ? HostRules.ROOT_WARN_TICKS : HostRules.FRAGMENT_WARN_TICKS;
                    double radius = root ? HostRules.ROOT_RADIUS : HostRules.FRAGMENT_RADIUS;
                    if (root && spot.age == 1) sound(point, HostSounds.ROOTS_WARN.get());
                    if (spot.age < warning) {
                        level.sendParticles(root ? ParticleTypes.CRIMSON_SPORE : ParticleTypes.SMOKE,
                                point.x, point.y, point.z, 12, radius * .65, .1, radius * .65, .01);
                        if (root) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.NETHER_WART_BLOCK.defaultBlockState()),
                                point.x, point.y, point.z, 8, radius * .5, .1, radius * .5, .02);
                        if (!root) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BASALT.defaultBlockState()),
                                point.x, point.y + (warning - spot.age) * .6 * HostRules.SCALE, point.z, 8,
                                        .3 * HostRules.SCALE, .3 * HostRules.SCALE, .3 * HostRules.SCALE, .02);
                    } else {
                        sound(point, root ? HostSounds.ROOTS.get() : HostSounds.SHELL_BREAK.get());
                        if (root) {
                            for (double height = 0; height <= 3; height += .5)
                                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.NETHER_WART_BLOCK.defaultBlockState()),
                                        point.x, point.y + height, point.z, 16, .3, .2, .3, .02);
                        } else level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BASALT.defaultBlockState()),
                                point.x, point.y + .3 * HostRules.SCALE, point.z, 80,
                                radius * .5, .3 * HostRules.SCALE, radius * .5, .15);
                        damage(point, radius, root ? HostRules.ROOT_DAMAGE : HostRules.FRAGMENT_DAMAGE,
                                root ? HostRules.ROOT_LIFT : 0);
                        done = true;
                    }
                }
                case SLURRY, FIRE -> {
                    boolean fire = spot.kind == Kind.FIRE;
                    double radius = fire ? HostRules.PATCH_RADIUS : HostRules.SLURRY_RADIUS;
                    if (spot.age % 4 == 0) level.sendParticles(fire ? ParticleTypes.FLAME : ParticleTypes.DRAGON_BREATH,
                            point.x, point.y + .15, point.z, 18, radius * .6, .1, radius * .6, .01);
                    if (spot.age % HostRules.DAMAGE_INTERVAL == 0)
                        damage(point, radius, fire ? HostRules.PATCH_DAMAGE : HostRules.SLURRY_DAMAGE, 0);
                    done = spot.age >= (fire ? HostRules.PATCH_TICKS : HostRules.SLURRY_TICKS);
                }
                case EGG -> {
                    double progress = Math.min(1, spot.age / (HostRules.EGG_HATCH_TICKS * .75));
                    var egg = spot.start.lerp(point, progress * progress);
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.NETHER_WART_BLOCK.defaultBlockState()),
                            egg.x, egg.y + .3, egg.z, 12, .18, .25, .18, 0);
                    if (spot.age >= HostRules.EGG_HATCH_TICKS) {
                        if (level.getEntitiesOfClass(HostDrone.class, new AABB(host.arenaPoint(), host.arenaPoint())
                                .inflate(HostRules.DRONE_RANGE)).size() < HostRules.DRONE_CAP) {
                            var drone = HostEntities.DRONE.get().create(level);
                            drone.moveTo(point.x, point.y + 1, point.z, 0, 0);
                            level.addFreshEntity(drone);
                        }
                        sound(point, HostSounds.SLURRY_LAND.get());
                        done = true;
                    }
                }
            }
            if (done) iterator.remove();
        }
    }

    private void damage(Vec3 point, double radius, float amount, double lift) {
        for (var player : host.level().getEntitiesOfClass(Player.class, new AABB(point, point).inflate(radius, 2, radius),
                p -> p.isAlive() && !p.isCreative() && !p.isSpectator()
                        && p.position().subtract(point).horizontalDistanceSqr() <= radius * radius)) {
            if (player.hurt(host.damageSources().mobAttack(host), amount) && lift > 0) {
                player.push(0, lift, 0);
                player.hurtMarked = true;
            }
        }
    }
}
