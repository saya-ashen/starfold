package dev.starfold.worldgen.plan;

import com.mojang.logging.LogUtils;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.worldgen.PlanetGeneration;
import net.minecraft.Util;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Per-planet background archive; tick readers poll readiness, world generation may join. */
public final class PlanetPlanStore {
    private final PlanetChunkGenerator planet;
    private final StructureSandbox.Context context;
    private final Map<Long,StructureSandbox.Result> replays=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Integer,Map<net.minecraft.world.level.ChunkPos,List<PlanetPlan.Site>>> entityChunks=new java.util.concurrent.ConcurrentHashMap<>();
    public List<PlanetPlan.Site> entitySites(int planetId,net.minecraft.world.level.ChunkPos chunk) {
        return entityChunks.computeIfAbsent(planetId,id->{
            var result=new HashMap<net.minecraft.world.level.ChunkPos,List<PlanetPlan.Site>>();
            var archive=archive();
            for(var site:archive.plan().sites()) {
                var owned=new HashSet<net.minecraft.world.level.ChunkPos>();
                for(var tag:archive.recipes().get(site.id()).getList("entities",Tag.TAG_COMPOUND)) {
                    var spawn=StructureEntities.Spawn.load((CompoundTag)tag);
                    owned.add(StructureEntities.chunk(StructureEntities.project(site,spawn,archive.plan().size(),id,context.registries())));
                }
                for(var target:owned)result.computeIfAbsent(target,k->new ArrayList<>()).add(site);
            }
            result.replaceAll((k,v)->List.copyOf(v));return Map.copyOf(result);
        }).getOrDefault(chunk,List.of());
    }
    private final CompletableFuture<TraditionalPlanetPlanner.Archive> loading;
    public PlanetPlanStore(PlanetChunkGenerator planet,StructureSandbox.Context context,Path file) {
        this(planet,context,file,CompletableFuture.completedFuture(null));
    }
    PlanetPlanStore(PlanetChunkGenerator planet,StructureSandbox.Context context,Path file,CompletableFuture<?> previous) {
        this(planet,context,previous.handle((value,error)->null).thenApplyAsync(ignored->loadArchive(file,planet,context),Util.backgroundExecutor()));
    }
    PlanetPlanStore(PlanetChunkGenerator planet,StructureSandbox.Context context,CompletableFuture<TraditionalPlanetPlanner.Archive> loading) {
        this.planet=planet;this.context=context;this.loading=loading;
    }
    public boolean ready() {
        if(!loading.isDone())return false;
        loading.join(); // Propagate failures instead of silently retrying forever.
        return true;
    }
    public TraditionalPlanetPlanner.Archive archive() { return loading.join(); }
    public PlanetPlan plan() { return archive().plan(); }
    public StructureSandbox.Result replay(PlanetPlan.Site site) {
        return replays.computeIfAbsent(site.id(),id->StructureSandbox.merge(StructureSandbox.runPieces(planet,context,site.face(),site,archive().recipes().get(id),id)));
    }
    private static TraditionalPlanetPlanner.Archive loadArchive(Path file,PlanetChunkGenerator planet,StructureSandbox.Context context) {
        try {
            if(Files.exists(file))return read(file,planet,context);
            long start=System.nanoTime();
            var archive=planet.generation().structures() ? new TraditionalPlanetPlanner(planet,context).generate()
                    : new TraditionalPlanetPlanner.Archive(new PlanetPlan(planet.terrain().shape().size(),planet.terrain().terrainPlan(),List.of(),List.of()),Map.of(),Map.of());
            write(file,planet,archive);
            LogUtils.getLogger().info("Planet plan {}: {} sites, {} ms, {} bytes; skipped {}",planet.seed(),archive.plan().sites().size(),
                    (System.nanoTime()-start)/1_000_000,Files.size(file),archive.skipped());
            return archive;
        } catch(IOException e) { throw new UncheckedIOException("Planet plan "+file,e); }
    }
    public static void write(Path file,PlanetChunkGenerator planet,TraditionalPlanetPlanner.Archive archive) throws IOException {
        var root=new CompoundTag();
        root.putInt("entities_version",1);
        root.putLong("seed",planet.seed());root.putString("generation",planet.generation().toString());
        var compressed=archive.plan().compressed();
        if(compressed.length>PlanetPlan.TRANSFER_LIMIT)throw new IOException("Planet plan exceeds sync budget");
        root.putByteArray("plan",compressed);
        var recipes=new ListTag();
        for(var site:archive.plan().sites()) {
            var entry=new CompoundTag();entry.putLong("site",site.id());entry.put("recipe",archive.recipes().get(site.id()).copy());recipes.add(entry);
        }
        root.put("recipes",recipes);
        var skipped=new CompoundTag();archive.skipped().forEach(skipped::putString);root.put("skipped",skipped);
        Files.createDirectories(file.getParent());
        var temporary=file.resolveSibling(file.getFileName()+".tmp");
        NbtIo.writeCompressed(root,temporary);
        Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    public static TraditionalPlanetPlanner.Archive read(Path file,PlanetChunkGenerator planet,StructureSandbox.Context context) throws IOException {
        var root=NbtIo.readCompressed(file,NbtAccounter.create(64_000_000));
        if(root.getInt("entities_version")!=1)throw new IOException("Unsupported planet entity recipes");
        if(root.getLong("seed")!=planet.seed() || !root.getString("generation").equals(planet.generation().toString()))
            throw new IOException("Planet plan identity mismatch");
        var plan=PlanetPlan.decode(root.getByteArray("plan"));
        if(plan.size()!=planet.terrain().shape().size())throw new IOException("Planet plan size mismatch");
        if(plan.terrain().seed()!=planet.seed()||!plan.terrain().recipe().equals(planet.generation().recipe()))
            throw new IOException("Embedded terrain identity mismatch");
        try { plan.validateGeneration(planet.terrain().floor()); }
        catch(IllegalArgumentException e) { throw new IOException("Plan core shell",e); }
        planet.terrain().initializeTerrain(plan.terrain());
        var list=root.getList("recipes",Tag.TAG_COMPOUND);
        if(list.size()!=plan.sites().size())throw new IOException("Planet recipe count");
        var recipes=new TreeMap<Long,CompoundTag>();
        for(int i=0;i<list.size();i++) {
            var entry=list.getCompound(i);var recipe=entry.getCompound("recipe");
            if(recipes.put(entry.getLong("site"),recipe)!=null)throw new IOException("Duplicate planet recipe");
        }
        int[] verificationBudget={32_000_000};
        for(var site:plan.sites()) {
            var recipe=recipes.get(site.id());
            if(recipe==null)throw new IOException("Missing planet recipe");
            var pieces=recipe.getList("pieces",Tag.TAG_COMPOUND);
            if(pieces.size()!=site.pieces().size())throw new IOException("Planet recipe piece count");
            try {
                for(int i=0;i<pieces.size();i++) {
                    var tag=pieces.getCompound(i);var box=tag.getIntArray("BB");
                    if(box.length!=6 || box[0]>box[3] || box[1]>box[4] || box[2]>box[5]
                            || Math.abs((long)box[0]-site.u())>256 || Math.abs((long)box[3]-site.u())>256
                            || Math.abs((long)box[2]-site.v())>256 || Math.abs((long)box[5]-site.v())>256
                            || box[1]<planet.terrain().floor() || box[4]>=320)throw new IOException("Planet recipe bounds");
                    for(String dimension:List.of("Width","Height","Depth","Num"))
                        if(tag.getInt(dimension)<0 || tag.getInt(dimension)>256)throw new IOException("Planet recipe dimension");
                    var loaded=StructureSandbox.piece(context,tag);
                    // Vanilla jigsaw reconstructs its expanded BB; every other recipe field must be canonical.
                    var canonical=StructureSandbox.recipe(loaded,context);
                    var expected=tag.copy();expected.remove("BB");canonical.remove("BB");
                    if(!canonical.equals(expected))throw new IOException("Noncanonical planet recipe: "+tag.getString("id"));
                    var actual=loaded.getBoundingBox();
                    if(actual.getXSpan()>256 || actual.getZSpan()>256 || actual.minY()<planet.terrain().floor() || actual.maxY()>=320
                            || Math.abs((long)actual.minX()-site.u())>256 || Math.abs((long)actual.maxX()-site.u())>256
                            || Math.abs((long)actual.minZ()-site.v())>256 || Math.abs((long)actual.maxZ()-site.v())>256)
                        throw new IOException("Planet loaded recipe bounds");
                    if(loaded instanceof net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces.MineShaftCorridor
                            && tag.getInt("Num")!=Math.max(actual.getXSpan(),actual.getZSpan())/5)throw new IOException("Planet corridor sections");
                }
                var replay=StructureSandbox.runPieces(planet,context,site.face(),site,recipe,site.id(),verificationBudget);
                if(!StructureEntities.save(replay).equals(recipe.getList("entities",Tag.TAG_COMPOUND)))throw new IOException("Planet entity recipe differs from recording");
                for(int i=0;i<replay.size();i++) {
                    var piece=site.pieces().get(i);var blocks=new ArrayList<PlanetPlan.Voxel>();
                    replay.get(i).blocks().forEach((p,state)->{
                        if(p.getY()<=planet.terrain().floor())throw new IllegalArgumentException("Recipe writes core shell");
                        blocks.add(new PlanetPlan.Voxel(p.getX()-piece.origin().getX(),p.getY()-piece.origin().getY(),
                                p.getZ()-piece.origin().getZ(),state));
                    });
                    if(!new PlanetPlan.Template(blocks).equals(plan.templates().get(piece.template())))throw new IOException("Planet recipe differs from fixed piece "+i);
                }
            } catch(RuntimeException e) { throw new IOException("Invalid planet recipe",e); }
        }
        var skipped=new TreeMap<String,String>();var tags=root.getCompound("skipped");
        for(var key:tags.getAllKeys())skipped.put(key,tags.getString(key));
        return new TraditionalPlanetPlanner.Archive(plan,recipes,skipped);
    }
    /** World startup waits for home before accepting players; other planets keep loading in the background. */
    public static void prepareHome(SystemChunkGenerator system) {
        var store=system.planet(0).planStore();
        if(store!=null)store.archive();
    }
    public static boolean ready(ServerLevel level,int id) {
        return !(level.getChunkSource().getGenerator() instanceof SystemChunkGenerator system) || system.planet(id).planReady();
    }
    public static void bind(ServerLevel level,SystemChunkGenerator system) {
        var folder=DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data/planet-plans");
        var context=new StructureSandbox.Context(level.registryAccess(),level.getServer().getStructureManager(),level.getChunkSource().randomState());
        // Home (slot zero), then occupied planets, precede the rest. Do not saturate worldgen workers.
        var order=new LinkedHashSet<Integer>();
        order.add(0);
        for(var player:level.players())
            if(!dev.starfold.sky.Space.in(player))order.add(dev.starfold.planet.PlanetSystem.planet(player));
        for(int id=0;id<system.count();id++)order.add(id);
        CompletableFuture<?> previous=CompletableFuture.completedFuture(null);
        for(int id:order) {
            var planet=system.planet(id);
            if(planet.generation().surfaceVersion()!=PlanetGeneration.SURFACE_VERSION || planet.planStore()!=null)continue;
            var store=new PlanetPlanStore(planet,context,folder.resolve(id+".dat"),previous);
            planet.initializePlan(store);
            previous=store.loading;
        }
    }
}
