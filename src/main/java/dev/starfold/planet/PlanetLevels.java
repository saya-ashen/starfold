package dev.starfold.planet;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Which levels are the mod's: the dimension that holds the planets, and the two of worlds made before all planets shared one. */
public final class PlanetLevels {
    public static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(
                    Registries.DIMENSION, ResourceLocation.parse("starfold:native_ground"));
    public static final ResourceKey<Level> PLANET =
            ResourceKey.create(
                    Registries.DIMENSION, ResourceLocation.parse("starfold:native_planet"));
    public static final ResourceKey<Level> ROCK =
            ResourceKey.create(
                    Registries.DIMENSION, ResourceLocation.parse("starfold:native_rock"));

    private PlanetLevels() {}

    public static boolean is(Level level) {
        return level != null
                && (level.dimension().equals(DIMENSION)
                        || level.dimension().equals(PLANET)
                        || level.dimension().equals(ROCK));
    }

    /** Storage-chart aliasing applies to the server world only: a client world is already laid out in the
     * observer's own continuous coordinates and is read exactly where it is. */
    public static boolean canonical(Level level) {
        return is(level) && !level.isClientSide;
    }
}
