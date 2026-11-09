package dev.starfold.universe;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
/** Population switches; all biome selection belongs to the terrain recipe. */
public record PlanetTypeLife(Holder<Biome> biome, Optional<Boolean> creatures, Optional<Boolean> features,
    Optional<Boolean> structures) {
    public static final Codec<PlanetTypeLife> CODEC = RecordCodecBuilder.create(i
        -> i.group(Biome.CODEC.fieldOf("biome").forGetter(PlanetTypeLife::biome),
                Codec.BOOL.optionalFieldOf("creatures").forGetter(PlanetTypeLife::creatures),
                Codec.BOOL.optionalFieldOf("features").forGetter(PlanetTypeLife::features),
                Codec.BOOL.optionalFieldOf("structures").forGetter(PlanetTypeLife::structures))
            .apply(i, PlanetTypeLife::new));
}
