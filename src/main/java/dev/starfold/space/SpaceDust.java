package dev.starfold.space;

import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Motion exposure in seconds, not a speed-dependent billboard size. */
public final class SpaceDust {
    static final double STREAK_TIME = .06, MAX_LENGTH = SpaceSpeed.V_MAX * STREAK_TIME, WIDTH = .10;
    static final double MIN_SPEED = 60,
            CRUISE_SPEED = 300,
            BOOST_SPEED = 800,
            CRUISE_RATE = 8,
            MAX_RATE = 28;
    static final double RATE_POWER =
            Math.log(CRUISE_RATE / MAX_RATE)
                    / Math.log((CRUISE_SPEED - MIN_SPEED) / (BOOST_SPEED - MIN_SPEED));
    static final double ALPHA_SPEED = 150,
            ALPHA_MIN = .45,
            ALPHA_MAX = .90,
            CENTRE_CLEAR = .125,
            CENTRE_FULL = .25;
    private static final DeferredRegister<net.minecraft.core.particles.ParticleType<?>> TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, "starfold");
    public static final java.util.function.Supplier<SimpleParticleType> TYPE =
            TYPES.register("space_dust", () -> new SimpleParticleType(false));

    public static void initialize(net.neoforged.bus.api.IEventBus bus) {
        TYPES.register(bus);
    }

    static double length(double speed) {
        return Math.min(MAX_LENGTH, Math.max(0, speed) * STREAK_TIME)
                * Math.min(1, Math.max(0, speed) / 60);
    }

    static double rate(double speed) {
        return Double.isFinite(speed)
                ? MAX_RATE
                        * Math.pow(
                                Math.clamp((speed - MIN_SPEED) / (BOOST_SPEED - MIN_SPEED), 0, 1),
                                RATE_POWER)
                : 0;
    }

    /** Keep absolute exposure/opacity thresholds; only saturate emission earlier for slower hulls. */
    static double rate(double speed, double boostCap) {
        double top =
                Double.isFinite(boostCap)
                        ? Math.clamp(boostCap, SpaceSpeed.V_MIN, BOOST_SPEED)
                        : BOOST_SPEED;
        return Math.min(MAX_RATE, rate(speed) * MAX_RATE / rate(top));
    }

    /** The shell recedes with speed; past the old cap the streak thickens with it, so it does not thin out of sight. */
    static double width(double speed) {
        return speed > MIN_SPEED && Double.isFinite(speed)
                ? WIDTH * Math.max(1, speed / BOOST_SPEED)
                : 0;
    }

    static float alpha(double speed, double age, double lifetime) {
        return Double.isFinite(speed)
                ? (float)
                        ((ALPHA_MIN
                                        + (ALPHA_MAX - ALPHA_MIN)
                                                * Math.clamp(
                                                        (speed - ALPHA_SPEED)
                                                                / (BOOST_SPEED - ALPHA_SPEED),
                                                        0,
                                                        1))
                                * Math.clamp((speed - MIN_SPEED) / (ALPHA_SPEED - MIN_SPEED), 0, 1)
                                * Math.clamp(age / 2, 0, 1)
                                * Math.clamp((lifetime - age) / 2, 0, 1))
                : 0;
    }

    /** NDC centre fade: preserve long peripheral streaks, not their bounding rectangles. */
    static double peripheral(double x, double y) {
        return Math.clamp(
                (Math.max(Math.abs(x), Math.abs(y)) - CENTRE_CLEAR) / (CENTRE_FULL - CENTRE_CLEAR),
                0,
                1);
    }

    private SpaceDust() {}
}
