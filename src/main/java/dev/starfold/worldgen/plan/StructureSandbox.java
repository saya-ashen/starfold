package dev.starfold.worldgen.plan;

import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.worldgen.PlanetTerrain;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.structures.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Predicate;

/** Fresh vanilla pieces against fixed L1/L2. Replay never reads chunk load order. */
public final class StructureSandbox {
    public record Context(RegistryAccess registries, StructureTemplateManager templates, RandomState random) {
        public StructurePieceSerializationContext serialization() {
            return new StructurePieceSerializationContext(null,registries,templates);
        }
    }
    public record Result(Map<BlockPos,BlockState> blocks, Map<BlockPos,BlockEntity> entities, List<StructureEntities.Spawn> spawns) {}
    // Vanilla publishes this lazy map before filling it; class initialization publishes all tables safely.
    static {
        for(var group:com.mojang.math.OctahedralGroup.values())for(var direction:Direction.values())group.rotate(direction);
    }
    private StructureSandbox() {}
    static final class Foundation {
        private final PlanetTerrain terrain;
        private final Face face;
        private final int u,v,ground;
        private Integer top;
        int top() { if(top==null)top=terrain.foundationTop(face,u,v,ground);return top; }
        private final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<BlockState> states=new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
        Foundation(PlanetTerrain terrain,Face face,int u,int v,PlanetPlan.Site site) {
            this.terrain=terrain;var c=canonical(terrain,face,u,v);this.face=c.face();this.u=c.u();this.v=c.v();
            this.ground=ground(terrain,face,u,v,site);
        }
        BlockState state(int y) {
            var cached=states.get(y);
            if(cached!=null)return cached;
            int low=y<=ground?Math.max(terrain.floor(),Math.floorDiv(y,16)*16):y;
            int high=y<=ground?Math.min(ground+1,low+16):y+1;
            var band=terrain.foundationBand(face,u,v,ground,low,high);
            boolean bounded=terrain.terrainPlan().localVolumes(face,u,v).isEmpty();
            // A truncated underground band may carry cap snow; a one-cell underground read never does.
            for(int k=band.bottom();k<band.end();k++) {
                var state=band.state(k);
                if(bounded && k<=ground && state.is(Blocks.SNOW))state=terrain.foundationBand(face,u,v,ground,k,k+1).state(k);
                states.put(k,state);
            }
            return states.get(y);
        }
    }
    public static CompoundTag recipe(StructurePiece piece,Context context) {
        var recipe=piece.createTag(context.serialization());
        var sources=new CompoundTag();
        if(piece instanceof TemplateStructurePiece template)sources.putString(recipe.getString("Template"),templateDigest(template.template()));
        collectSources(recipe.getCompound("pool_element"),context,sources);
        recipe.put("plan_sources",sources);
        return recipe;
    }
    private static final ThreadLocal<Map<StructureTemplate,String>> SOURCE_DIGESTS=new ThreadLocal<>();
    private static final ThreadLocal<Map<PlanetChunkGenerator,EnumMap<Face,FlatLevelSource>>> GENERATORS=new ThreadLocal<>();
    static <T> T cacheSources(java.util.function.Supplier<T> operation) {
        SOURCE_DIGESTS.set(new IdentityHashMap<>());GENERATORS.set(new IdentityHashMap<>());
        try { return operation.get(); } finally { SOURCE_DIGESTS.remove();GENERATORS.remove(); }
    }
    private static String templateDigest(StructureTemplate template) {
        var cache=SOURCE_DIGESTS.get();
        return cache==null ? computeTemplateDigest(template) : cache.computeIfAbsent(template,StructureSandbox::computeTemplateDigest);
    }
    private static String computeTemplateDigest(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template) {
        try {
            var bytes=new java.io.ByteArrayOutputStream();
            NbtIo.write(template.save(new CompoundTag()),new java.io.DataOutputStream(bytes));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        catch(java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static void collectSources(Tag tag,Context context,CompoundTag sources) {
        if(tag instanceof CompoundTag compound) {
            if(compound.contains("location",Tag.TAG_STRING)) {
                String id=compound.getString("location");
                sources.putString(id,templateDigest(context.templates.getOrCreate(ResourceLocation.parse(id))));
            }
            for(String key:compound.getAllKeys())collectSources(compound.get(key),context,sources);
        } else if(tag instanceof ListTag list)for(var child:list)collectSources(child,context,sources);
    }
    public static StructurePiece piece(Context context,CompoundTag recipe) {
        var type=BuiltInRegistries.STRUCTURE_PIECE.getOptional(ResourceLocation.parse(recipe.getString("id"))).orElseThrow();
        var sources=recipe.getCompound("plan_sources");
        for(String id:sources.getAllKeys())if(!sources.getString(id).equals(templateDigest(context.templates.getOrCreate(ResourceLocation.parse(id)))))
            throw new IllegalArgumentException("Saved planet structure template changed: "+id);
        return type.load(context.serialization(),recipe.copy());
    }
    /** ServerLevel-dependent branches record instructions at the same vanilla positions/RNG boundary. */
    private static StructurePiece recordingPiece(Context context,CompoundTag recipe) {
        return recordingPiece(piece(context,recipe),context,recipe);
    }
    private static StructurePiece recordingPiece(StructurePiece loaded,Context context,CompoundTag recipe) {
        if(loaded instanceof SwampHutPiece) {
            var copy=recipe.copy();copy.putBoolean("Witch",true);copy.putBoolean("Cat",true);
            return new SwampHutPiece(copy) {
                @Override public void postProcess(WorldGenLevel level,StructureManager manager,ChunkGenerator generator,RandomSource random,
                                                   BoundingBox area,ChunkPos chunk,BlockPos origin) {
                    super.postProcess(level,manager,generator,random,area,chunk,origin);
                    var pos=getWorldPos(2,2,5);
                    if(area.isInside(pos)) {
                        var at=new net.minecraft.world.phys.Vec3(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
                        if(!recipe.getBoolean("Witch"))StructureEntities.record(StructureEntities.entity("minecraft:witch",at,true),true,Rotation.NONE,Mirror.NONE);
                        if(!recipe.getBoolean("Cat"))StructureEntities.record(StructureEntities.entity("minecraft:cat",at,true),true,Rotation.NONE,Mirror.NONE);
                    }
                }
            };
        }
        if(loaded instanceof OceanRuinPieces.OceanRuinPiece)return new OceanRuinPieces.OceanRuinPiece(context.templates,ResourceLocation.parse(recipe.getString("Template")),
                new BlockPos(recipe.getInt("TPX"),recipe.getInt("TPY"),recipe.getInt("TPZ")),Rotation.valueOf(recipe.getString("Rot")),
                recipe.getFloat("Integrity"),OceanRuinStructure.Type.valueOf(recipe.getString("BiomeType")),recipe.getBoolean("IsLarge")) {
            @Override protected void handleDataMarker(String marker,BlockPos pos,ServerLevelAccessor level,RandomSource random,BoundingBox area) {
                if(marker.equals("drowned")) {
                    StructureEntities.record(StructureEntities.entity("minecraft:drowned",net.minecraft.world.phys.Vec3.atLowerCornerOf(pos),true),true,Rotation.NONE,Mirror.NONE);
                    level.setBlock(pos,pos.getY()>((WorldGenLevel)level).getSeaLevel() ? Blocks.AIR.defaultBlockState() : Blocks.WATER.defaultBlockState(),2);
                }
                else super.handleDataMarker(marker,pos,level,random,area);
            }
        };
        if(loaded instanceof MineshaftPieces.MineShaftCorridor)return new MineshaftPieces.MineShaftCorridor(recipe) {
            @Override protected boolean createChest(WorldGenLevel level,BoundingBox area,RandomSource random,int x,int y,int z,
                                                     net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable> loot) {
                var pos=getWorldPos(x,y,z);
                if(!area.isInside(pos) || !level.getBlockState(pos).isAir() || level.getBlockState(pos.below()).isAir())return false;
                placeBlock(level,Blocks.RAIL.defaultBlockState().setValue(net.minecraft.world.level.block.RailBlock.SHAPE,
                        random.nextBoolean() ? net.minecraft.world.level.block.state.properties.RailShape.NORTH_SOUTH : net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST),x,y,z,area);
                var entity=StructureEntities.entity("minecraft:chest_minecart",new net.minecraft.world.phys.Vec3(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5),false);
                entity.putString("LootTable",loot.location().toString());entity.putLong("LootTableSeed",random.nextLong());
                StructureEntities.record(entity,false,Rotation.NONE,Mirror.NONE);return true;
            }
        };
        return loaded;
    }
    // Vanilla CappedProcessor asks ServerLevel solely for its seed; this sandbox has the same fixed seed.
    private static StructureProcessor sandboxProcessor(StructureProcessor processor,long seed) {
        if(!(processor instanceof CappedProcessor capped))return processor;
        var tag=CappedProcessor.CODEC.codec().encodeStart(NbtOps.INSTANCE,capped).getOrThrow();
        var data=(CompoundTag)tag;
        var delegate=StructureProcessorType.SINGLE_CODEC.parse(NbtOps.INSTANCE,data.get("delegate")).getOrThrow();
        var limit=net.minecraft.util.valueproviders.IntProvider.POSITIVE_CODEC.parse(NbtOps.INSTANCE,data.get("limit")).getOrThrow();
        return new StructureProcessor() {
            @Override protected StructureProcessorType<?> getType() { return StructureProcessorType.CAPPED; }
            @Override public List<StructureTemplate.StructureBlockInfo> finalizeProcessing(ServerLevelAccessor level,BlockPos origin,BlockPos pivot,
                    List<StructureTemplate.StructureBlockInfo> original,List<StructureTemplate.StructureBlockInfo> processed,StructurePlaceSettings settings) {
                if(limit.getMaxValue()==0 || processed.isEmpty() || original.size()!=processed.size())return processed;
                var random=RandomSource.create(seed).forkPositional().at(origin);
                int maximum=Math.min(limit.sample(random),processed.size());
                if(maximum<1)return processed;
                var shuffled=net.minecraft.Util.toShuffledList(java.util.stream.IntStream.range(0,processed.size()),random);
                int changed=0;
                for(int i:shuffled) {
                    if(changed>=maximum)break;
                    var before=processed.get(i);var after=delegate.processBlock(level,origin,pivot,original.get(i),before,settings);
                    if(after!=null && !before.equals(after)) {changed++;processed.set(i,after);}
                }
                return processed;
            }
        };
    }
    public static CompoundTag recipes(List<StructurePiece> pieces,Context context) {
        var root=new CompoundTag();var list=new ListTag();
        for(var piece:pieces)list.add(recipe(piece,context));
        root.put("pieces",list);return root;
    }
    public static Cell canonical(PlanetTerrain terrain,Face face,int u,int v) {
        return terrain.shape().topology().canonical(new Cell(face,u,0,v)).cell();
    }
    public static int ground(PlanetTerrain terrain,Face face,int u,int v,PlanetPlan.Site site) {
        var c=canonical(terrain,face,u,v);
        int raw=terrain.groundY(c.face(),c.u(),c.v());
        return site==null ? raw : PlanetPlan.ground(site,u,v,raw);
    }
    public static FlatLevelSource generator(PlanetChunkGenerator planet,Face face) {
        var cache=GENERATORS.get();
        return cache==null?createGenerator(planet,face):cache.computeIfAbsent(planet,k->new EnumMap<>(Face.class))
            .computeIfAbsent(face,f->createGenerator(planet,f));
    }
    private static FlatLevelSource createGenerator(PlanetChunkGenerator planet,Face face) {
        var terrain=planet.terrain();
        var biomes=new BiomeSource() {
            @Override protected com.mojang.serialization.MapCodec<? extends BiomeSource> codec() { throw new UnsupportedOperationException(); }
            @Override protected java.util.stream.Stream<Holder<Biome>> collectPossibleBiomes() { return planet.getBiomeSource().possibleBiomes().stream(); }
            @Override public Holder<Biome> getNoiseBiome(int x,int y,int z,Climate.Sampler sampler) {
                var c=canonical(terrain,face,x*4+2,z*4+2);
                return planet.getBiomeSource().getNoiseBiome((c.face().ordinal()*4096+c.u())/4,y,c.v()/4,sampler);
            }
        };
        return new FlatLevelSource(planet.settings()) {
            private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap floorHeights=new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap surfaceHeights=new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            @Override public BiomeSource getBiomeSource() { return biomes; }
            @Override public int getBaseHeight(int x,int z,Heightmap.Types type,LevelHeightAccessor height,RandomState random) {
                var cache=type==Heightmap.Types.OCEAN_FLOOR_WG || type==Heightmap.Types.OCEAN_FLOOR?floorHeights:surfaceHeights;
                long key=ChunkPos.asLong(z,x);
                return cache.computeIfAbsent(key,k->{
                    var c=canonical(terrain,face,x,z);int ground=terrain.groundY(c.face(),c.u(),c.v());
                    return (cache==floorHeights?ground:terrain.foundationTop(c.face(),c.u(),c.v(),ground))+1;
                });
            }
            @Override public NoiseColumn getBaseColumn(int x,int z,LevelHeightAccessor height,RandomState random) {
                var c=canonical(terrain,face,x,z);int ground=terrain.groundY(c.face(),c.u(),c.v());
                var col=terrain.foundationColumn(c.face(),c.u(),c.v(),ground);
                var blocks=new BlockState[height.getHeight()];
                for(int i=0;i<blocks.length;i++)blocks[i]=col.state(i+height.getMinBuildHeight());
                return new NoiseColumn(height.getMinBuildHeight(),blocks);
            }
            @Override public int getMinY() { return terrain.floor(); }
            @Override public int getGenDepth() { return PlanetTerrain.MAX_Y-terrain.floor(); }
            @Override public int getSeaLevel() { return planet.getSeaLevel(); }
        };
    }
    @SuppressWarnings("unchecked")
    public static Result run(PlanetChunkGenerator planet,Context context,Face face,PlanetPlan.Site site,
                             CompoundTag recipe,long seed) {
        return run(planet,context,face,site,recordingPiece(context,recipe),seed,new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(),List.of(),new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(),new int[]{16_000_000});
    }
    public static List<Result> runPieces(PlanetChunkGenerator planet,Context context,Face face,PlanetPlan.Site site,CompoundTag recipes,long seed) {
        return runPieces(planet,context,face,site,recipes,seed,new int[]{16_000_000});
    }
    static List<Result> runPieces(PlanetChunkGenerator planet,Context context,Face face,PlanetPlan.Site site,CompoundTag recipes,long seed,int[] budget) {
        var pieces=new ArrayList<StructurePiece>();
        for(var tag:recipes.getList("pieces",Tag.TAG_COMPOUND))pieces.add(recordingPiece(context,(CompoundTag)tag));
        return runPieces(planet,context,face,site,pieces,seed,budget);
    }
    static List<Result> recordPieces(PlanetChunkGenerator planet,Context context,Face face,PlanetPlan.Site site,CompoundTag recipes,List<StructurePiece> generated,long seed) {
        var pieces=new ArrayList<StructurePiece>();var tags=recipes.getList("pieces",Tag.TAG_COMPOUND);
        for(int i=0;i<generated.size();i++)pieces.add(recordingPiece(generated.get(i),context,tags.getCompound(i)));
        return runPieces(planet,context,face,site,pieces,seed,new int[]{16_000_000});
    }
    private static List<Result> runPieces(PlanetChunkGenerator planet,Context context,Face face,PlanetPlan.Site site,List<StructurePiece> pieces,long seed,int[] budget) {
        var results=new ArrayList<Result>();var prior=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState>();
        var bands=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Foundation>();
        for(int i=0;i<pieces.size();i++) {
            var result=run(planet,context,face,site,pieces.get(i),dev.starfold.worldgen.surface.Scatter.mix(seed+i),prior,
                    i==pieces.size()-1 ? pieces : List.of(),bands,budget);
            results.add(result);result.blocks.forEach((pos,state)->prior.put(pos.asLong(),state));
            if(results.stream().mapToInt(r->r.spawns.size()).sum()>4096)throw new IllegalArgumentException("Site entity expansion limit");
        }
        return List.copyOf(results);
    }
    public static Result merge(List<Result> results) {
        var blocks=new it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<BlockPos,BlockState>();var entities=new HashMap<BlockPos,BlockEntity>();
        for(var result:results)for(var e:result.blocks.entrySet()) {
            blocks.put(e.getKey(),e.getValue());entities.remove(e.getKey());
            if(result.entities.containsKey(e.getKey()))entities.put(e.getKey(),result.entities.get(e.getKey()));
        }
        return new Result(Collections.unmodifiableMap(blocks),Collections.unmodifiableMap(entities),results.stream().flatMap(r->r.spawns.stream()).toList());
    }
    private static Foundation foundation(PlanetTerrain terrain,Face face,PlanetPlan.Site site,
            it.unimi.dsi.fastutil.longs.Long2ObjectMap<Foundation> bands,int u,int v) {
        long key=BlockPos.asLong(u,site==null?1:0,v);
        return bands.computeIfAbsent(key,k->new Foundation(terrain,face,u,v,site));
    }
    private static Result run(PlanetChunkGenerator planet,Context context,Face face,PlanetPlan.Site site,
                              StructurePiece piece,long seed,it.unimi.dsi.fastutil.longs.Long2ObjectMap<BlockState> prior,List<StructurePiece> completed,it.unimi.dsi.fastutil.longs.Long2ObjectMap<Foundation> bands,int[] budget) {
        var terrain=planet.terrain();
        if(piece instanceof TemplateStructurePiece template) {
            var processors=template.placeSettings().getProcessors().stream().map(p->sandboxProcessor(p,terrain.seed())).toList();
            template.placeSettings().clearProcessors();processors.forEach(template.placeSettings()::addProcessor);
        }
        var substrate=piece instanceof PoolElementStructurePiece pool
                && pool.getElement().getProjection()==net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.Projection.TERRAIN_MATCHING ? null : site;
        var blocks=new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<BlockState>();
        var entities=new HashMap<BlockPos,BlockEntity>();

        java.util.function.Function<BlockPos,BlockState> read=p->{
            if(--budget[0]<0)throw new IllegalArgumentException("Structure sandbox operation budget");
            long position=p.asLong();var state=blocks.get(position);if(state!=null)return state;
            state=prior.get(position);if(state!=null)return state;
            if(p.getY()<terrain.floor() || p.getY()>=PlanetTerrain.MAX_Y)return Blocks.AIR.defaultBlockState();
            return foundation(terrain,face,substrate,bands,p.getX(),p.getZ()).state(p.getY());
        };
        var spawns=new ArrayList<StructureEntities.Spawn>();
        var random=RandomSource.create(seed);
        int[] writes={0};
        var chunks=new HashMap<ChunkPos,ProtoChunk>();
        WorldGenLevel level=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},
                (proxy,method,args)->switch(method.getName()) {
                    case "getBlockState" -> read.apply((BlockPos)args[0]);
                    case "getChunk" -> {
                        var pos=args[0] instanceof BlockPos p ? new ChunkPos(p) : new ChunkPos((int)args[0],(int)args[1]);
                        yield chunks.computeIfAbsent(pos,p->new ProtoChunk(p,UpgradeData.EMPTY,
                                LevelHeightAccessor.create(terrain.floor(),PlanetTerrain.MAX_Y-terrain.floor()),context.registries.registryOrThrow(Registries.BIOME),null) {
                            @Override public BlockState getBlockState(BlockPos at) { return read.apply(at); }
                        });
                    }
                    case "getFluidState" -> read.apply((BlockPos)args[0]).getFluidState();
                    case "isEmptyBlock" -> read.apply((BlockPos)args[0]).isAir();
                    case "isStateAtPosition" -> ((Predicate<BlockState>)args[1]).test(read.apply((BlockPos)args[0]));
                    case "isFluidAtPosition" -> ((Predicate<net.minecraft.world.level.material.FluidState>)args[1]).test(read.apply((BlockPos)args[0]).getFluidState());
                    case "setBlock" -> {
                        var p=((BlockPos)args[0]).immutable();var state=(BlockState)args[1];
                        if(--budget[0]<0)throw new IllegalArgumentException("Structure sandbox operation budget");
                        if(++writes[0]>4_000_000 || Math.abs((long)p.getX()-site.u())>256 || Math.abs((long)p.getZ()-site.v())>256
                                || p.getY()<terrain.floor() || p.getY()>=PlanetTerrain.MAX_Y)throw new IllegalArgumentException("Structure recording bounds");
                        blocks.put(p.asLong(),state);entities.remove(p);
                        if(state.getBlock() instanceof EntityBlock b)entities.put(p,b.newBlockEntity(p,state));
                        yield true;
                    }
                    case "getBlockEntity" -> args.length==1 ? entities.get(args[0]) : Optional.ofNullable(entities.get(args[0]));
                    case "registryAccess" -> context.registries;
                    case "dimensionType" -> context.registries.registryOrThrow(Registries.DIMENSION_TYPE).get(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD);
                    case "holderLookup" -> context.registries.lookupOrThrow((net.minecraft.resources.ResourceKey)args[0]);
                    case "getMinBuildHeight" -> terrain.floor();
                    case "getMaxBuildHeight" -> PlanetTerrain.MAX_Y;
                    case "getHeight" -> {
                        if(args==null || args.length==0)yield PlanetTerrain.MAX_Y-terrain.floor();
                        var band=foundation(terrain,face,substrate,bands,(int)args[1],(int)args[2]);
                        yield ((Heightmap.Types)args[0]).isOpaque().test(Blocks.WATER.defaultBlockState())?band.top()+1:band.ground+1;
                    }
                    case "getHeightmapPos" -> {
                        var p=(BlockPos)args[1];var band=foundation(terrain,face,substrate,bands,p.getX(),p.getZ());
                        yield new BlockPos(p.getX(),band.top()+1,p.getZ());
                    }
                    case "getSeed" -> terrain.seed();
                    case "getSeaLevel" -> planet.getSeaLevel();
                    case "getLevel" -> null;
                    case "getBrightness" -> 0;
                    case "getRawBrightness", "getMaxLocalRawBrightness" -> 15;
                    case "canSeeSky" -> {
                        var p=(BlockPos)args[0];yield p.getY()>foundation(terrain,face,substrate,bands,p.getX(),p.getZ()).ground;
                    }
                    case "getRandom" -> random;
                    case "isClientSide" -> false;
                    case "ensureCanWrite", "hasChunk", "hasChunkAt", "isLoaded", "isAreaLoaded" -> true;
                    case "isOutsideBuildHeight" -> args[0] instanceof BlockPos p ? p.getY()<terrain.floor() || p.getY()>=PlanetTerrain.MAX_Y
                            : (int)args[0]<terrain.floor() || (int)args[0]>=PlanetTerrain.MAX_Y;
                    case "getBiome" -> {
                        var p=(BlockPos)args[0];var c=canonical(terrain,face,p.getX(),p.getZ());
                        yield planet.getBiomeSource().getNoiseBiome((c.face().ordinal()*4096+c.u())/4,p.getY()/4,c.v()/4,context.random.sampler());
                    }
                    case "scheduleTick", "setCurrentlyGenerating", "blockUpdated", "neighborChanged" -> null;
                    case "addFreshEntity", "addFreshEntityWithPassengers" -> {
                        var tag=new CompoundTag();var entity=(net.minecraft.world.entity.Entity)args[0];entity.save(tag);
                        if(entity instanceof net.minecraft.world.entity.decoration.BlockAttachedEntity attached) {
                            var anchor=attached.getPos();tag.putInt("TileX",anchor.getX());tag.putInt("TileY",anchor.getY());tag.putInt("TileZ",anchor.getZ());
                        }
                        StructureEntities.record(tag,false,Rotation.NONE,Mirror.NONE);yield true;
                    }
                    case "toString" -> "PlanetStructureSandbox";
                    default -> throw new UnsupportedOperationException("structure sandbox: "+method.getName());
                });
        var box=piece.getBoundingBox();
        var area=new BoundingBox(box.minX()-32,terrain.floor(),box.minZ()-32,box.maxX()+32,PlanetTerrain.MAX_Y-1,box.maxZ()+32);
        StructureEntities.begin(spawns,seed);
        try {
            piece.postProcess(level,null,generator(planet,face),random,area,new ChunkPos(box.getCenter()),box.getCenter());
            if(!completed.isEmpty()) {
                var all=BoundingBox.encapsulatingBoxes(completed.stream().map(StructurePiece::getBoundingBox).toList()).orElseThrow();
                var allArea=new BoundingBox(all.minX()-32,terrain.floor(),all.minZ()-32,all.maxX()+32,PlanetTerrain.MAX_Y-1,all.maxZ()+32);
                context.registries.registryOrThrow(Registries.STRUCTURE).get(site.structure()).afterPlace(level,null,generator(planet,face),random,
                        allArea,new ChunkPos(all.getCenter()),new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(completed));
            }
        } finally { StructureEntities.end(); }
        for(var spawn:spawns) {
            var pos=spawn.nbt().getList("Pos",Tag.TAG_DOUBLE);
            if(Math.abs(pos.getDouble(0)-site.u())>256 || Math.abs(pos.getDouble(2)-site.v())>256
                    || pos.getDouble(1)<=terrain.floor() || pos.getDouble(1)>=PlanetTerrain.MAX_Y)
                throw new IllegalArgumentException("Structure entity outside recording bounds");
            StructureEntities.project(site,spawn,terrain.shape().size(),0,context.registries);
        }
        var recorded=new LinkedHashMap<BlockPos,BlockState>();
        for(var entry:blocks.long2ObjectEntrySet())recorded.put(BlockPos.of(entry.getLongKey()),entry.getValue());
        return new Result(Collections.unmodifiableMap(recorded),Collections.unmodifiableMap(entities),List.copyOf(spawns));
    }
}
