package dev.starfold.planet;

import dev.starfold.sky.Space;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.worldgen.PlanetEnvironment;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.function.Function;
import java.util.function.IntFunction;

/** Shared gameplay policy. Missing profiles, other dimensions and space retain vanilla behavior. */
public final class PlanetConditions {
    public static final double AIRLESS_DENSITY = .01;
    public static final double FALL_DAMAGE_EXPONENT = 1;
    public static final double VACUUM_WORLD_VOLUME = .12, VACUUM_SELF_VOLUME = .45;
    private static Function<BlockPos, PlanetEnvironment> client = pos -> null;

    public static void installClient(Function<BlockPos, PlanetEnvironment> source) {
        client = java.util.Objects.requireNonNull(source);
    }

    /** Pure storage lookup, also used by physics: never guess a nearest planet in a gap. */
    public static PlanetEnvironment at(BlockPos pos, IntFunction<PlanetEnvironment> environments) {
        if (Space.holds(pos.getX(), pos.getZ()) || !PlanetAccess.SHARED.inChart(pos)) return null;
        return environments.apply(PlanetAccess.SHARED.storagePlanet(pos));
    }

    /** Only the observed planet has a client profile; unrelated positions must not inherit it. */
    public static PlanetEnvironment observedAt(
            BlockPos storage, int planet, PlanetEnvironment environment) {
        return at(storage, id -> id == planet ? environment : null);
    }

    public static PlanetEnvironment at(Level level, BlockPos pos) {
        if (!PlanetLevels.is(level)) return null;
        // Client positions are observer-continuous, not canonical storage coordinates.
        if (level.isClientSide) return client.apply(pos);
        if (!(level instanceof ServerLevel server)
                || !(server.getChunkSource().getGenerator() instanceof SystemChunkGenerator system))
            return null;
        return at(pos, id -> system.planet(id).generation().environment());
    }

    public static double gravity(PlanetEnvironment environment) {
        return environment == null ? 1 : environment.gravity();
    }

    public static double gravity(Level level, BlockPos pos) {
        return gravity(at(level, pos));
    }

    /** Kinetic-energy scaling: (distance - safe/g) * g; jump strength is scaled separately by jumpScale. */
    public static double fallScale(double gravity) {
        return Math.pow(gravity, FALL_DAMAGE_EXPONENT);
    }

    public static double safeFallScale(double gravity) {
        return 1 / fallScale(gravity);
    }

    /** Heavy planets keep the vanilla jump height (v^2/g), so a block can still be climbed; light ones jump higher. */
    public static double jumpScale(double gravity) {
        return Math.max(1, Math.sqrt(gravity));
    }

    public static boolean canBurn(double density) {
        return density > AIRLESS_DENSITY;
    }

    public static boolean airless(Level level, BlockPos pos) {
        var environment = at(level, pos);
        return environment != null && !canBurn(environment.atmosphere().density());
    }

    public static double soundScale(double density, boolean self) {
        double floor = self ? VACUUM_SELF_VOLUME : VACUUM_WORLD_VOLUME;
        return floor + (1 - floor) * Math.clamp(density, 0, 1);
    }

    private PlanetConditions() {}
}
