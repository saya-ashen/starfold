package dev.starfold.universe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;

/** A world preset's choice to begin beside a ship: the structure the ship is built from. */
public record StarterShipOption(ResourceLocation structure) {
    public static final Codec<StarterShipOption> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            ResourceLocation.CODEC
                                                    .fieldOf("structure")
                                                    .forGetter(StarterShipOption::structure))
                                    .apply(i, StarterShipOption::new));
}
