package dev.starfold;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;
import java.util.stream.Stream;

/** Actual Minecraft biome palette/resolver; shared climate has no face-specific seed. */
public final class NativePlanetBiomes extends BiomeSource {
    public static final MapCodec<NativePlanetBiomes> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
        Codec.LONG.fieldOf("terrain_seed").forGetter(NativePlanetBiomes::seed),
        Biome.CODEC.fieldOf("forest").forGetter(b->b.forest),Biome.CODEC.fieldOf("plains").forGetter(b->b.plains),
        Biome.CODEC.fieldOf("desert").forGetter(b->b.desert),Biome.CODEC.fieldOf("ocean").forGetter(b->b.ocean)
    ).apply(i,NativePlanetBiomes::new));
    private final long seed;private final Holder<Biome> forest,plains,desert,ocean;
    private final NativePlanetTerrain terrain;private final NativePlanetEcology ecology;
    public NativePlanetBiomes(long seed,Holder<Biome> forest,Holder<Biome> plains,Holder<Biome> desert,Holder<Biome> ocean){this(seed,forest,plains,desert,ocean,new NativePlanetTerrain(seed));}
    /** The same palette over the planet it belongs to: a planet of another size has its regions where its own ground is. */
    public NativePlanetBiomes on(NativePlanetTerrain planet){if(planet.seed()!=seed)throw new IllegalArgumentException("Native biome palette/seed mismatch");return new NativePlanetBiomes(seed,forest,plains,desert,ocean,planet);}
    private NativePlanetBiomes(long seed,Holder<Biome> forest,Holder<Biome> plains,Holder<Biome> desert,Holder<Biome> ocean,NativePlanetTerrain terrain){this.seed=seed;this.forest=forest;this.plains=plains;this.desert=desert;this.ocean=ocean;if(!forest.is(Biomes.FOREST)||!plains.is(Biomes.PLAINS)||!desert.is(Biomes.DESERT)||!ocean.is(Biomes.OCEAN))throw new IllegalArgumentException("v4 requires vanilla biome palette");this.terrain=terrain;ecology=new NativePlanetEcology(terrain);}
    public long seed(){return seed;}
    @Override protected MapCodec<? extends BiomeSource> codec(){return CODEC;}
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes(){return Stream.of(forest,plains,desert,ocean);}
    @Override public Holder<Biome> getNoiseBiome(int x,int y,int z,Climate.Sampler sampler){int wx=x*4+2,wz=z*4+2,face=Math.floorDiv(wx,4096),u=Math.floorMod(wx,4096);int size=terrain.shape().size();if(face<0||face>=6||u>=size||wz<0||wz>=size)return plains;var f=CubeSurfaceTopology.Face.values()[face];if(terrain.sea()!=null&&terrain.groundY(f,u,wz)<terrain.sea().level())return ocean;return switch(ecology.region(f,u,wz)){case FOREST->forest;case PLAINS->plains;case DESERT->desert;};}
}
