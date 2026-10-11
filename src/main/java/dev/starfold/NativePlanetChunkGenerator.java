package dev.starfold;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import java.util.concurrent.CompletableFuture;

/** Generates original Minecraft voxels once. Empty storage gaps are not another planet. */
public final class NativePlanetChunkGenerator extends FlatLevelSource {
    public static final MapCodec<NativePlanetChunkGenerator> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
        FlatLevelGeneratorSettings.CODEC.fieldOf("settings").forGetter(NativePlanetChunkGenerator::settings),
        Codec.LONG.fieldOf("terrain_seed").forGetter(NativePlanetChunkGenerator::seed),
        Codec.INT.fieldOf("terrain_version").forGetter(NativePlanetChunkGenerator::version),
        NativePlanetBiomes.CODEC.codec().optionalFieldOf("planet_biomes").forGetter(g->java.util.Optional.ofNullable(g.planetBiomes)),
        NativePlanetGeneration.CODEC.optionalFieldOf("planet_generation").forGetter(g->g.savedGeneration),
        Codec.BOOL.optionalFieldOf("multi_planet",false).forGetter(NativePlanetChunkGenerator::multiple),
        ResourceLocation.CODEC.optionalFieldOf("planet_type_id").forGetter(NativePlanetChunkGenerator::typeId),
        Codec.STRING.optionalFieldOf("planet_name").forGetter(NativePlanetChunkGenerator::displayName),
        // Authored per-planet name alias; keep planet_name in saved generators for compatibility.
        Codec.STRING.optionalFieldOf("name").forGetter(g->java.util.Optional.<String>empty())
    ).apply(i,(settings,seed,version,biomes,generation,multiple,type,legacy,name)->{
        if(name.isPresent()&&legacy.isPresent()&&!name.equals(legacy))throw new IllegalArgumentException("Conflicting name and planet_name");
        return new NativePlanetChunkGenerator(settings,seed,version,biomes,generation,multiple,type,name.isPresent()?name:legacy);
    }));
    private final java.util.Optional<ResourceLocation> typeId;
    private final java.util.Optional<String> displayName;
    public java.util.Optional<ResourceLocation> typeId(){return typeId;}
    public java.util.Optional<String> displayName(){return displayName;}
    public NativePlanetChunkGenerator identity(java.util.Optional<ResourceLocation> type,java.util.Optional<String> name){return new NativePlanetChunkGenerator(settings(),seed(),version(),java.util.Optional.ofNullable(planetBiomes),savedGeneration,multiple,type,name);}
    private final NativePlanetTerrain terrain;private final NativePlanetBiomes planetBiomes;private final java.util.Optional<NativePlanetGeneration> savedGeneration;private final boolean multiple;
    public NativePlanetChunkGenerator(FlatLevelGeneratorSettings settings,long seed,int version){this(settings,seed,version,java.util.Optional.empty());}
    public NativePlanetChunkGenerator(FlatLevelGeneratorSettings settings,long seed,int version,java.util.Optional<NativePlanetBiomes> biomes){this(settings,seed,version,biomes,java.util.Optional.empty());}
    public NativePlanetChunkGenerator(FlatLevelGeneratorSettings settings,long seed,int version,java.util.Optional<NativePlanetBiomes> biomes,java.util.Optional<NativePlanetGeneration> generation){this(settings,seed,version,biomes,generation,false);}
    public NativePlanetChunkGenerator(FlatLevelGeneratorSettings settings,long seed,int version,java.util.Optional<NativePlanetBiomes> biomes,java.util.Optional<NativePlanetGeneration> generation,boolean multiple){this(settings,seed,version,biomes,generation,multiple,java.util.Optional.empty(),java.util.Optional.empty());}
    public NativePlanetChunkGenerator(FlatLevelGeneratorSettings settings,long seed,int version,java.util.Optional<NativePlanetBiomes> biomes,java.util.Optional<NativePlanetGeneration> generation,boolean multiple,java.util.Optional<ResourceLocation> typeId,java.util.Optional<String> displayName){super(settings);this.typeId=java.util.Objects.requireNonNull(typeId);this.displayName=java.util.Objects.requireNonNull(displayName);displayName.ifPresent(NativeSystemDefinition::validName);if(multiple&&version!=4&&version!=5)throw new IllegalArgumentException("Multi-planet generator version");this.multiple=multiple;if(version==NativePlanetTerrain.ROCK_VERSION&&generation.isEmpty())throw new IllegalArgumentException("Rocky terrain requires saved generation policy");savedGeneration=generation;terrain=new NativePlanetTerrain(seed,version,generation.orElseGet(()->NativePlanetGeneration.defaults(version)));planetBiomes=biomes.map(palette->version==NativePlanetTerrain.ECOLOGY_VERSION&&palette.seed()==seed?palette.on(terrain):palette).orElse(null);if(version==NativePlanetTerrain.ECOLOGY_VERSION?(planetBiomes==null||planetBiomes.seed()!=seed):planetBiomes!=null)throw new IllegalArgumentException("Native biome palette/version/seed mismatch");}
    @Override public net.minecraft.world.level.biome.BiomeSource getBiomeSource(){return planetBiomes==null?super.getBiomeSource():planetBiomes;}
    @Override public CompletableFuture<ChunkAccess> createBiomes(RandomState random,Blender blender,StructureManager structures,ChunkAccess chunk){if(planetBiomes==null)return super.createBiomes(random,blender,structures,chunk);chunk.fillBiomesFromNoise(planetBiomes,null);return CompletableFuture.completedFuture(chunk);}
    public long seed(){return terrain.seed();}public int version(){return terrain.version();}public NativePlanetTerrain terrain(){return terrain;}public NativePlanetGeneration generation(){return terrain.generation();}public boolean multiple(){return multiple;}
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> GENERATORS=DeferredRegister.create(BuiltInRegistries.CHUNK_GENERATOR,"starfold");
    private static final DeferredRegister<MapCodec<? extends net.minecraft.world.level.biome.BiomeSource>> BIOMES=DeferredRegister.create(BuiltInRegistries.BIOME_SOURCE,"starfold");
    public static void initialize(IEventBus modBus){NativePlanetType.initialize(modBus);GENERATORS.register("native_system",()->NativeSystemChunkGenerator.CODEC);BIOMES.register("native_system",()->NativeSystemBiomes.CODEC);GENERATORS.register("native_planet",()->CODEC);BIOMES.register("native_ecology",()->NativePlanetBiomes.CODEC);GENERATORS.register(modBus);BIOMES.register(modBus);}
    @Override protected MapCodec<? extends ChunkGenerator> codec(){return CODEC;}
    @Override public int getMinY(){return Math.floorDiv(terrain.floor(),16)*16;}
    @Override public int getGenDepth(){return NativePlanetTerrain.MAX_Y-getMinY();}
    @Override public int getSeaLevel(){return terrain.sea()==null?getMinY():terrain.sea().level();}
    @Override public int getSpawnHeight(LevelHeightAccessor height){return 130;}
    @Override public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender,RandomState random,StructureManager structures,ChunkAccess chunk){
        var surface=chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);var floor=chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);var pos=new BlockPos.MutableBlockPos();var start=chunk.getPos();
        if(NativeGroundSurfaceIndex.key(start.x,start.z)>=0){var face=CubeSurfaceTopology.Face.values()[Math.floorDiv(start.x,256)];for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            int wx=start.getMinBlockX()+x,wz=start.getMinBlockZ()+z,ground=terrain.groundY(face,wx-face.ordinal()*4096,wz);var column=version()>=NativePlanetTerrain.MINING_VERSION?terrain.column(face,wx-face.ordinal()*4096,wz):null;
            for(int y=terrain.floor();y<=Math.max(terrain.surfaceY(face,wx-face.ordinal()*4096,wz),version()==NativePlanetTerrain.ECOLOGY_VERSION?ground+10:ground);y++){var state=column==null?terrain.state(face,wx-face.ordinal()*4096,y,wz):column.state(y);chunk.setBlockState(pos.set(wx,y,wz),state,false);surface.update(x,y,z,state);floor.update(x,y,z,state);if(!state.getFluidState().isEmpty()&&(y==127||x==0||x==15||z==0||z==15))chunk.markPosForPostprocessing(pos);}
        }}return CompletableFuture.completedFuture(chunk);
    }
    private int ground(int x,int z){var pos=new BlockPos(x,128,z);if(!NativeGroundCoordinates.owned(terrain.shape(),new dev.starfold.BentStripGeometry.Point(x,128,z)))return Integer.MIN_VALUE;var cell=NativeGroundTopology.cell(pos);return terrain.groundY(cell.face(),cell.u(),cell.v());}
    @Override public int getBaseHeight(int x,int z,Heightmap.Types type,LevelHeightAccessor height,RandomState random){int ground=ground(x,z);if(ground==Integer.MIN_VALUE)return height.getMinBuildHeight();var at=NativeGroundTopology.cell(new BlockPos(x,128,z));if(terrain.closure(at.face(),at.u(),at.v()))return Math.clamp(terrain.closureTop()+1,height.getMinBuildHeight(),height.getMaxBuildHeight());if(version()==NativePlanetTerrain.ECOLOGY_VERSION){var cell=NativeGroundTopology.cell(new BlockPos(x,128,z));var col=terrain.column(cell.face(),cell.u(),cell.v());for(int y=Math.min(height.getMaxBuildHeight(),320)-1;y>=Math.max(height.getMinBuildHeight(),terrain.floor());y--)if(type.isOpaque().test(col.state(y)))return y+1;return height.getMinBuildHeight();}return Math.clamp((terrain.sea()!=null&&type.isOpaque().test(terrain.sea().fluid())?terrain.surfaceTop(ground):ground)+1,height.getMinBuildHeight(),height.getMaxBuildHeight());}
    @Override public NoiseColumn getBaseColumn(int x,int z,LevelHeightAccessor height,RandomState random){int ground=ground(x,z);var states=new net.minecraft.world.level.block.state.BlockState[height.getHeight()];var cell=ground==Integer.MIN_VALUE?null:NativeGroundTopology.cell(new BlockPos(x,128,z));var column=cell!=null&&version()>=NativePlanetTerrain.MINING_VERSION?terrain.column(cell.face(),cell.u(),cell.v()):null;for(int i=0;i<states.length;i++)states[i]=ground==Integer.MIN_VALUE?net.minecraft.world.level.block.Blocks.AIR.defaultBlockState():column==null?terrain.state(cell.face(),cell.u(),height.getMinBuildHeight()+i,cell.v()):column.state(height.getMinBuildHeight()+i);return new NoiseColumn(height.getMinBuildHeight(),states);}
}
