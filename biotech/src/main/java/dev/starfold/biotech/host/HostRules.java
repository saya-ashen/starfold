package dev.starfold.biotech.host;

import net.minecraft.world.phys.Vec3;

/** Provisional encounter balance, independent of worlds and registries. */
public final class HostRules {
    public static final float SCALE = 2.5f;
    public static final float HEALTH = 600;
    public static final int SAC_COUNT = 6, SHELL_TICKS = 40, TAKEOFF_TICKS = 40;
    public static final double FLIGHT_RADIUS = 150;
    public static final int GROUND_ATTACK_TICKS = 90, AIR_ATTACK_TICKS = 100;
    public static final int ROOT_WARN_TICKS = 20, VENT_CHARGE_TICKS = 24, VENT_FIRE_SECONDS = 2;
    public static final double ROOT_RADIUS = 2 * (1 + (SCALE - 1) / 3), ROOT_LIFT = .7;
    public static final double VENT_RADIUS = 7 * SCALE, VENT_KNOCKBACK = 1.6, VENT_LIFT = .45;
    public static final float ROOT_DAMAGE = 8, VENT_DAMAGE = 6;
    public static final int SLURRY_TICKS = 120, DAMAGE_INTERVAL = 20, PROJECTILE_TICKS = 200;
    public static final double SLURRY_RADIUS = 2.5 * (1 + (SCALE - 1) / 3), SLURRY_GRAVITY = .03;
    public static final float SLURRY_DAMAGE = 2;
    public static final int LOB_FLIGHT_TICKS = 40;
    public static final double MOUTH_HEIGHT = 4 * SCALE, MOUTH_FORWARD = 5.5 * SCALE;
    public static final int FRAGMENT_COUNT = 8, FRAGMENT_WARN_TICKS = 12;
    public static final double FRAGMENT_SPREAD = 10 * SCALE, FRAGMENT_RADIUS = 1.5 * SCALE;
    public static final float FRAGMENT_DAMAGE = 6;
    public static final int ENGINE_SOUND_TICKS = 40, TRAIL_TICKS = 60, TRAIL_INTERVAL = 10;
    public static final int TRAIL_PERIOD = 180, PATCH_TICKS = 80;
    public static final double PATCH_RADIUS = 2 * SCALE;
    public static final float PATCH_DAMAGE = 3;
    public static final int EGG_MIN = 2, EGG_MAX = 3, EGG_HATCH_TICKS = 40, DRONE_CAP = 18;
    public static final double DRONE_RANGE = FLIGHT_RADIUS, EGG_SPREAD = 3 * SCALE;
    public static final int DIVE_PERIOD = 240, DIVE_TICKS = 45, DIVE_PITCH_TICKS = 10;
    public static final float DIVE_DAMAGE = 10;
    public static final int DEATH_CRASH_TICKS = 100, GROUND_DEATH_TICKS = 60;

    public enum GroundAttack { ROOTS, VENT, SLURRY }

    public static GroundAttack groundAttack(double distanceSquared, boolean slurry) {
        return distanceSquared <= VENT_RADIUS * VENT_RADIUS ? GroundAttack.VENT
                : slurry ? GroundAttack.SLURRY : GroundAttack.ROOTS;
    }

    public static Vec3 lobVelocity(Vec3 origin, Vec3 target) {
        var delta = target.subtract(origin);
        double drag = .99f, ticks = LOB_FLIGHT_TICKS;
        double sum = (1 - Math.pow(drag, ticks)) / (1 - drag);
        // Throwable projectiles move, then apply drag and gravity each tick.
        return new Vec3(delta.x / sum, (delta.y + SLURRY_GRAVITY * (ticks - sum) / (1 - drag)) / sum,
                delta.z / sum);
    }

    public static boolean secondPhase(int livingSacs, float health, float maximum) {
        return livingSacs == 0 || health < maximum * .5f;
    }

    public static float damage(float amount, boolean groundPhase, int livingSacs) {
        return groundPhase && livingSacs > 0 ? amount * .1f : amount;
    }

    public static boolean shouldSpawn(boolean spawned, boolean defeated, double distanceSquared,
                                      double verticalDistance, double craterRadius) {
        return !spawned && !defeated && Double.isFinite(distanceSquared) && distanceSquared >= 0
                && Double.isFinite(verticalDistance) && Math.abs(verticalDistance) <= 96
                && distanceSquared <= (craterRadius + 48) * (craterRadius + 48);
    }

    public static Vec3 orbitPoint(Vec3 centre, int ticks) {
        double angle = ticks * .014;
        // Leave room for the enlarged body within the arena's unchanged flight boundary.
        double radius = Math.min(45 * SCALE, FLIGHT_RADIUS * .6);
        return centre.add(Math.cos(angle) * radius, (28 + Math.sin(angle * 2) * 6) * SCALE, Math.sin(angle) * radius);
    }

    public static Vec3 flightVelocity(Vec3 position, Vec3 velocity, Vec3 destination, double speed) {
        var delta = destination.subtract(position);
        var desired = delta.normalize().scale(Math.min(speed, delta.length() * .08));
        return velocity.scale(.8).add(desired.scale(.2));
    }

    private HostRules() {}
}
