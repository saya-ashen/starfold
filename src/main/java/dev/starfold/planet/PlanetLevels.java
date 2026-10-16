package dev.starfold.planet;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** The level that holds a system's planets. */
public final class PlanetLevels {
    public static final ResourceKey<Level> PLANET =
            ResourceKey.create(
                    Registries.DIMENSION, ResourceLocation.parse("starfold:native_planet"));

    private PlanetLevels() {}

    public static boolean is(Level level) {
        return level != null && level.dimension().equals(PLANET);
    }

    /** Storage-chart aliasing applies to the server world only: a client world is already laid out in the
     * observer's own continuous coordinates and is read exactly where it is. */
    public static boolean canonical(Level level) {
        return is(level) && !level.isClientSide;
    }
}
