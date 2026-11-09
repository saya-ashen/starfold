package dev.starfold.worldgen.plan;

import dev.starfold.worlds.fixtures.ExperienceFixtures;

import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.planet.*;
import dev.starfold.universe.*;
import dev.starfold.worldgen.*;
import dev.starfold.worldgen.surface.SurfaceFunction;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.structures.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PlanetPlanTest {
    private static dev.starfold.worldgen.plan.terrain.TerrainPlan fixtureTerrain;
    @BeforeAll static void boot() throws Exception { PlanetPlanFixtures.boot(); fixtureTerrain=PlanetPlanFixtures.planet(512,"plains").terrain().terrainPlan(); }
    @AfterAll static void close() throws Exception { PlanetPlanFixtures.close(); fixtureTerrain=null; }

    @Test void bindingQueuesBackgroundLoadsInPriorityOrderBeforeAnyReaderJoins(@org.junit.jupiter.api.io.TempDir Path folder) throws Exception {
        var first=PlanetPlanFixtures.planet(512,"plains");var second=PlanetPlanFixtures.planet(512,"plains");
        var archive=new TraditionalPlanetPlanner.Archive(new PlanetPlan(512,fixtureTerrain,List.of(),List.of()),Map.of(),Map.of());
        var a=folder.resolve("first.dat");var b=folder.resolve("second.dat");
        PlanetPlanStore.write(a,first,archive);PlanetPlanStore.write(b,second,archive);
        var gate=new java.util.concurrent.CompletableFuture<Void>();
        var one=new PlanetPlanStore(first,PlanetPlanFixtures.context,a,gate);
        // The next slot uses the preceding archive future in production; this gate models that dependency.
        assertFalse(one.ready());gate.complete(null);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),()->{
            while(!one.ready())Thread.sleep(1);
        });
        var two=new PlanetPlanStore(second,PlanetPlanFixtures.context,b);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),()->{
            while(!two.ready())Thread.sleep(1);
        });
        assertEquals(archive.plan(),one.plan());assertEquals(archive.plan(),two.plan());
        var source=dev.starfold.Sources.text("PlanetPlanStore");
        assertTrue(source.indexOf("order.add(0);")<source.indexOf("for(var player:level.players())"));
        assertTrue(source.indexOf("order.add(0);")<source.indexOf("for(int id=0;id<system.count();id++)order.add(id);"));
        assertTrue(source.contains("new PlanetPlanStore(planet,context,folder.resolve(id+\".dat\"),previous)"));
        assertTrue(source.contains("previous=store.loading;"));
        assertTrue(source.contains("if(!dev.starfold.sky.Space.in(player))order.add(dev.starfold.planet.PlanetSystem.planet(player));"));
        assertFalse(source.contains("Suppliers.memoize"));
    }

    @Test void startupWaitsForHomeBeforeLoginButDoesNotWaitForOtherPlanets() throws Exception {
        var home=PlanetPlanFixtures.planet(512,"plains");
        var other=PlanetPlanFixtures.planet(512,"plains");
        var loading=new java.util.concurrent.CompletableFuture<TraditionalPlanetPlanner.Archive>();
        var background=new java.util.concurrent.CompletableFuture<TraditionalPlanetPlanner.Archive>();
        home.initializePlan(new PlanetPlanStore(home,PlanetPlanFixtures.context,loading));
        other.initializePlan(new PlanetPlanStore(other,PlanetPlanFixtures.context,background));
        var system=new SystemChunkGenerator(PlanetStorage.LAYOUT,List.of(home,other));
        var archive=new TraditionalPlanetPlanner.Archive(new PlanetPlan(512,fixtureTerrain,List.of(),List.of()),Map.of(),Map.of());
        var started=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var startup=executor.submit(()->{started.countDown();PlanetPlanStore.prepareHome(system);});
            try {
                assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,()->startup.get(100,java.util.concurrent.TimeUnit.MILLISECONDS),
                        "unfinished home must keep startup behind the loading screen");
                assertFalse(home.planReady());
            } finally { loading.complete(archive); }
            startup.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(home.planReady());
            assertFalse(other.planReady(),"other planets must not delay login");
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->PlanetPlanStore.prepareHome(system),
                    "a ready home on rejoin needs no extra wait");
        }
    }

    @Test void vanillaGenerationNotifiesPoisButProtoToFullUpgradeDoesNotRepairMissingNotifications() throws Exception {
        var region=bytecode("net/minecraft/server/level/WorldGenRegion");
        assertTrue(region.methods.stream().filter(m->m.name.equals("setBlock")).anyMatch(m->calls(m,"onBlockStateChange")));
        var server=bytecode("net/minecraft/server/level/ServerLevel");
        assertTrue(server.methods.stream().filter(m->m.name.equals("onBlockStateChange")).anyMatch(m->calls(m,"forState")));
        assertTrue(server.methods.stream().filter(m->m.name.startsWith("lambda$onBlockStateChange$")).anyMatch(m->calls(m,"execute")));
        var serializer=bytecode("net/minecraft/world/level/chunk/storage/ChunkSerializer");
        assertTrue(serializer.methods.stream().filter(m->m.name.equals("read")).anyMatch(m->calls(m,"checkConsistencyWithBlocks")));
        var full=bytecode("net/minecraft/world/level/chunk/LevelChunk");
        assertFalse(full.methods.stream().filter(m->m.name.equals("<init>")).anyMatch(m->calls(m,"checkConsistencyWithBlocks")));
        var source=dev.starfold.Sources.text("SystemChunkGenerator");
        assertTrue(source.indexOf("PlanetDecoration.generatedPois(")<source.indexOf("PlanetDecoration.apply("));
    }
    private static org.objectweb.asm.tree.ClassNode bytecode(String name) throws IOException {
        try(var in=net.minecraft.world.entity.Entity.class.getResourceAsStream("/"+name+".class")) {
            assertNotNull(in);var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(in).accept(node,0);return node;
        }
    }
    private static boolean calls(org.objectweb.asm.tree.MethodNode method,String name) {
        for(var instruction:method.instructions)
            if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals(name))return true;
        return false;
    }

    @Test void unfinishedPlanDefersProfilesWithoutBlockingAndPublishesOnlyAfterCompletion() throws Exception {
        var planet=PlanetPlanFixtures.planet(512,"plains");
        var future=new java.util.concurrent.CompletableFuture<TraditionalPlanetPlanner.Archive>();
        planet.initializePlan(new PlanetPlanStore(planet,PlanetPlanFixtures.context,future));
        var sent=new ArrayList<PlanetPackets.Profile>();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->{
            assertFalse(planet.planReady());
            PlanetPackets.Profile.whenReady(17,planet).ifPresent(sent::add);
        });
        assertTrue(sent.isEmpty());assertFalse(future.isDone());
        var plan=new PlanetPlan(512,planet.terrain().terrainPlan(),List.of(),List.of());
        future.complete(new TraditionalPlanetPlanner.Archive(plan,Map.of(),Map.of()));
        PlanetPackets.Profile.whenReady(17,planet).ifPresent(sent::add);
        assertEquals(1,sent.size());assertSame(plan,sent.getFirst().plan().orElseThrow());
        var failed=new java.util.concurrent.CompletableFuture<TraditionalPlanetPlanner.Archive>();
        planet.initializePlan(new PlanetPlanStore(planet,PlanetPlanFixtures.context,failed));
        failed.completeExceptionally(new IOException("fixture failure"));
        assertThrows(java.util.concurrent.CompletionException.class,()->PlanetPackets.Profile.whenReady(17,planet));
        for(String name:List.of("GroundViewServer","PlanetRemoteServer")) {
            var source=dev.starfold.Sources.text(name);
            assertTrue(source.contains("PlanetPackets.Profile.whenReady("));
            assertFalse(source.contains("PlanetPackets.Profile.of("));
            assertTrue(source.contains("if(profile.isEmpty())return;"));
        }
        assertFalse(dev.starfold.Sources.text("GroundSurfaceIndex").contains("index.function=new"));
        assertTrue(dev.starfold.Sources.text("GroundFlightServer").contains("if(!entryReady(player,player.serverLevel()))return;"));
    }

    @Test void prefilledVillagePoisRegisterBeforeBarrierReplayWithoutMissingRemovals(@org.junit.jupiter.api.io.TempDir Path folder) throws Exception {
        var planet=PlanetPlanFixtures.planet(512,"plains");
        var archive=TraditionalPlanetPlanner.record(planet,PlanetPlanFixtures.context,1,ResourceLocation.withDefaultNamespace("village_plains"),
                Face.NORTH,256,256,generate(planet,"village_plains",1));
        var file=folder.resolve("plan.dat");PlanetPlanStore.write(file,planet,archive);
        planet.initializePlan(new PlanetPlanStore(planet,PlanetPlanFixtures.context,file));
        var system=new SystemChunkGenerator(PlanetStorage.LAYOUT,List.of(planet));
        var chunks=new LinkedHashMap<ChunkPos,ProtoChunk>();var site=archive.plan().sites().getFirst();
        for(var piece:site.pieces())for(var block:archive.plan().templates().get(piece.template()).blocks()) {
            var c=StructureSandbox.canonical(planet.terrain(),site.face(),piece.origin().getX()+block.u(),piece.origin().getZ()+block.v());
            var pos=new ChunkPos((c.face().ordinal()*4096+c.u())>>4,c.v()>>4);
            chunks.computeIfAbsent(pos,p->new ProtoChunk(p,UpgradeData.EMPTY,LevelHeightAccessor.create(system.getMinY(),system.getGenDepth()),
                    PlanetPlanFixtures.context.registries().registryOrThrow(Registries.BIOME),null));
        }
        var reporter=(net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter.class},(p,m,a)->{throw new AssertionError(m.getName(),(Throwable)a[0]);});
        try(var pois=new net.minecraft.world.entity.ai.village.poi.PoiManager(
                new net.minecraft.world.level.chunk.storage.RegionStorageInfo("fixture",Level.OVERWORLD,"poi"),folder.resolve("poi"),
                net.minecraft.util.datafix.DataFixers.getDataFixer(),false,PlanetPlanFixtures.context.registries(),reporter,
                LevelHeightAccessor.create(system.getMinY(),system.getGenDepth()))) {
            var expected=new HashMap<BlockPos,net.minecraft.core.Holder<net.minecraft.world.entity.ai.village.poi.PoiType>>();
            for(var chunk:chunks.values()) {
                system.fillFromNoise(null,null,null,chunk).join();
                PlanetDecoration.generatedPois(chunk,(pos,state)->{
                    var type=net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(state).orElseThrow();
                    assertTrue(pois.getType(pos).isEmpty(),"noise alone did not notify POI manager");
                    expected.put(pos,type);pois.add(pos,type);
                });
            }
            assertTrue(expected.values().stream().anyMatch(t->t.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME)));
            assertTrue(expected.values().stream().anyMatch(t->!t.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME)
                    && !t.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.MEETING)),"village includes jobs");
            var delegate=chunkLevel(chunks,new HashMap<>());
            var level=(WorldGenLevel)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WorldGenLevel.class},(p,m,a)->{
                if(!m.getName().equals("setBlock"))return m.invoke(delegate,a);
                var pos=((BlockPos)a[0]).immutable();
                var before=net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(delegate.getBlockState(pos));
                var after=net.minecraft.world.entity.ai.village.poi.PoiTypes.forState((BlockState)a[1]);
                var result=m.invoke(delegate,a);
                // The same type comparison and ordered remove/add as ServerLevel.onBlockStateChange.
                if(!before.equals(after)) {
                    if(before.isPresent()) { assertEquals(before,pois.getType(pos),"no removal of an unregistered POI");pois.remove(pos); }
                    after.ifPresent(t->pois.add(pos,t));
                }
                return result;
            });
            for(var chunk:chunks.values()) {
                var pos=chunk.getPos();var area=new BoundingBox(pos.getMinBlockX(),system.getMinY(),pos.getMinBlockZ(),pos.getMaxBlockX(),319,pos.getMaxBlockZ());
                new PlannedStructurePiece(0,site.id(),area).place(level,system,area,pos,entity->{});
            }
            expected.forEach((pos,type)->assertEquals(Optional.of(type),pois.getType(pos)));
            // Non-village L5 POIs must be registered even when structures are disabled.
            var chunk=chunks.values().iterator().next();var origin=chunk.getPos().getWorldPosition().atY(300);
            chunk.setBlockState(origin,Blocks.BELL.defaultBlockState(),false);
            chunk.setBlockState(origin.above(),Blocks.BEE_NEST.defaultBlockState(),false);
            PlanetDecoration.generatedPois(chunk,(pos,state)->net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(state).ifPresent(t->pois.add(pos,t)));
            assertTrue(pois.existsAtPosition(net.minecraft.world.entity.ai.village.poi.PoiTypes.MEETING,origin));
            assertTrue(pois.existsAtPosition(net.minecraft.world.entity.ai.village.poi.PoiTypes.BEE_NEST,origin.above()));
        }
        var source=dev.starfold.Sources.text("SystemChunkGenerator");
        assertTrue(source.contains("PlanetDecoration.generatedPois(chunk,(pos,state)->level.getLevel().onBlockStateChange("));
        assertTrue(source.contains("if(planet(id).generation().structures())PlanetDecoration.apply("));
    }

    @Test void deterministicPlansPackAcrossCanonicalFacesAndRoundTripArchivesAndProfiles() throws Exception {
        var planet=PlanetPlanFixtures.planet(416,"plains");
        var a=new TraditionalPlanetPlanner(planet,PlanetPlanFixtures.context).generate();
        var b=new TraditionalPlanetPlanner(PlanetPlanFixtures.planet(416,"plains"),PlanetPlanFixtures.context).generate();
        assertFalse(a.plan().sites().isEmpty());assertArrayEquals(a.plan().compressed(),b.plan().compressed());
        assertEquals(a.recipes(),b.recipes());assertEquals(a.plan(),PlanetPlan.decode(a.plan().compressed()));
        var occupied=new HashSet<Long>();
        for(var site:a.plan().sites()) {
            var extent=PlanetPlan.extent(site);var own=new HashSet<Long>();
            for(int u=extent.minU();u<=extent.maxU();u++)for(int v=extent.minV();v<=extent.maxV();v++) {
                if(!PlanetPlan.footprint(site,u,v))continue;
                var c=StructureSandbox.canonical(planet.terrain(),site.face(),u,v);
                assertTrue(Anomaly.distance(planet.terrain().unit(c.face(),c.u()+.5,c.v()+.5),planet.terrain().shape())>=Anomaly.CLEAR);
                own.add(PlanetPlan.cellKey(c));
            }
            if(own.stream().anyMatch(occupied::contains))
                assertTrue(a.plan().terrain().relations().stream().anyMatch(link -> link.relation().equals("overlaps")
                    && link.target().id()==site.id()), "every intentional overlap has a fixed relation");
            occupied.addAll(own);
            for(var piece:site.pieces())for(var voxel:a.plan().templates().get(piece.template()).blocks())
                assertTrue(piece.origin().getY()+voxel.y()>planet.terrain().floor(),"core shell");
        }
        var file=Path.of("build/planet-plan-fixture/p2-roundtrip.dat");PlanetPlanStore.write(file,planet,a);
        assertEquals(a.plan(),PlanetPlanStore.read(file,planet,PlanetPlanFixtures.context).plan());
        planet.initializePlan(new PlanetPlanStore(planet,PlanetPlanFixtures.context,file));
        var profile=PlanetPackets.Profile.of(17,planet);var raw=Unpooled.buffer();
        try {
            var buffer=new RegistryFriendlyByteBuf(raw,PlanetPlanFixtures.context.registries());
            PlanetPackets.Profile.CODEC.encode(buffer,profile);assertTrue(buffer.readableBytes()<=PlanetPackets.Profile.PACKET_LIMIT);
            var decoded=PlanetPackets.Profile.CODEC.decode(buffer);assertEquals(profile,decoded);assertFalse(buffer.isReadable());
            var server=new SurfaceFunction(planet.terrain());var client=new SurfaceFunction(decoded.terrain());
            for(var site:a.plan().sites())assertEquals(server.point(site.face(),site.u(),site.v()),client.point(site.face(),site.u(),site.v()));
        } finally {raw.release();}
    }

    @Test void everyTaggedVanillaStructureSetRecordsAllGeneratedPieces() throws Exception {
        var sets=PlanetPlanFixtures.context.registries().registryOrThrow(Registries.STRUCTURE_SET);
        var supported=new TreeSet<String>();
        for(var holder:sets.getTagOrEmpty(SystemChunkGenerator.STRUCTURES)) {
            var structure=holder.value().structures().getFirst().structure().value();
            var biome=structure.biomes().stream().findFirst().orElseThrow().unwrapKey().orElseThrow().location().getPath();
            var planet=PlanetPlanFixtures.planet(768,biome);
            var archive=new TraditionalPlanetPlanner(planet,PlanetPlanFixtures.context).generate(List.of(holder));
            assertFalse(archive.plan().sites().isEmpty(),()->holder.unwrapKey()+" "+archive.skipped());
            assertEquals(archive.plan(),PlanetPlan.decode(archive.plan().compressed()));
            var file=Path.of("build/planet-plan-fixture/p2-set-"+holder.unwrapKey().orElseThrow().location().getPath()+".dat");
            PlanetPlanStore.write(file,planet,archive);assertEquals(archive.plan(),PlanetPlanStore.read(file,planet,PlanetPlanFixtures.context).plan());
            if(holder.unwrapKey().orElseThrow().location().getPath().equals("igloos"))assertTrue(archive.plan().sites().stream().anyMatch(site->site.pieces().size()>1));
            if(holder.unwrapKey().orElseThrow().location().getPath().equals("ruined_portals"))assertTrue(archive.recipes().values().stream()
                    .anyMatch(recipe->recipe.getList("pieces",Tag.TAG_COMPOUND).getCompound(0).getString("VerticalPlacement").equals("underground")));
            for(var site:archive.plan().sites())for(var tag:archive.recipes().get(site.id()).getList("entities",Tag.TAG_COMPOUND)) {
                var position=((CompoundTag)tag).getCompound("nbt").getList("Pos",Tag.TAG_DOUBLE);
                assertTrue(site.bounds().contains((int)Math.floor(position.getDouble(0)),(int)Math.floor(position.getDouble(2))),"entity owner is indexed");
            }
            supported.add(holder.unwrapKey().orElseThrow().location().getPath());
            System.out.println("SUPPORTED "+holder.unwrapKey().orElseThrow().location()+" sites="+archive.plan().sites().size()+" pieces="
                    +archive.plan().sites().stream().mapToInt(site->site.pieces().size()).sum()+" skipped="+archive.skipped());
        }
        assertEquals(11,supported.size());
    }

    @Test void crossSeamVillageTempleAndCaveIntersectingMineshaftReplayMatchesRealChunks() throws Exception {
        for(String structure:List.of("village_plains","desert_pyramid","mineshaft","swamp_hut")) {
            var planet=PlanetPlanFixtures.planet(512,structure.equals("desert_pyramid") ? "desert" : structure.equals("swamp_hut") ? "swamp" : "plains");
            TraditionalPlanetPlanner.Archive archive=null;
            for(long seed=1;seed<32;seed++) {
                var pieces=generate(planet,structure,seed);
                var full=BoundingBox.encapsulatingBoxes(pieces.stream().map(StructurePiece::getBoundingBox).toList()).orElseThrow();
                int dx=1-(full.minX()+full.maxX())/2,dz=256-(full.minZ()+full.maxZ())/2;
                for(var piece:pieces)piece.move(dx,0,dz);
                try {
                    archive=TraditionalPlanetPlanner.record(planet,PlanetPlanFixtures.context,seed,ResourceLocation.withDefaultNamespace(structure),Face.NORTH,1,256,pieces);
                    if(structure.equals("mineshaft") && archive.recipes().get(seed).getList("entities",Tag.TAG_COMPOUND).isEmpty())continue;
                    break;
                }
                catch(IllegalArgumentException e) {if(seed==31)throw e;}
            }
            assertNotNull(archive);var site=archive.plan().sites().getFirst();assertTrue(site.bounds().minU()<0);
            if(structure.equals("village_plains")) {
                assertTrue(site.pieces().size()>2);
                assertTrue(site.pieces().stream().anyMatch(p->p.adaptation()==TerrainAdjustment.BEARD_THIN));
                var recipes=archive.recipes().get(site.id()).getList("pieces",Tag.TAG_COMPOUND);
                boolean road=false;
                for(int i=0;i<recipes.size();i++)if(recipes.getCompound(i).getCompound("pool_element").getString("projection").equals("terrain_matching")) {
                    road=true;assertEquals(TerrainAdjustment.NONE,site.pieces().get(i).adaptation());
                }
                assertTrue(road,"fixture has terrain_matching roads");
            }
            if(structure.equals("mineshaft")) {
                boolean cave=false;
                for(var piece:site.pieces())for(var voxel:archive.plan().templates().get(piece.template()).blocks()) {
                    var p=piece.origin().offset(voxel.u(),voxel.y(),voxel.v());var c=StructureSandbox.canonical(planet.terrain(),site.face(),p.getX(),p.getZ());
                    if(p.getY()<planet.terrain().groundY(c.face(),c.u(),c.v())-4 && planet.terrain().column(c.face(),c.u(),c.v()).state(p.getY()).isAir())cave=true;
                }
                assertTrue(cave,"mineshaft intersects existing caves");
                assertTrue(archive.plan().templates().stream().flatMap(t->t.blocks().stream()).anyMatch(v->v.state().isAir()),"AIR retained");
            }
            replayChunks(planet,archive,structure);
        }
    }
    static List<StructurePiece> generate(PlanetChunkGenerator planet,String id,long seed) {
        var context=PlanetPlanFixtures.context;var generator=StructureSandbox.generator(planet,Face.NORTH);
        var structure=context.registries().registryOrThrow(Registries.STRUCTURE).get(ResourceLocation.withDefaultNamespace(id));
        var generation=new Structure.GenerationContext(context.registries(),generator,generator.getBiomeSource(),context.random(),context.templates(),
                seed,new ChunkPos(16,16),LevelHeightAccessor.create(planet.getMinY(),planet.getGenDepth()),b->true);
        return structure.findValidGenerationPoint(generation).orElseThrow().getPiecesBuilder().build().pieces();
    }
    static void replayChunks(PlanetChunkGenerator planet,TraditionalPlanetPlanner.Archive archive,String name) throws Exception {
        var file=Path.of("build/planet-plan-fixture/p2-"+name+".dat");PlanetPlanStore.write(file,planet,archive);
        planet.initializePlan(new PlanetPlanStore(planet,PlanetPlanFixtures.context,file));
        var system=new SystemChunkGenerator(PlanetStorage.LAYOUT,List.of(planet));var function=new SurfaceFunction(planet.terrain());
        var chunks=new LinkedHashMap<ChunkPos,ProtoChunk>();var faces=new HashSet<Face>();var site=archive.plan().sites().getFirst();
        for(var piece:site.pieces())for(var b:archive.plan().templates().get(piece.template()).blocks()) {
            int u=piece.origin().getX()+b.u(),v=piece.origin().getZ()+b.v();var c=StructureSandbox.canonical(planet.terrain(),site.face(),u,v);
            faces.add(c.face());var pos=new ChunkPos((c.face().ordinal()*4096+c.u())/16,c.v()/16);
            chunks.computeIfAbsent(pos,p->new ProtoChunk(p,UpgradeData.EMPTY,LevelHeightAccessor.create(system.getMinY(),system.getGenDepth()),
                    PlanetPlanFixtures.context.registries().registryOrThrow(Registries.BIOME),null));
        }
        assertTrue(faces.size()>=2);var entities=new HashMap<BlockPos,BlockEntity>();var level=chunkLevel(chunks,entities);
        var manager=new StructureManager(level,new WorldOptions(42,true,false),null);
        var spawns=StructureSandbox.merge(StructureSandbox.runPieces(planet,PlanetPlanFixtures.context,site.face(),site,archive.recipes().get(site.id()),site.id())).spawns();
        var expected=new ArrayList<CompoundTag>();
        for(var pos:chunks.keySet())for(var projected:StructureEntities.forChunk(site,spawns,archive.plan().size(),0,pos,PlanetPlanFixtures.context.registries()))expected.add(projected.nbt());
        assertEquals(spawns.size(),expected.size(),"every entity belongs to a generated chunk");
        if(name.equals("village_plains")) {
            assertTrue(expected.stream().anyMatch(t->t.getString("id").equals("minecraft:villager")));
            assertTrue(expected.stream().filter(t->t.getString("id").equals("minecraft:villager")).allMatch(t->t.contains("VillagerData")));
        }
        if(name.equals("swamp_hut"))assertEquals(Set.of("minecraft:witch","minecraft:cat"),expected.stream().map(t->t.getString("id")).collect(java.util.stream.Collectors.toSet()));
        if(name.equals("mineshaft")) {
            assertFalse(expected.isEmpty());
            assertTrue(expected.stream().allMatch(t->t.getString("id").equals("minecraft:chest_minecart") && t.getString("LootTable").equals("minecraft:chests/abandoned_mineshaft") && t.contains("LootTableSeed")));
        }
        var order=new ArrayList<>(chunks.values());Collections.reverse(order);int at=0;
        for(var chunk:order) {
            if(at++==order.size()/2) {
                planet.initializePlan(new PlanetPlanStore(planet,PlanetPlanFixtures.context,file));
                assertEquals(archive.plan(),planet.planStore().plan());
                // Persist actual ProtoChunk entity payloads, then continue only ungenerated chunks.
                var saved=new CompoundTag();var payload=new ListTag();
                for(var existing:chunks.values()) {
                    var entry=new CompoundTag();entry.putInt("x",existing.getPos().x);entry.putInt("z",existing.getPos().z);
                    var tags=new ListTag();existing.getEntities().forEach(t->tags.add(t.copy()));entry.put("entities",tags);payload.add(entry);
                }
                saved.put("chunks",payload);var entitiesFile=file.resolveSibling("p3-entities-"+name+".dat");NbtIo.writeCompressed(saved,entitiesFile);
                var loaded=NbtIo.readCompressed(entitiesFile,NbtAccounter.create(16_000_000));
                for(var tag:loaded.getList("chunks",Tag.TAG_COMPOUND)) {
                    var entry=(CompoundTag)tag;var pos=new ChunkPos(entry.getInt("x"),entry.getInt("z"));
                    if(entry.getList("entities",Tag.TAG_COMPOUND).isEmpty())continue;
                    var restored=new ProtoChunk(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(system.getMinY(),system.getGenDepth()),PlanetPlanFixtures.context.registries().registryOrThrow(Registries.BIOME),null);
                    entry.getList("entities",Tag.TAG_COMPOUND).forEach(t->restored.addEntity((CompoundTag)t));chunks.put(pos,restored);
                }
            }
            system.fillFromNoise(null,null,null,chunk).join();verifyChunk(chunk,function);
            int faceId=chunk.getPos().getMinBlockX()/4096;var face=Face.VALUES[faceId];
            for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
                int wx=chunk.getPos().getMinBlockX()+x,v=chunk.getPos().getMinBlockZ()+z,u=wx-faceId*4096;
                var column=planet.terrain().foundationColumn(face,u,v,archive.plan().ground(face,u,v,planet.terrain().groundY(face,u,v)));
                for(int y=planet.terrain().floor();y<320;y++)chunk.setBlockState(new BlockPos(wx,y,v),column.state(y),false);
            }
            system.createStructures(PlanetPlanFixtures.context.registries(),null,manager,chunk,PlanetPlanFixtures.context.templates());
            var area=new BoundingBox(chunk.getPos().getMinBlockX(),planet.terrain().floor(),chunk.getPos().getMinBlockZ(),chunk.getPos().getMaxBlockX(),319,chunk.getPos().getMaxBlockZ());
            for(var start:chunk.getAllStarts().values()) {
                var restored=StructureStart.loadStaticStart(PlanetPlanFixtures.context.serialization(),start.createTag(PlanetPlanFixtures.context.serialization(),chunk.getPos()),planet.seed());
                assertNotNull(restored);
                for(var piece:restored.getPieces())((PlannedStructurePiece)piece).place(level,system,area,chunk.getPos(),
                        projected->chunks.get(chunk.getPos()).addEntity(projected.nbt()));
            }
            verifyChunk(chunk,function);
        }
        var actual=chunks.values().stream().flatMap(c->c.getEntities().stream()).toList();
        assertEquals(new HashSet<>(expected),new HashSet<>(actual));assertEquals(expected.size(),actual.size(),"no duplicated entities after partial reload");
        assertEquals(actual.size(),actual.stream().map(t->t.getUUID("UUID")).distinct().count());
        for(int u=-3;u<=3;u++)for(int v=230;v<=280;v++) {
            var c=StructureSandbox.canonical(planet.terrain(),site.face(),u,v);
            assertEquals(function.visible(site.face(),u,v),function.visible(c.face(),c.u(),c.v()));
            assertEquals(function.point(site.face(),u,v),function.point(c.face(),c.u(),c.v()));
        }
    }

    @Test void airExcavationChangesVisibleTopAndCoreShellWritesRejectWholeStructure() {
        var planet=PlanetPlanFixtures.planet(512,"plains");int u=256,v=256,ground=planet.terrain().groundY(Face.NORTH,u,v);
        var bounds=new PlanetPlan.Bounds(u,ground-2,v,u,ground,v);
        var piece=new PlanetPlan.Piece(0,new BlockPos(u,ground,v),Rotation.NONE,Mirror.NONE,bounds,bounds,TerrainAdjustment.NONE);
        var site=new PlanetPlan.Site(1,ResourceLocation.withDefaultNamespace("mineshaft"),Face.NORTH,u,v,bounds,List.of(piece));
        var template=new PlanetPlan.Template(List.of(new PlanetPlan.Voxel(0,0,0,Blocks.AIR.defaultBlockState()),new PlanetPlan.Voxel(0,-1,0,Blocks.AIR.defaultBlockState()),new PlanetPlan.Voxel(0,-2,0,Blocks.STONE.defaultBlockState())));
        var plan=new PlanetPlan(512,fixtureTerrain,List.of(site),List.of(template));planet.terrain().initializePlan(()->plan);
        var function=new SurfaceFunction(planet.terrain());assertEquals(ground-2,function.visible(Face.NORTH,u,v).top());assertEquals(ground-2,function.point(Face.NORTH,u,v).height());
        assertEquals(Blocks.STONE.defaultBlockState(),function.visible(Face.NORTH,u,v).block());
        var low=new BuriedTreasurePieces.BuriedTreasurePiece(new BlockPos(256,planet.terrain().floor()-1,256));
        assertThrows(IllegalArgumentException.class,()->TraditionalPlanetPlanner.record(planet,PlanetPlanFixtures.context,3,site.structure(),Face.NORTH,256,256,List.of(low)));
    }

    @Test void entityOnlyChunksOutsideVisibleBoundsGetOneStartAfterArchiveReload() throws Exception {
        var planet=PlanetPlanFixtures.planet(512,"plains");var context=PlanetPlanFixtures.context;
        var id=ResourceLocation.withDefaultNamespace("village/plains/houses/plains_small_house_1");
        var template=context.templates().getOrCreate(id);var original=template.save(new CompoundTag());var changed=original.copy();
        var entities=new ListTag();var info=new CompoundTag();info.put("pos",StructureEntities.position(-17,1,1));
        var anchor=new ListTag();anchor.add(IntTag.valueOf(0));anchor.add(IntTag.valueOf(1));anchor.add(IntTag.valueOf(1));info.put("blockPos",anchor);
        var nbt=new CompoundTag();nbt.putString("id","minecraft:painting");nbt.putString("variant","minecraft:match");nbt.putByte("facing",(byte)Direction.SOUTH.get2DDataValue());info.put("nbt",nbt);entities.add(info);changed.put("entities",entities);
        try {
            template.load(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),changed);
            var element=net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement.single(id.toString()).apply(net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.Projection.RIGID);
            var edgeOrigin=new BlockPos(511,130,255);
            var edge=new PoolElementStructurePiece(context.templates(),element,edgeOrigin,0,Rotation.NONE,
                    element.getBoundingBox(context.templates(),edgeOrigin,Rotation.NONE),net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings.APPLY_WATERLOGGING);
            var rejected=assertThrows(IllegalArgumentException.class,()->TraditionalPlanetPlanner.record(planet,context,72,ResourceLocation.withDefaultNamespace("village_plains"),Face.NORTH,511,256,List.of(edge)));
            assertTrue(rejected.getMessage().contains("center leaves generated face chart"),"reject the whole site instead of accepting an entity for a gap chunk");
            var pool=new PoolElementStructurePiece(context.templates(),element,new BlockPos(-1,130,255),0,Rotation.CLOCKWISE_90,
                    element.getBoundingBox(context.templates(),new BlockPos(-1,130,255),Rotation.CLOCKWISE_90),net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings.APPLY_WATERLOGGING);
            var archive=TraditionalPlanetPlanner.record(planet,context,71,ResourceLocation.withDefaultNamespace("village_plains"),Face.NORTH,0,256,List.of(pool));
            var site=archive.plan().sites().getFirst();var spawn=StructureEntities.Spawn.load(archive.recipes().get(71L).getList("entities",Tag.TAG_COMPOUND).getCompound(0));
            var projected=StructureEntities.project(site,spawn,512,0,PlanetPlanFixtures.context.registries());var target=StructureEntities.chunk(projected);
            assertNotEquals(site.face().ordinal(),Math.floorDiv(target.getMinBlockX(),4096),"entity target crosses a seam");
            var sourcePos=spawn.nbt().getList("Pos",Tag.TAG_DOUBLE);assertFalse(site.bounds().contains((int)Math.floor(sourcePos.getDouble(0)),(int)Math.floor(sourcePos.getDouble(2))));
            var file=Path.of("build/planet-plan-fixture/p3-entity-only-chunk.dat");PlanetPlanStore.write(file,planet,archive);
            for(int reload=0;reload<2;reload++) {
                planet.initializePlan(new PlanetPlanStore(planet,context,file));var system=new SystemChunkGenerator(PlanetStorage.LAYOUT,List.of(planet));
                var chunk=new ProtoChunk(target,UpgradeData.EMPTY,LevelHeightAccessor.create(system.getMinY(),system.getGenDepth()),context.registries().registryOrThrow(Registries.BIOME),null);
                var chunks=Map.of(target,chunk);var level=chunkLevel(chunks,new HashMap<>());var manager=new StructureManager(level,new WorldOptions(42,true,false),null);
                assertEquals(List.of(site),planet.planStore().entitySites(0,target));
                PlannedStructurePiece.createStarts(system,0,context.registries(),manager,chunk);
                assertEquals(1,chunk.getAllStarts().values().stream().mapToInt(start->start.getPieces().size()).sum());
                var area=new BoundingBox(target.getMinBlockX(),system.getMinY(),target.getMinBlockZ(),target.getMaxBlockX(),319,target.getMaxBlockZ());
                for(var start:chunk.getAllStarts().values())for(var piece:start.getPieces())((PlannedStructurePiece)piece).place(level,system,area,target,p->chunk.addEntity(p.nbt()));
                assertEquals(1,chunk.getEntities().size());assertEquals(projected.nbt(),chunk.getEntities().getFirst());
            }
        } finally {template.load(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),original);}
    }

    @Test void templatePassengersKeepNbtAndCrossSeamFractionalPositionAndRotationInstructions() {
        var planet=PlanetPlanFixtures.planet(512,"plains");
        var nbt=new CompoundTag();nbt.putString("id","minecraft:villager");
        var data=new CompoundTag();data.putString("profession","minecraft:librarian");data.putString("type","minecraft:desert");nbt.put("VillagerData",data);
        var passenger=new CompoundTag();passenger.putString("id","minecraft:painting");passenger.putString("variant","minecraft:kebab");passenger.putByte("facing",(byte)2);passenger.putInt("TileX",0);passenger.putInt("TileY",0);passenger.putInt("TileZ",0);var passengers=new ListTag();passengers.add(passenger);nbt.put("Passengers",passengers);
        var spawns=new ArrayList<StructureEntities.Spawn>();StructureEntities.begin(spawns,7);
        try {
            assertTrue(StructureEntities.captureTemplate(new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate(),
                    chunkLevel(Map.of(),Map.of()),new BlockPos(-1,120,256),
                    new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings().setRotation(Rotation.CLOCKWISE_90).setMirror(Mirror.FRONT_BACK).setFinalizeEntities(true),
                    List.of(new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureEntityInfo(new net.minecraft.world.phys.Vec3(.25,0,.75),BlockPos.ZERO,nbt))));
        } finally {StructureEntities.end();}
        assertEquals(1,spawns.size());var spawn=spawns.getFirst();assertEquals(Rotation.CLOCKWISE_90,spawn.rotation());assertEquals(Mirror.FRONT_BACK,spawn.mirror());
        assertEquals(data,spawn.nbt().getCompound("VillagerData"));assertTrue(spawn.finalizeSpawn());
        var pos=spawn.nbt().getList("Pos",Tag.TAG_DOUBLE);var route=planet.terrain().shape().topology().canonical(new Cell(Face.NORTH,(int)Math.floor(pos.getDouble(0)),0,(int)Math.floor(pos.getDouble(2))));
        var c=route.cell();var chunk=new ChunkPos((c.face().ordinal()*4096+c.u())/16,c.v()/16);
        var bounds=new PlanetPlan.Bounds(-2,100,254,2,140,260);var site=new PlanetPlan.Site(7,ResourceLocation.withDefaultNamespace("village_plains"),Face.NORTH,0,256,bounds,List.of());
        var projected=StructureEntities.forChunk(site,spawns,512,0,chunk,PlanetPlanFixtures.context.registries());assertEquals(1,projected.size());
        assertTrue(StructureEntities.forChunk(site,spawns,512,0,new ChunkPos(chunk.x+1,chunk.z),PlanetPlanFixtures.context.registries()).isEmpty());
        assertEquals(route.turns(),projected.getFirst().turns());
        var output=projected.getFirst().nbt();
        var repeated=StructureEntities.project(site,spawn,512,0,PlanetPlanFixtures.context.registries());var other=StructureEntities.project(site,spawn,512,1,PlanetPlanFixtures.context.registries());
        assertEquals(output,repeated.nbt());assertNotEquals(output.getUUID("UUID"),other.nbt().getUUID("UUID"));
        assertNotEquals(output.getList("Passengers",Tag.TAG_COMPOUND).getCompound(0).getUUID("UUID"),other.nbt().getList("Passengers",Tag.TAG_COMPOUND).getCompound(0).getUUID("UUID"));
        var hanging=output.getList("Passengers",Tag.TAG_COMPOUND).getCompound(0);
        assertNotEquals(output.getUUID("UUID"),hanging.getUUID("UUID"));assertEquals("minecraft:kebab",hanging.getString("variant"));
        var sourceAnchor=new BlockPos(-1,120,256);var expectedAnchor=planet.terrain().shape().topology().canonical(new Cell(Face.NORTH,sourceAnchor.getX(),0,sourceAnchor.getZ())).cell();
        assertEquals(expectedAnchor.face().ordinal()*4096+expectedAnchor.u(),hanging.getInt("TileX"));
        assertEquals(120,hanging.getInt("TileY"));assertEquals(expectedAnchor.v(),hanging.getInt("TileZ"));
        var isolated=spawn.nbt();isolated.putString("id","minecraft:pig");assertEquals("minecraft:villager",spawn.nbt().getString("id"));
    }

    @Test void twoByTwoPaintingKeepsIndependentAnchorAndIndexesItsFinalCenterAcrossChunksAndSeams() throws Exception {
        var context=PlanetPlanFixtures.context;var planet=PlanetPlanFixtures.planet(512,"plains");
        var variant=context.registries().registryOrThrow(Registries.PAINTING_VARIANT).get(ResourceLocation.withDefaultNamespace("match"));
        assertEquals(2,variant.width());assertEquals(2,variant.height());
        for(int x:new int[]{109,15,-1,511,512})for(var rotation:Rotation.values())for(var mirror:Mirror.values()) {
            var origin=new BlockPos(x,120,256);var tag=new CompoundTag();tag.putString("id","minecraft:painting");tag.putString("variant","minecraft:match");
            tag.putByte("facing",(byte)Direction.SOUTH.get2DDataValue());tag.putInt("TileX",0);tag.putInt("TileY",0);tag.putInt("TileZ",0);
            var spawns=new ArrayList<StructureEntities.Spawn>();StructureEntities.begin(spawns,111);
            try {
                StructureEntities.captureTemplate(new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate(),chunkLevel(Map.of(),Map.of()),origin,
                        new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings().setRotation(rotation).setMirror(mirror),
                        List.of(new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureEntityInfo(new net.minecraft.world.phys.Vec3(1,1,.03125),BlockPos.ZERO,tag)));
            } finally {StructureEntities.end();}
            var spawn=spawns.getFirst();assertEquals(x,spawn.nbt().getInt("TileX"));assertEquals(120,spawn.nbt().getInt("TileY"));
            var site=new PlanetPlan.Site(111,ResourceLocation.withDefaultNamespace("village_plains"),Face.TOP,0,256,new PlanetPlan.Bounds(-32,100,230,520,150,280),List.of());
            var route=planet.terrain().shape().topology().canonical(new Cell(Face.TOP,x,0,256));var c=route.cell();
            int ax=c.face().ordinal()*4096+c.u(),az=c.v();
            Direction direction=switch(rotation) {case NONE->Direction.SOUTH;case CLOCKWISE_90->Direction.WEST;case CLOCKWISE_180->Direction.NORTH;case COUNTERCLOCKWISE_90->Direction.EAST;};
            if(mirror==Mirror.LEFT_RIGHT && direction.getAxis()==Direction.Axis.Z || mirror==Mirror.FRONT_BACK && direction.getAxis()==Direction.Axis.X)direction=direction.getOpposite();
            direction=Rotation.values()[route.turns()].rotate(direction);
            // Explicit centers from vanilla's 2x2 Painting.calculateBoundingBox, not floor(Pos)=anchor.
            var expected=switch(direction) {
                case SOUTH -> new net.minecraft.world.phys.Vec3(ax+1,121,az+.03125);
                case NORTH -> new net.minecraft.world.phys.Vec3(ax,121,az+.96875);
                case WEST -> new net.minecraft.world.phys.Vec3(ax+.96875,121,az+1);
                case EAST -> new net.minecraft.world.phys.Vec3(ax+.03125,121,az);
                default -> throw new AssertionError();
            };
            int localU=(int)Math.floor(expected.x)-c.face().ordinal()*4096,localV=(int)Math.floor(expected.z);
            if(localU<0 || localU>=512 || localV<0 || localV>=512) {
                var failure=assertThrows(IllegalArgumentException.class,()->StructureEntities.project(site,spawn,512,0,context.registries()));
                assertTrue(failure.getMessage().contains("center leaves generated face chart"));
                if(x==511 && rotation==Rotation.NONE && mirror==Mirror.NONE) {
                    assertEquals(new net.minecraft.world.phys.Vec3(512,121,256.03125),expected);
                    assertEquals(-1,dev.starfold.storage.ChunkKeys.local(32,16,32),"gap chunk never reaches createStarts");
                }
                continue;
            }
            var projected=StructureEntities.project(site,spawn,512,0,context.registries());var output=projected.nbt();
            assertEquals(ax,output.getInt("TileX"));assertEquals(120,output.getInt("TileY"));assertEquals(az,output.getInt("TileZ"));
            assertEquals(direction.get2DDataValue(),output.getByte("facing"));
            assertEquals(StructureEntities.position(expected.x,expected.y,expected.z),output.getList("Pos",Tag.TAG_DOUBLE));
            assertEquals(expected,StructureEntities.attachedCenter(output,context.registries()));
            var target=new ChunkPos(BlockPos.containing(expected));assertEquals(target,StructureEntities.chunk(projected));
            assertEquals(1,StructureEntities.forChunk(site,spawns,512,0,target,context.registries()).size());
            assertTrue(StructureEntities.forChunk(site,spawns,512,0,new ChunkPos(target.x+1,target.z),context.registries()).isEmpty());
            if(x==109 && rotation==Rotation.NONE && mirror==Mirror.NONE) {
                assertEquals(new net.minecraft.world.phys.Vec3(110,121,256.03125),expected);assertEquals(new ChunkPos(6,16),target);
                assertNotEquals(BlockPos.containing(expected).getX(),output.getInt("TileX"));
            }
            if(x==15 && rotation==Rotation.NONE && mirror==Mirror.NONE)assertNotEquals(new ChunkPos(ax/16,az/16),target,"center owns next chunk even when anchor is in previous chunk");
        }
        var source=Files.readString(Path.of("src/main/java/dev/starfold/worldgen/plan/StructureEntities.java"));
        assertTrue(source.contains("attached.setPos(anchor.getX(),anchor.getY(),anchor.getZ())"));
        assertFalse(source.contains("var anchor=BlockPos.containing(pos)"));
        assertTrue(Files.readString(Path.of("src/main/java/dev/starfold/worldgen/plan/StructureSandbox.java")).contains("StructureEntities.project(site,spawn,terrain.shape().size(),0,context.registries)"),"recording and archive verification reject ungenerated entity targets before publishing a plan");
    }

    @Test void serverEntityRecipesAreDeterministicValidatedAndNeverSynchronized() throws Exception {
        var planet=PlanetPlanFixtures.planet(512,"swamp");var context=PlanetPlanFixtures.context;
        var a=TraditionalPlanetPlanner.record(planet,context,29,ResourceLocation.withDefaultNamespace("swamp_hut"),Face.NORTH,256,256,generate(planet,"swamp_hut",2));
        var b=TraditionalPlanetPlanner.record(planet,context,29,ResourceLocation.withDefaultNamespace("swamp_hut"),Face.NORTH,256,256,generate(planet,"swamp_hut",2));
        assertEquals(a.recipes(),b.recipes());assertEquals(2,a.recipes().get(29L).getList("entities",Tag.TAG_COMPOUND).size());
        var file=Path.of("build/planet-plan-fixture/p3-entity-tamper.dat");
        for(String field:List.of("id","Pos","seed","finalize")) {
            PlanetPlanStore.write(file,planet,a);var root=NbtIo.readCompressed(file,NbtAccounter.create(64_000_000));
            var spawn=root.getList("recipes",Tag.TAG_COMPOUND).getCompound(0).getCompound("recipe").getList("entities",Tag.TAG_COMPOUND).getCompound(0);
            if(field.equals("id"))spawn.getCompound("nbt").putString("id","minecraft:pig");
            else if(field.equals("Pos"))spawn.getCompound("nbt").put("Pos",StructureEntities.position(0,100,0));
            else if(field.equals("seed"))spawn.putLong("seed",spawn.getLong("seed")+1);
            else spawn.putBoolean("finalize",false);
            NbtIo.writeCompressed(root,file);assertThrows(IOException.class,()->PlanetPlanStore.read(file,planet,context),field);
        }
        var source=Files.readString(Path.of("src/main/java/dev/starfold/worldgen/plan/StructureEntities.java"));
        assertTrue(source.contains("level.addFreshEntityWithPassengers(entity)"));
        assertTrue(source.contains("EventHooks.finalizeMobSpawn(mob,spawnLevel,"));
        var sandboxSource=Files.readString(Path.of("src/main/java/dev/starfold/worldgen/plan/StructureSandbox.java"));
        assertTrue(sandboxSource.contains("entity instanceof net.minecraft.world.entity.decoration.BlockAttachedEntity attached"));
        assertTrue(sandboxSource.contains("var anchor=attached.getPos();tag.putInt(\"TileX\",anchor.getX());tag.putInt(\"TileY\",anchor.getY());tag.putInt(\"TileZ\",anchor.getZ())"));
        assertFalse(source.contains("mob.finalizeSpawn("),"mod calls must preserve NeoForge's finalize event and spawn cancellation");
        assertTrue(Files.readString(Path.of("src/main/java/dev/starfold/worldgen/plan/PlannedStructurePiece.java")).contains("entity->StructureEntities.place(level,entity)"));
        assertFalse(Files.readString(Path.of("src/main/java/dev/starfold/worldgen/surface/SurfaceFunction.java")).contains("StructureEntities"));
    }

    @Test void archiveRejectsTemplateAndNonFirstPieceRecipeTampering() throws Exception {
        var planet=PlanetPlanFixtures.planet(512,"plains");
        var archive=TraditionalPlanetPlanner.record(planet,PlanetPlanFixtures.context,1,ResourceLocation.withDefaultNamespace("village_plains"),Face.NORTH,256,256,generate(planet,"village_plains",1));
        var file=Path.of("build/planet-plan-fixture/p2-tampered.dat");PlanetPlanStore.write(file,planet,archive);
        var root=NbtIo.readCompressed(file,NbtAccounter.create(64_000_000));
        var pieces=root.getList("recipes",Tag.TAG_COMPOUND).getCompound(0).getCompound("recipe").getList("pieces",Tag.TAG_COMPOUND);
        int changed=1;while(archive.plan().templates().get(archive.plan().sites().getFirst().pieces().get(changed).template()).blocks().size()<20)changed++;
        var second=pieces.getCompound(changed);second.putInt("PosX",second.getInt("PosX")+1);NbtIo.writeCompressed(root,file);
        assertThrows(IOException.class,()->PlanetPlanStore.read(file,planet,PlanetPlanFixtures.context));
        PlanetPlanStore.write(file,planet,archive);root=NbtIo.readCompressed(file,NbtAccounter.create(64_000_000));
        var first=root.getList("recipes",Tag.TAG_COMPOUND).getCompound(0).getCompound("recipe").getList("pieces",Tag.TAG_COMPOUND).getCompound(0);
        var sources=first.getCompound("plan_sources");assertFalse(sources.isEmpty());sources.putString(sources.getAllKeys().iterator().next(),"0".repeat(64));NbtIo.writeCompressed(root,file);
        assertThrows(IOException.class,()->PlanetPlanStore.read(file,planet,PlanetPlanFixtures.context));
    }

    @Test void untrustedCodecBoundsPieceCountsReferencesOverlapsAndPacketExpansion() throws Exception {
        var bounds=new PlanetPlan.Bounds(200,128,200,200,128,200);
        var piece=new PlanetPlan.Piece(0,new BlockPos(200,128,200),Rotation.NONE,Mirror.NONE,bounds,bounds,TerrainAdjustment.NONE);
        var template=new PlanetPlan.Template(List.of(new PlanetPlan.Voxel(0,0,0,Blocks.STONE.defaultBlockState())));
        var a=new PlanetPlan.Site(1,ResourceLocation.withDefaultNamespace("igloo"),Face.TOP,200,200,bounds,List.of(piece));
        var b=new PlanetPlan.Site(2,a.structure(),a.face(),a.u(),a.v(),bounds,List.of(piece));
        var overlapping=PlanetPlan.decode(new PlanetPlan(512,fixtureTerrain,List.of(a,b),List.of(template)).compressed());
        assertEquals(List.of(a,b),overlapping.sites());
        assertTrue(overlapping.owns(Face.TOP,200,200,128,b.id()),"later saved site owns an overlapping block");
        var amplified=new PlanetPlan.Site(1,a.structure(),a.face(),a.u(),a.v(),bounds,Collections.nCopies(PlanetPlan.MAX_PIECES+1,piece));
        assertThrows(IllegalArgumentException.class,()->PlanetPlan.decode(new PlanetPlan(512,fixtureTerrain,List.of(amplified),List.of(template)).compressed()));
        assertThrows(IllegalArgumentException.class,()->PlanetPlan.decode(new byte[PlanetPlan.TRANSFER_LIMIT+1]));
        assertThrows(IllegalArgumentException.class,()->PlanetPlan.decode(zip(out->{header(out);out.writeInt(Integer.MAX_VALUE);})));
        assertThrows(IllegalArgumentException.class,()->PlanetPlan.decode(zip(out->{header(out);out.writeInt(0);out.writeInt(0);out.writeByte(9);})));
        assertThrows(IllegalArgumentException.class,()->PlanetPlan.decode(zip(out->out.write(new byte[64_000_001]))));
        var raw=Unpooled.buffer();try {var buffer=new RegistryFriendlyByteBuf(raw,RegistryAccess.EMPTY);buffer.writeVarInt(PlanetPlan.PACKET_LIMIT+1);
            assertThrows(RuntimeException.class,()->PlanetPlan.readPacket(buffer));}finally {raw.release();}
    }

    @Test void processorPortalSpillAndLootKeepTheirVanillaStatesAndTemplateIdentity() throws Exception {
        var planet=PlanetPlanFixtures.planet(512,"plains");var context=PlanetPlanFixtures.context;
        var id=ResourceLocation.withDefaultNamespace("ruined_portal/portal_1");var template=context.templates().getOrCreate(id);
        int ground=planet.terrain().groundY(Face.NORTH,1,256);
        var vanilla=new RuinedPortalPiece(context.templates(),new BlockPos(-4,ground,250),RuinedPortalPiece.VerticalPlacement.ON_LAND_SURFACE,
                new RuinedPortalPiece.Properties(false,1,false,true,true,false),id,template,Rotation.CLOCKWISE_90,Mirror.FRONT_BACK,
                new BlockPos(template.getSize().getX()/2,0,template.getSize().getZ()/2));
        var archive=TraditionalPlanetPlanner.record(planet,context,19,ResourceLocation.withDefaultNamespace("ruined_portal"),Face.NORTH,1,256,List.of(vanilla));
        assertTrue(archive.plan().templates().getFirst().blocks().stream().anyMatch(b->b.state().is(Blocks.MOSSY_STONE_BRICKS) || b.state().is(Blocks.MOSSY_COBBLESTONE)));
        var piece=archive.plan().sites().getFirst().pieces().getFirst();var box=vanilla.getBoundingBox();
        assertTrue(archive.plan().templates().getFirst().blocks().stream().anyMatch(b->!box.isInside(piece.origin().offset(b.u(),b.y(),b.v()))),"portal spill outside source bbox");
        var replay=StructureSandbox.merge(StructureSandbox.runPieces(planet,context,Face.NORTH,archive.plan().sites().getFirst(),archive.recipes().get(19L),19));
        assertTrue(replay.entities().values().stream().anyMatch(entity->entity.saveWithFullMetadata(context.registries()).contains("LootTable")));
        var file=Path.of("build/planet-plan-fixture/p2-source-drift.dat");PlanetPlanStore.write(file,planet,archive);
        var original=template.save(new CompoundTag());var changed=original.copy();changed.getList("size",Tag.TAG_INT).set(0,IntTag.valueOf(1));
        try {template.load(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),changed);
            assertThrows(IOException.class,()->PlanetPlanStore.read(file,planet,context));}
        finally {template.load(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),original);}
        replayChunks(planet,archive,"portal");
    }

    @Test void repeatedPieceReferencesCannotAmplifyASmallCompressedPlanBeyondTheExpansionBudget() {
        var voxels=new ArrayList<PlanetPlan.Voxel>();
        for(int u=0;u<64;u++)for(int v=0;v<64;v++)for(int y=0;y<18;y++)voxels.add(new PlanetPlan.Voxel(u,y,v,Blocks.STONE.defaultBlockState()));
        var bounds=new PlanetPlan.Bounds(200,0,200,263,17,263);
        var piece=new PlanetPlan.Piece(0,new BlockPos(200,0,200),Rotation.NONE,Mirror.NONE,bounds,bounds,TerrainAdjustment.NONE);
        var site=new PlanetPlan.Site(1,ResourceLocation.withDefaultNamespace("village_plains"),Face.TOP,200,200,bounds,Collections.nCopies(64,piece));
        var plan=new PlanetPlan(512,fixtureTerrain,List.of(site),List.of(new PlanetPlan.Template(voxels)));
        assertTrue(plan.compressed().length<PlanetPlan.PACKET_LIMIT);
        assertThrows(IllegalArgumentException.class,()->PlanetPlan.decode(plan.compressed()));
    }

    @Test void pieceFoundationExcludesBiomeTreeTemplatesFromActualChunks() {
        var type=ExperienceFixtures.registryLookup().lookupOrThrow(PlanetType.KEY).listElements()
                .filter(h->h.key().location().toString().equals("starfold_worlds:home")).findFirst().orElseThrow().value();
        var planet=type.planet(42,Optional.of(type.minSize()));var terrain=planet.terrain();
        int u=0,v=0,raw=0;
        search:for(int x=200;x<500;x++)for(int z=200;z<500;z++) {
            var column=new SurfaceFunction(terrain).blocks(Face.TOP,x,z);
            int ground=terrain.groundY(Face.TOP,x,z);
            if(column[ground+1-terrain.floor()].is(net.minecraft.tags.BlockTags.LOGS)) {u=x;v=z;raw=ground;break search;}
        }
        assertTrue(raw>0);int height=raw+4;
        var bounds=new PlanetPlan.Bounds(u-3,height+1,v-3,u+3,height+12,v+3);
        var piece=new PlanetPlan.Piece(0,new BlockPos(u,height+1,v),Rotation.NONE,Mirror.NONE,bounds,bounds,TerrainAdjustment.BEARD_THIN);
        var site=new PlanetPlan.Site(7,ResourceLocation.withDefaultNamespace("village_plains"),Face.TOP,u,v,bounds,List.of(piece));
        var plan=new PlanetPlan(terrain.shape().size(),terrain.terrainPlan(),List.of(site),List.of(new PlanetPlan.Template(List.of(new PlanetPlan.Voxel(0,0,0,Blocks.STONE.defaultBlockState())))));
        terrain.initializePlan(()->plan);var function=new SurfaceFunction(terrain);
        for(int x=u-3;x<=u+3;x++)for(int z=v-3;z<=v+3;z++) {
            assertEquals(height,function.groundTop(Face.TOP,x,z));var blocks=function.blocks(Face.TOP,x,z);
            for(int y=height+1;y<320;y++)assertEquals(x==u && z==v && y==height+1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),blocks[y-terrain.floor()]);
        }
        var chunk=new ProtoChunk(new ChunkPos(u/16,v/16),UpgradeData.EMPTY,LevelHeightAccessor.create(planet.getMinY(),planet.getGenDepth()),
                PlanetPlanFixtures.context.registries().registryOrThrow(Registries.BIOME),null);
        new SystemChunkGenerator(dev.starfold.planet.PlanetStorage.LAYOUT,List.of(planet))
                .fillFromNoise(null,null,null,chunk).join();verifyChunk(chunk,function);
        assertEquals(height+1,function.point(Face.TOP,u,v).canopyTop());
    }

    @Test void boundedPlanPartsRoundTripOutOfOrderWaitForMissingRejectConflictingDuplicatesAndLimits() {
        var planet=PlanetPlanFixtures.planet(512,"plains");var voxels=new ArrayList<PlanetPlan.Voxel>();var random=new Random(19);
        for(int u=0;u<64;u++)for(int v=0;v<64;v++)for(int y=0;y<64;y++)
            voxels.add(new PlanetPlan.Voxel(u,y,v,Block.BLOCK_STATE_REGISTRY.byId(random.nextInt(Block.BLOCK_STATE_REGISTRY.size()))));
        var bounds=new PlanetPlan.Bounds(200,0,200,263,63,263);
        var piece=new PlanetPlan.Piece(0,new BlockPos(200,0,200),Rotation.NONE,Mirror.NONE,bounds,bounds,TerrainAdjustment.NONE);
        var plan=new PlanetPlan(512,fixtureTerrain,List.of(new PlanetPlan.Site(1,ResourceLocation.withDefaultNamespace("mineshaft"),Face.TOP,200,200,bounds,List.of(piece))),List.of(new PlanetPlan.Template(voxels)));
        var biomes=PlanetPlanFixtures.context.registries().registryOrThrow(Registries.BIOME);
        var ecology=new dev.starfold.worldgen.PlanetBiomes(planet.seed(),java.util.List.of(biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS),biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.FOREST),biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.DESERT),biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.OCEAN)));
        var policy=new dev.starfold.worldgen.PlanetGeneration("temperate",dev.starfold.worldgen.PlanetGeneration.SURFACE_VERSION,dev.starfold.worldgen.PlanetGeneration.defaults(dev.starfold.worldgen.PlanetTerrain.VERSION).recipe(),512,191,"core_shell",false,false,true,dev.starfold.worldgen.PlanetEnvironment.defaults(true,512));
        planet=new PlanetChunkGenerator(planet.settings(),planet.seed(),dev.starfold.worldgen.PlanetTerrain.VERSION,Optional.of(ecology),Optional.of(policy));
        plan=new PlanetPlan(512,planet.terrain().terrainPlan(),plan.sites(),plan.templates());
        var fixedPlan=plan;planet.terrain().initializePlan(()->fixedPlan);var profile=PlanetPackets.Profile.of(17,planet);
        var transfer=PlanetPlanSync.prepare(profile,PlanetPlanFixtures.context.registries());
        assertEquals(profile.generation().recipe(),transfer.profile().generation().recipe());
        assertTrue(transfer.fullProfileBytes()>1_000_000);assertTrue(transfer.maxPacketBytes()<=1_000_000);assertTrue(transfer.parts().size()>1);
        var raw=Unpooled.buffer();PlanetPackets.Profile metadata;
        try {
            var buffer=new RegistryFriendlyByteBuf(raw,PlanetPlanFixtures.context.registries());
            assertThrows(IllegalArgumentException.class,()->PlanetPackets.Profile.CODEC.encode(buffer,profile));buffer.clear();
            PlanetPackets.Profile.CODEC.encode(buffer,transfer.profile());metadata=PlanetPackets.Profile.CODEC.decode(buffer);assertFalse(metadata.surfaceCompatible());
        } finally {raw.release();}
        PlanetPlanSync.clearCompleted();var assembler=new PlanetPlanSync.Assembler();var delivered=new ArrayList<PlanetPackets.Profile>();assembler.await(metadata,"ground",delivered::add);
        for(int i=transfer.parts().size()-1;i>0;i--) {
            var part=transfer.parts().get(i);var bytes=Unpooled.buffer();
            try {var buffer=new RegistryFriendlyByteBuf(bytes,PlanetPlanFixtures.context.registries());PlanetPlanSync.Part.CODEC.encode(buffer,part);
                var decoded=PlanetPlanSync.Part.CODEC.decode(buffer);assertArrayEquals(part.bytes(),decoded.bytes());assembler.accept(decoded);assembler.accept(decoded);}
            finally {bytes.release();}
        }
        assertTrue(delivered.isEmpty(),"missing first packet cannot publish partial terrain");
        var last=transfer.parts().getLast();var corrupt=last.bytes();corrupt[0]^=1;
        assertThrows(IllegalArgumentException.class,()->assembler.accept(new PlanetPlanSync.Part(last.session(),last.seed(),last.reference(),last.index(),corrupt)));
        assembler.accept(transfer.parts().getFirst());assertEquals(List.of(profile),delivered);
        var empty=new PlanetPlan(512,fixtureTerrain,List.of(),List.of());var emptyRef=new PlanetPlanSync.Reference(empty.identity(),empty.compressed().length);
        for(int i=0;i<20;i++)assembler.accept(new PlanetPlanSync.Part(17,100+i,emptyRef,0,empty.compressed()));
        assertThrows(IllegalArgumentException.class,()->new PlanetPlanSync.Reference(fixedPlan.identity(),PlanetPlan.TRANSFER_LIMIT+1));
        assertThrows(IllegalArgumentException.class,()->new PlanetPlanSync.Part(17,42,last.reference(),last.reference().parts(),last.bytes()));
        var wrong=new PlanetPlanSync.Reference("0".repeat(64),last.reference().bytes());var invalid=new PlanetPlanSync.Assembler();
        assertThrows(IllegalArgumentException.class,()->{for(var part:transfer.parts())invalid.accept(new PlanetPlanSync.Part(17,42,wrong,part.index(),part.bytes()));});
    }

    @Test void asynchronousPlanDecodePublishesOnlyOnThePublisherAndDeduplicatesResends() {
        var profile=PlanetPackets.Profile.of(17,PlanetPlanFixtures.planet(512,"plains"));
        var transfer=PlanetPlanSync.prepare(profile,PlanetPlanFixtures.context.registries());
        PlanetPlanSync.clearCompleted();
        var assembler=new PlanetPlanSync.Assembler();var delivered=new ArrayList<PlanetPackets.Profile>();
        var decoding=new ArrayDeque<Runnable>();var publishing=new ArrayDeque<Runnable>();
        assembler.await(transfer.profile(),"ground",delivered::add);
        for(var part:transfer.parts())assembler.accept(part,decoding::add,publishing::add);
        for(var part:transfer.parts())assembler.accept(part,decoding::add,publishing::add);
        assertEquals(1,decoding.size());assertTrue(publishing.isEmpty());assertTrue(delivered.isEmpty());
        decoding.remove().run();
        assertTrue(delivered.isEmpty(),"background decoding must not publish terrain");
        assertFalse(assembler.await(transfer.profile(),"remote",null,delivered::add));
        assertEquals(1,publishing.size());publishing.remove().run();
        assertEquals(List.of(profile,profile),delivered);
        assembler.accept(transfer.parts().getLast(),decoding::add,publishing::add);
        assertTrue(decoding.isEmpty(),"completed digest resends must not decode again");
    }

    @Test void clearedAndReplacedSessionsDiscardOldAsyncPlanCompletions() {
        var profile=PlanetPackets.Profile.of(17,PlanetPlanFixtures.planet(512,"plains"));
        var transfer=PlanetPlanSync.prepare(profile,PlanetPlanFixtures.context.registries());
        for(long nextSession:new long[]{17,18}) {
            PlanetPlanSync.clearCompleted();var assembler=new PlanetPlanSync.Assembler();
            var decoding=new ArrayDeque<Runnable>();var publishing=new ArrayDeque<Runnable>();
            var delivered=new ArrayList<PlanetPackets.Profile>();
            assembler.await(transfer.profile(),"old",delivered::add);
            for(var part:transfer.parts())assembler.accept(part,decoding::add,publishing::add);
            if(nextSession==17)assembler.clear();
            var replacement=new PlanetPackets.Profile(nextSession,profile.seed(),profile.version(),profile.generation(),
                    profile.biome(),profile.surface(),Optional.empty(),transfer.profile().planReference());
            assembler.await(replacement,"new",delivered::add);
            for(var part:transfer.parts())assembler.accept(new PlanetPlanSync.Part(nextSession,part.seed(),part.reference(),part.index(),part.bytes()),decoding::add,publishing::add);
            decoding.remove().run();publishing.remove().run();
            assertTrue(delivered.isEmpty(),"old completion cannot publish after clear or a new session");
            assertTrue(new PlanetPlanSync.Assembler().await(replacement,"observer",null,delivered::add),"old completion cannot populate the digest cache");
            decoding.remove().run();publishing.remove().run();
            assertEquals(List.of(replacement.withPlan(profile.plan(),Optional.empty())),delivered);
        }
    }

    @Test void asyncPlanDigestFailuresAreReportedOnThePublisher() {
        var profile=PlanetPackets.Profile.of(17,PlanetPlanFixtures.planet(512,"plains"));
        var transfer=PlanetPlanSync.prepare(profile,PlanetPlanFixtures.context.registries());
        PlanetPlanSync.clearCompleted();var assembler=new PlanetPlanSync.Assembler();
        var decoding=new ArrayDeque<Runnable>();var publishing=new ArrayDeque<Runnable>();
        for(var part:transfer.parts())assembler.accept(new PlanetPlanSync.Part(part.session(),part.seed(),
                new PlanetPlanSync.Reference("0".repeat(64),part.reference().bytes()),part.index(),part.bytes()),decoding::add,publishing::add);
        decoding.remove().run();
        assertThrows(IllegalArgumentException.class,()->publishing.remove().run());
    }

    @Test void concurrentMissingReferencesDeferAtByteAndEntryBudgetsUntilRetryCanProgress() throws Exception {
        var base=PlanetPackets.Profile.of(17,PlanetPlanFixtures.planet(512,"plains"));
        var large=new PlanetPlanSync.Reference("1".repeat(64),11_000_000);
        PlanetPlanSync.clearCompleted();var assembler=new PlanetPlanSync.Assembler();var delivered=new ArrayList<PlanetPackets.Profile>();
        for(int i=0;i<3;i++) {
            var metadata=new PlanetPackets.Profile(17,100+i,base.version(),base.generation(),base.biome(),base.surface(),Optional.empty(),Optional.of(large));
            assertEquals(i<2,assembler.await(metadata,i,null,delivered::add));
            assertFalse(assembler.await(metadata,i,null,delivered::add),"no duplicate request or reservation at a full 32 MB budget");
        }
        assertTrue(delivered.isEmpty());
        // Exercise retry progress with real, tiny transfers instead of allocating three 11 MB plans.
        assembler.clear();var plan=base.plan().orElseThrow();
        var pending=new ArrayList<PlanetPackets.Profile>();var plans=new ArrayList<PlanetPlan>();
        for(int i=0;i<17;i++) {
            var fixed=reseed(plan,100+i);plans.add(fixed);var ref=new PlanetPlanSync.Reference(fixed.identity(),fixed.compressed().length);
            var metadata=new PlanetPackets.Profile(17,100+i,base.version(),base.generation(),base.biome(),base.surface(),Optional.empty(),Optional.of(ref));
            pending.add(metadata);assertEquals(i<16,assembler.await(metadata,i,null,delivered::add));
        }
        deliver(assembler,pending.getLast(),plans.getLast());assertTrue(delivered.isEmpty(),"unreserved parts do not bypass the budget");
        deliver(assembler,pending.getFirst(),plans.getFirst());assertEquals(1,delivered.size());
        assertTrue(assembler.await(pending.getLast(),16,null,delivered::add));deliver(assembler,pending.getLast(),plans.getLast());assertEquals(2,delivered.size());
        assertTrue(dev.starfold.Sources.text("GroundViewClient").contains("profile == null || (previewed || !coverageReady())"));
    }

    @Test void evictedPlanReferencesReuseLiveViewsOrRequestOneBoundedResend() throws Exception {
        var planet=PlanetPlanFixtures.planet(512,"plains");var plan=new PlanetPlan(512,planet.terrain().terrainPlan(),List.of(),List.of());
        planet.terrain().initializePlan(()->plan);var base=PlanetPackets.Profile.of(17,planet);
        var reference=new PlanetPlanSync.Reference(plan.identity(),plan.compressed().length);
        PlanetPlanSync.clearCompleted();var assembler=new PlanetPlanSync.Assembler();var profiles=new ArrayList<PlanetPackets.Profile>();
        for(int i=0;i<33;i++) {
            var fixed=reseed(plan,100+i);var known=new PlanetPackets.Profile(17,100+i,base.version(),base.generation(),base.biome(),base.surface(),Optional.of(fixed),Optional.empty());
            profiles.add(known);
        }
        // More completed transfers than the cache holds, then fresh summaries for every live view.
        var delivered=new ArrayList<PlanetPackets.Profile>();
        for(var known:profiles) {
            var ref=new PlanetPlanSync.Reference(known.plan().orElseThrow().identity(),known.plan().orElseThrow().compressed().length);
            var metadata=known.withPlan(Optional.empty(),Optional.of(ref));
            assertFalse(assembler.await(metadata,known.seed(),known,delivered::add));
            assertEquals(known,delivered.getLast());
        }
        PlanetPlanSync.clearCompleted(); // the digest cache lost it too
        var lostPlan=reseed(plan,999);reference=new PlanetPlanSync.Reference(lostPlan.identity(),lostPlan.compressed().length);
        var lost=new PlanetPackets.Profile(17,999,base.version(),base.generation(),base.biome(),base.surface(),Optional.of(lostPlan),Optional.empty());var metadata=lost.withPlan(Optional.empty(),Optional.of(reference));
        delivered.clear();assertTrue(assembler.await(metadata,"lost",null,delivered::add));
        assertFalse(assembler.await(metadata,"lost",null,delivered::add),"only the first missing reference requests a resend");
        assertTrue(delivered.isEmpty());
        var request=new PlanetPlanSync.Request(17,lost.seed(),reference);assertTrue(request.matches(metadata));
        assertFalse(new PlanetPlanSync.Request(18,lost.seed(),reference).matches(metadata));
        assertFalse(new PlanetPlanSync.Request(17,lost.seed()+1,reference).matches(metadata));
        assertFalse(new PlanetPlanSync.Request(17,lost.seed(),new PlanetPlanSync.Reference("0".repeat(64),reference.bytes())).matches(metadata));
        var raw=Unpooled.buffer();
        try {var buffer=new RegistryFriendlyByteBuf(raw,PlanetPlanFixtures.context.registries());
            PlanetPlanSync.Request.CODEC.encode(buffer,request);assertEquals(request,PlanetPlanSync.Request.CODEC.decode(buffer));assertFalse(buffer.isReadable());}
        finally {raw.release();}
        deliver(assembler,metadata,lostPlan);assertEquals(List.of(lost),delivered);
        var ground=dev.starfold.Sources.text("GroundViewClient");var remote=dev.starfold.Sources.text("PlanetRemoteRenderer");
        assertTrue(ground.contains("resolveProfile(incoming,\"ground\",profile,packet->"));
        assertTrue(ground.contains("new dev.starfold.universe.PlanetPlanSync.Request("));
        assertTrue(remote.contains("known==null ? null : known.view.profile()"));
        assertTrue(dev.starfold.Sources.text("GroundViewServer").contains("PlanetRemoteServer.requestPlan(player,packet)"));
        assertTrue(dev.starfold.Sources.text("PlanetRemoteServer").contains("request.matches(viewer.profile.profile())"));
    }

    @Test void allAdaptationsUsePieceBoxesAndSmoothL1EdgesWithoutReadingOtherLayers() {
        var bounds=new PlanetPlan.Bounds(200,130,200,210,140,210);
        for(var adaptation:TerrainAdjustment.values()) {
            var piece=new PlanetPlan.Piece(0,new BlockPos(200,130,200),Rotation.NONE,Mirror.NONE,bounds,bounds,adaptation);
            var site=new PlanetPlan.Site(1,ResourceLocation.withDefaultNamespace("village_plains"),Face.TOP,205,205,bounds,List.of(piece));
            int target=switch(adaptation) {case NONE->120;case BEARD_THIN,BEARD_BOX->129;case BURY->135;case ENCAPSULATE->141;};
            assertEquals(target,PlanetPlan.ground(site,205,205,120));assertEquals(120,PlanetPlan.ground(site,222,205,120));
            int middle=PlanetPlan.ground(site,216,205,120);assertEquals((int)Math.round((target+120)/2.),middle);
            var plan=new PlanetPlan(512,fixtureTerrain,List.of(site),List.of(new PlanetPlan.Template(List.of())));
            for(int raw:new int[]{120,150,100,120})for(int u=199;u<=223;u++) {
                assertEquals(PlanetPlan.ground(site,u,205,raw),plan.ground(Face.TOP,u,205,raw),"cached geometry still reads current L1");
                assertTrue(plan.column(Face.TOP,u,205).isEmpty());
            }
        }
    }

    @Test void emptyRepeatedPiecesCannotAmplifyFootprintColumnsIntoBillionsOfChecks() {
        var bounds=new PlanetPlan.Bounds(100,128,100,355,128,355);
        var piece=new PlanetPlan.Piece(0,new BlockPos(200,128,200),Rotation.NONE,Mirror.NONE,bounds,bounds,TerrainAdjustment.NONE);
        var site=new PlanetPlan.Site(1,ResourceLocation.withDefaultNamespace("igloo"),Face.TOP,200,200,bounds,Collections.nCopies(1024,piece));
        var plan=new PlanetPlan(512,fixtureTerrain,List.of(site),List.of(new PlanetPlan.Template(List.of())));
        assertTrue(plan.compressed().length<new PlanetPlan(512,fixtureTerrain,List.of(),List.of()).compressed().length+16_000);assertEquals(67_108_864,PlanetPlan.footprintColumns(site));
        assertThrows(IllegalArgumentException.class,()->PlanetPlan.decode(plan.compressed()));
    }

    @Test void planPartsAcceptCurrentGroundSpaceAndRetainedSessionsAndCompletionRechecksIdentity() throws Exception {
        assertTrue(PlanetPlanSync.acceptsSession(11,11,0,0));assertTrue(PlanetPlanSync.acceptsSession(22,0,22,0));
        assertTrue(PlanetPlanSync.acceptsSession(33,0,0,33));assertFalse(PlanetPlanSync.acceptsSession(0,0,0,0));
        assertFalse(PlanetPlanSync.acceptsSession(44,11,22,33));
        var ground=dev.starfold.Sources.text("GroundViewClient");var remote=dev.starfold.Sources.text("PlanetRemoteRenderer");
        assertTrue(ground.contains("PlanetRemoteRenderer.acceptsPlanSession(packet.session())"));
        assertTrue(remote.contains("space ? SpaceClient.session() : 0"));assertTrue(remote.contains("SpaceClient.retainedSession() : 0"));
        assertTrue(remote.contains("!accepts(resolved.session(),p.planet())"));assertTrue(remote.contains("Minecraft.getInstance().level!=expectedWorld"));
        assertTrue(ground.contains("GroundFlightClient.planet()!=expectedPlanet"));assertTrue(ground.contains("!state.matches(expectedWorld,incoming.session(),expectedPlanet,state.shape)"));
        assertTrue(dev.starfold.Sources.text("GroundClient").contains("GroundViewClient.initialize()"));
        assertTrue(ground.contains("StarfoldNetwork.clientReceiver(dev.starfold.universe.PlanetPlanSync.Part.TYPE"));
    }

    @Test void defaultDynamicPiecesResolveNearL1WithoutFlatteningToTheirInitialHeight() {
        for(String id:List.of("desert_pyramid","jungle_pyramid","swamp_hut","igloo")) {
            var planet=PlanetPlanFixtures.planet(512,id.equals("igloo") ? "snowy_plains" : id.equals("swamp_hut") ? "swamp" : id.equals("jungle_pyramid") ? "jungle" : "desert");
            var pieces=generate(planet,id,1);var archive=TraditionalPlanetPlanner.record(planet,PlanetPlanFixtures.context,1,ResourceLocation.withDefaultNamespace(id),Face.NORTH,256,256,pieces);
            var site=archive.plan().sites().getFirst();int ground=planet.terrain().groundY(Face.NORTH,256,256),highest=Integer.MIN_VALUE;
            for(var piece:site.pieces()) {
                assertEquals(TerrainAdjustment.NONE,piece.adaptation());
                for(var b:archive.plan().templates().get(piece.template()).blocks())if(!b.state().isAir())highest=Math.max(highest,piece.origin().getY()+b.y());
            }
            assertTrue(highest>=ground && highest<=ground+32,id+" highest="+highest+" L1="+ground);
            assertEquals(ground,archive.plan().ground(Face.NORTH,256,256,ground));
        }
    }

    @Test void placementGridPartitionsCoverAndReflectAcrossEveryDirectedSeamAtEveryLegalSize() {
        var spacings=new HashSet<Integer>();
        for(var set:PlanetPlanFixtures.context.registries().registryOrThrow(Registries.STRUCTURE_SET).getTagOrEmpty(SystemChunkGenerator.STRUCTURES))
            if(set.value().placement() instanceof net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement p)
                spacings.add(p.spacing()*16);
        for(int size=416;size<=2048;size+=16)for(int spacing:spacings) {
            var edges=TraditionalPlanetPlanner.boundaries(size,spacing);int n=edges.length-1;
            assertEquals(0,edges[0]);assertEquals(size,edges[n]);
            var topology=new dev.starfold.geometry.CubeSurfaceTopology(size);
            for(int i=0;i<n;i++) {
                assertTrue(edges[i+1]>edges[i]);assertEquals(size,edges[i]+edges[n-i]);
                for(var face:Face.VALUES)for(int edge=0;edge<4;edge++) {
                    int first=edges[i],last=edges[i+1]-1;
                    var a=topology.canonical(new Cell(face,edge<2 ? (edge==0 ? -1 : size) : first,0,edge>=2 ? (edge==2 ? -1 : size) : first));
                    var b=topology.canonical(new Cell(face,edge<2 ? (edge==0 ? -1 : size) : last,0,edge>=2 ? (edge==2 ? -1 : size) : last));
                    assertEquals(a.cell().face(),b.cell().face());
                    var tangent=a.transport(edge<2 ? new Delta(0,0,1) : new Delta(1,0,0));
                    int ca=tangent.u()!=0 ? a.cell().u() : a.cell().v(),cb=tangent.u()!=0 ? b.cell().u() : b.cell().v();
                    int target=(tangent.u()+tangent.v())>0 ? i : n-1-i;
                    assertEquals(edges[target],Math.min(ca,cb));assertEquals(edges[target+1]-1,Math.max(ca,cb));
                }
            }
        }
    }

    private static void verifyChunk(ProtoChunk chunk,SurfaceFunction function) {
        int faceId=chunk.getPos().getMinBlockX()/4096;var face=Face.VALUES[faceId];
        for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
            int wx=chunk.getPos().getMinBlockX()+x,v=chunk.getPos().getMinBlockZ()+z,u=wx-faceId*4096;
            var expected=function.blocks(face,u,v);var visible=function.visible(face,u,v);
            for(int y=function.terrain().floor();y<PlanetTerrain.MAX_Y;y++)assertEquals(expected[y-function.terrain().floor()],chunk.getBlockState(new BlockPos(wx,y,v)),
                    ()->"chunk mismatch at "+face+" "+u+","+v);
            assertEquals(visible.block(),chunk.getBlockState(new BlockPos(wx,visible.top(),v)));
            for(var side:visible.sides())for(var run:side.runs())for(int y=run.bottom();y<=run.top();y++)
                assertEquals(run.block(),chunk.getBlockState(new BlockPos(wx,y,v)));
            for(var run:visible.objects())for(int y=run.bottom();y<=run.top();y++)assertEquals(run.block(),chunk.getBlockState(new BlockPos(wx,y,v)));
        }
    }
    private static WorldGenLevel chunkLevel(Map<ChunkPos,ProtoChunk> chunks,Map<BlockPos,BlockEntity> entities) {
        return (WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},(proxy,method,args)->switch(method.getName()) {
            case "setBlock" -> {var p=((BlockPos)args[0]).immutable();var s=(BlockState)args[1];chunks.get(new ChunkPos(p)).setBlockState(p,s,false);
                entities.remove(p);if(s.getBlock() instanceof EntityBlock b)entities.put(p,b.newBlockEntity(p,s));yield true;}
            case "getBlockEntity" -> entities.get(args[0]);
            case "registryAccess" -> PlanetPlanFixtures.context.registries();
            case "getBlockState" -> chunks.get(new ChunkPos((BlockPos)args[0])).getBlockState((BlockPos)args[0]);
            case "getSeed" -> 42L;
            case "toString" -> "PlanProtoChunks";
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
    private interface Write { void accept(DataOutputStream out) throws IOException; }
    private static byte[] zip(Write write) throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(new GZIPOutputStream(bytes))){write.accept(out);}return bytes.toByteArray();
    }
    private static void header(DataOutputStream out) throws IOException {out.writeInt(PlanetPlan.FORMAT);out.writeUTF(PlanetPlan.PLANNER);out.writeInt(512);new PlanetPlan(512,fixtureTerrain,List.of(),List.of()).terrain().write(out);}
    private static PlanetPlan reseed(PlanetPlan plan,long seed){var t=plan.terrain();var fields=new float[6][t.nodeCount()];var biomes=new short[t.nodeCount()];var water=new byte[t.nodeCount()];for(int i=0;i<t.nodeCount();i++){for(int f=0;f<6;f++)fields[f][i]=t.field(f,i);biomes[i]=(short)t.biome(i);water[i]=(byte)t.water(i).ordinal();}return new PlanetPlan(plan.size(),new dev.starfold.worldgen.plan.terrain.TerrainPlan(seed,plan.size(),t.recipe(),fields,biomes,water,t.landforms(),t.paths()),plan.sites(),plan.templates());}
    private static void deliver(PlanetPlanSync.Assembler assembler,PlanetPackets.Profile profile,PlanetPlan plan){var data=plan.compressed();var ref=new PlanetPlanSync.Reference(plan.identity(),data.length);for(int i=0;i<ref.parts();i++)assembler.accept(new PlanetPlanSync.Part(profile.session(),profile.seed(),ref,i,Arrays.copyOfRange(data,i*PlanetPlanSync.PART_BYTES,Math.min(data.length,(i+1)*PlanetPlanSync.PART_BYTES))));}
}
