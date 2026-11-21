package dev.starfold.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Persistent tint caches must distinguish vanilla's seeded quart-biome zoom. */
@Mixin(BiomeManager.class)
public interface GroundBiomeManagerAccess {
    @Accessor("biomeZoomSeed")
    long starfold$zoomSeed();
}
