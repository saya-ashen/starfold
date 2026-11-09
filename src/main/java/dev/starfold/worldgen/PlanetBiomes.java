package dev.starfold.worldgen;
import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.*;
/** Minecraft biome source reads only the fixed biome field. */
public final class PlanetBiomes extends BiomeSource {
    public static final MapCodec<PlanetBiomes> CODEC = RecordCodecBuilder.mapCodec(i
        -> i.group(Codec.LONG.fieldOf("terrain_seed").forGetter(PlanetBiomes::seed),
                Biome.CODEC.listOf().fieldOf("palette").forGetter(b -> b.palette))
            .apply(i, PlanetBiomes::new));
    private final long seed;
    private final List<Holder<Biome>> palette;
    private PlanetTerrain terrain;
    public PlanetBiomes(long seed, List<Holder<Biome>> palette) {
        this.seed = seed;
        this.palette = List.copyOf(palette);
    }
    public PlanetBiomes on(PlanetTerrain terrain) {
        var result = new PlanetBiomes(seed, palette);
        result.terrain = terrain;
        return result;
    }
    public long seed() {
        return seed;
    }
    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }
    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return palette.stream();
    }
    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        int wx = x * 4 + 2, wz = z * 4 + 2, f = Math.floorDiv(wx, 4096), u = Math.floorMod(wx, 4096);
        if (f < 0 || f >= 6 || u >= terrain.shape().size() || wz < 0 || wz >= terrain.shape().size())
            return palette.getFirst();
        var id = ResourceLocation.parse(
            terrain.biome(dev.starfold.geometry.CubeSurfaceTopology.Face.VALUES[f], u, wz));
        return palette.stream()
            .filter(h -> h.is(id))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing fixed biome " + id));
    }
}
