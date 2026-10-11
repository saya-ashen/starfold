package dev.starfold;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;
import java.util.stream.Stream;

/** Routes actual biome storage, not merely the sky tint, to the owning planet. */
public final class NativeSystemBiomes extends BiomeSource {
    public static final MapCodec<NativeSystemBiomes> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
        BiomeSource.CODEC.listOf().fieldOf("planets").forGetter(b->b.planets)
    ).apply(i,NativeSystemBiomes::new));
    private final java.util.List<BiomeSource> planets;
    public NativeSystemBiomes(java.util.List<BiomeSource> planets){if(planets.isEmpty())throw new IllegalArgumentException("System biomes");this.planets=java.util.List.copyOf(planets);}
    @Override protected MapCodec<? extends BiomeSource> codec(){return CODEC;}
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes(){return planets.stream().flatMap(p->p.possibleBiomes().stream()).distinct();}
    @Override public Holder<Biome> getNoiseBiome(int x,int y,int z,Climate.Sampler sampler){
        long key=NativePlanetStorage.key(Math.floorDiv(x,4),Math.floorDiv(z,4));
        // Gaps and halos own nothing; they read as the first planet's, as they always have.
        int planet=key<0?0:Math.min(planets.size()-1,(int)(key/NativePlanetStorage.CHUNKS_PER_PLANET));
        return planets.get(planet).getNoiseBiome(x-NativePlanetStorage.column(planet)*(NativePlanetStorage.PLANET_STRIDE/4),y,z-NativePlanetStorage.row(planet)*(NativePlanetStorage.ROW_STRIDE/4),sampler);
    }
}
