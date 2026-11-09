package dev.starfold.worldgen.surface;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worlds.fixtures.ExperienceFixtures;
import dev.starfold.universe.*;
import dev.starfold.worldgen.PlanetGeneration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Registry bootstrap and real ProtoChunk generators only. Never starts a Minecraft server/client. */
class SurfaceGenerationTest {
    @BeforeAll static void boot() throws Exception { ExperienceFixtures.boot(); SurfaceTestTags.bind(); }
    static PlanetChunkGenerator generator(String name) {
        var type = ExperienceFixtures.registryLookup().lookupOrThrow(PlanetType.KEY).listElements()
                .filter(h -> h.key().location().getNamespace().equals("starfold_worlds") && h.key().location().getPath().equals(name))
                .findFirst().orElseThrow().value();
        return type.planet(42,Optional.of(type.minSize()));
    }
    static ProtoChunk chunk(ChunkGenerator generator, int x, int z) {
        var registry = ExperienceFixtures.biomeRegistryAccess().registryOrThrow(Registries.BIOME);
        var chunk = new ProtoChunk(new ChunkPos(x,z),UpgradeData.EMPTY,
                LevelHeightAccessor.create(generator.getMinY(),generator.getGenDepth()),registry,null);
        assertSame(chunk,dev.starfold.worldgen.SystemGeneration.fill(generator, chunk).join());
        return chunk;
    }
    static BlockState at(List<SurfaceFunction.Run> runs, int y) {
        for (var r : runs) if (y >= r.bottom() && y <= r.top()) return r.block();
        return Blocks.AIR.defaultBlockState();
    }
    /** Independently read actual chunk writes, not SurfaceFunction.blocks as the comparison oracle. */
    static int verify(ProtoChunk chunk, SurfaceFunction f, Face face, int originX, int originZ) {
        var t = f.terrain();
        int checkedObjects = 0;
        var pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int wx = chunk.getPos().getMinBlockX()+x, wz = chunk.getPos().getMinBlockZ()+z;
            int u = wx-originX-face.ordinal()*4096, v = wz-originZ;
            var c = f.visible(face,u,v);
            assertEquals(c.block(),chunk.getBlockState(pos.set(wx,c.top(),wz)),"cap");
            for (var side : c.sides()) for (int y = side.bottom(); y <= side.top(); y++)
                assertEquals(at(side.runs(),y),chunk.getBlockState(pos.set(wx,y,wz)),"exposed side");
            for (int y = c.top()+1; y < chunk.getMaxBuildHeight(); y++) {
                BlockState expected = at(c.objects(),y);
                assertEquals(expected,chunk.getBlockState(pos.set(wx,y,wz)),face+" "+u+","+v+","+y);
                if (!expected.isAir()) checkedObjects++;
            }
        }
        return checkedObjects;
    }
    @Test void groundPlantsUseTheSamePointwisePolicyInChunksAndOnTheClient() {
        var g=generator("lush");
        var terrain=g.terrain();
        var function=new SurfaceFunction(terrain);
        var client=PlanetPackets.Profile.of(17,g).terrain();
        int plants=0,px=0,pz=0;Face selected=Face.TOP;
        search: for(var face:Face.VALUES)for(int z=96;z<terrain.shape().size()-96;z++)for(int x=96;x<terrain.shape().size()-96;x++) {
            var c=function.visible(face,x,z);
            if(c.objects().stream().noneMatch(r->dev.starfold.worldgen.lod.VoxelMesher.plant(r.block())))continue;
            plants++;px=x;pz=z;selected=face;
            assertEquals(c,new SurfaceFunction(client).visible(face,x,z));
            if(plants>10)break search;
        }
        assertTrue(plants>10,"per-block scatter, not one sparse 8-block lattice");
        var chunk=chunk(g,selected.ordinal()*256+px/16,pz/16);
        verify(chunk,function,selected,0,0);
    }

    @Test void allTypesUseVersionedSurfaceAndExtractBiomeFeatures() {
        for (String type : List.of("home","archipelago","asteroid","basalt","crystal","desert","end","ice","lush","mushroom","rocky","warden")) {
            var g = generator(type);
            assertEquals(PlanetGeneration.SURFACE_VERSION,g.generation().surfaceVersion());
            if (g.generation().features()) {
                assertNotNull(g.terrain().scatter());
                System.out.println(type+" entries="+g.terrain().scatter().entries()+" skipped="+g.terrain().scatter().skipped());
            }
        }
        for (String type : List.of("home","archipelago","lush","mushroom")) {
            var scatter = generator(type).terrain().scatter();
            assertTrue(scatter.entries().values().stream().anyMatch(entries -> !entries.isEmpty()),type+" must have sandbox templates, not silent skips");
            assertTrue(scatter.entries().values().stream().flatMap(List::stream).anyMatch(e ->
                    !type.equals("mushroom") ? e.feature() == Feature.TREE : e.feature() == Feature.HUGE_RED_MUSHROOM || e.feature() == Feature.HUGE_BROWN_MUSHROOM));
        }
    }
    @Test void ecologyBiomeTreeSpeciesReachRealSystemChunksAndClientColumns() {
        for (String type : List.of("home", "archipelago")) {
            var planet = generator(type);
            var terrain = planet.terrain();
            var scatter = terrain.scatter();
            var function = new SurfaceFunction(terrain);
            var disabled = PlanetChunkGenerator.CODEC.codec().encodeStart(ExperienceFixtures.ops(), planet)
                    .getOrThrow().getAsJsonObject();
            disabled.getAsJsonObject("planet_generation").addProperty("features", false);
            var withoutTrees = new SurfaceFunction(PlanetChunkGenerator.CODEC.codec()
                    .parse(ExperienceFixtures.ops(), disabled).getOrThrow().terrain());
            var client = new SurfaceFunction(PlanetPackets.Profile.of(17, planet).terrain());
            var system = new SystemChunkGenerator(dev.starfold.planet.PlanetStorage.LAYOUT, List.of(planet));
            var species = new HashSet<net.minecraft.world.level.block.Block>();
            search: for (var face : Face.values()) for (int v = 68; v < terrain.shape().size()-64; v += 8)
                for (int u = 68; u < terrain.shape().size()-64; u += 8) {
                    var candidate = scatter.candidate(face, u, v);
                    if (candidate == null || !scatter.wins(face, candidate)) continue;
                    int x = candidate.u(), z = candidate.v(), y = candidate.base()+1;
                    var blocks = function.blocks(face, x, z);
                    var log = blocks[y-terrain.floor()];
                    if (!log.is(net.minecraft.tags.BlockTags.LOGS) || !species.add(log.getBlock())) continue;
                    assertTrue(terrain.column(face, x, z).state(y).isAir()
                            || !terrain.column(face, x, z).state(y).is(net.minecraft.tags.BlockTags.LOGS),
                            "ecology has no second native tree implementation");
                    assertFalse(Arrays.stream(withoutTrees.blocks(face,x,z))
                            .anyMatch(s -> s.is(net.minecraft.tags.BlockTags.LOGS)), "features=false removes biome trees");
                    assertArrayEquals(blocks, client.blocks(face, x, z));
                    assertEquals(function.visibleExact(face,x,z), function.visible(face,x,z));
                    var generated = chunk(system, face.ordinal()*256+x/16, z/16);
                    for (int h = terrain.floor(); h < 320; h++)
                        assertEquals(blocks[h-terrain.floor()], generated.getBlockState(new BlockPos(face.ordinal()*4096+x,h,z)));
                    for (int h = y; h < 320; h++) if (blocks[h-terrain.floor()].is(net.minecraft.tags.BlockTags.LEAVES))
                        assertFalse(blocks[h-terrain.floor()].getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT));
                    if (species.contains(Blocks.BIRCH_LOG) && species.contains(Blocks.OAK_LOG)) break search;
                }
            assertTrue(species.contains(Blocks.BIRCH_LOG), type+" must honor biome birch trees");
            assertTrue(species.contains(Blocks.OAK_LOG), type+" must honor biome oak trees");
        }
    }

    static PlanetChunkGenerator customEcology(boolean swapped, boolean features) {
        dev.starfold.worldgen.PlanetBiomeFixtures.boot();
        var palette = dev.starfold.worldgen.PlanetBiomeFixtures.palette;
        var home = generator("home");
        var json = PlanetChunkGenerator.CODEC.codec().encodeStart(ExperienceFixtures.ops(), home).getOrThrow().getAsJsonObject();
        json.getAsJsonObject("planet_generation").addProperty("features", features);
        var recipeJson=json.getAsJsonObject("planet_generation").getAsJsonObject("recipe");
        for(var item:recipeJson.getAsJsonArray("biomes")) {
            var params=item.getAsJsonObject().getAsJsonObject("parameters");String id=params.get("biome").getAsString();
            if(id.equals(swapped?"minecraft:plains":"minecraft:forest"))params.addProperty("biome","starfold:custom_forest");
            else if(swapped&&id.equals("minecraft:forest"))params.addProperty("biome","minecraft:plains");
        }
        var generation = PlanetGeneration.CODEC.parse(ExperienceFixtures.ops(), json.get("planet_generation")).getOrThrow();
        var biomes = generation.recipe().biomeIds().stream()
                .sorted(java.util.Comparator.comparing(net.minecraft.resources.ResourceLocation::toString))
                .map(id -> (net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>) palette.getHolderOrThrow(
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, id)))
                .toList();
        return new PlanetChunkGenerator(home.settings(),home.seed(),dev.starfold.worldgen.PlanetTerrain.VERSION,Optional.of(new dev.starfold.worldgen.PlanetBiomes(home.seed(),biomes)),Optional.of(generation));
    }
    static PlanetPackets.Profile wire(PlanetPackets.Profile profile) {
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var access = new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(
                    List.of(dev.starfold.worldgen.PlanetBiomeFixtures.palette));
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(raw, access);
            PlanetPackets.Profile.CODEC.encode(buffer, profile);
            var copy = PlanetPackets.Profile.CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            assertEquals(profile, copy);
            return copy;
        } finally { raw.release(); }
    }
    @Test void customEcologyRolesSurviveProfileWireAndGenerateTheSameRealTreeColumns() {
        var planet = customEcology(false, true);
        var terrain = planet.terrain();
        var scatter = terrain.scatter();
        var server = new SurfaceFunction(terrain);
        var profile = wire(PlanetPackets.Profile.of(17, planet));
        var client = new SurfaceFunction(profile.terrain());
        var custom = dev.starfold.worldgen.PlanetBiomeFixtures.CUSTOM_FOREST;
        int checked = 0;
        search: for (var face : Face.values()) for (int v = 68; v < terrain.shape().size()-64; v += 8)
            for (int u = 68; u < terrain.shape().size()-64; u += 8) {
                var root = scatter.candidate(face, u, v);
                if (root == null || !scatter.wins(face, root)) continue;
                int x = root.u(), z = root.v();
                var biome = planet.getBiomeSource().getNoiseBiome((face.ordinal()*4096+x)/4, 0, z/4, null);
                if (!biome.is(custom)) continue;
                var blocks = server.blocks(face, x, z);
                if (!blocks[root.base()+1-terrain.floor()].is(net.minecraft.tags.BlockTags.LOGS)) continue;
                assertArrayEquals(blocks, client.blocks(face, x, z), "custom forest tree must survive the complete profile wire");
                assertEquals(custom, dev.starfold.worldgen.PlanetTint.biome(client.terrain(), profile.biome(),                         face.ordinal(), x/4*4+2, z/4*4+2));
                var system = new SystemChunkGenerator(dev.starfold.planet.PlanetStorage.LAYOUT, List.of(planet));
                var generated = chunk(system, face.ordinal()*256+x/16, z/16);
                for (int y = terrain.floor(); y < 320; y++)
                    assertEquals(blocks[y-terrain.floor()], generated.getBlockState(new BlockPos(face.ordinal()*4096+x,y,z)));
                checked++;
                if (checked == 2) break search;
            }
        assertEquals(2, checked, "must compare accepted real custom forest tree roots");
    }
    @Test void ecologyRoleOrderChangesScatterAndSurfaceIdentity() {
        var first = customEcology(false, true).terrain();
        var second = customEcology(true, true).terrain();
        assertEquals(first.scatter().entries().keySet(), second.scatter().entries().keySet());
        assertNotEquals(first.scatter().identity(), second.scatter().identity());
        assertNotEquals(SurfaceKey.of(first), SurfaceKey.of(second));
        assertEquals(SurfaceKey.of(first), SurfaceKey.of(wire(PlanetPackets.Profile.of(17, customEcology(false, true))).terrain()));
    }

    @Test void disabledFeaturesKeepFixedBiomePaletteThroughPlanTransfers() {
        var profile=wire(PlanetPackets.Profile.of(17,customEcology(false,false)));var plan=profile.plan().orElseThrow();
        assertTrue(profile.biomesKnown(dev.starfold.worldgen.PlanetBiomeFixtures.palette::containsKey));
        for(var id:profile.generation().recipe().biomeIds())assertFalse(profile.biomesKnown(key->!key.equals(id)));
        var reference=new PlanetPlanSync.Reference(plan.identity(),plan.compressed().length);
        var metadata=wire(profile.withPlan(Optional.empty(),Optional.of(reference)));assertFalse(metadata.surfaceCompatible());
        var assembler=new PlanetPlanSync.Assembler();var completed=new ArrayList<PlanetPackets.Profile>();assembler.await(metadata,"remote",completed::add);
        for(var part:PlanetPlanSync.prepare(profile,new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(List.of(dev.starfold.worldgen.PlanetBiomeFixtures.palette))).parts())assembler.accept(part);
        assertEquals(List.of(profile),completed);assertEquals(SurfaceKey.of(profile.terrain()),SurfaceKey.of(completed.getFirst().terrain()));
    }

    @Test void realChunkGenerationAndReversedOrderAreIdenticalIncludingBorderObjects() {
        for (String type : List.of("home","lush","mushroom","ice","basalt","end")) {
            var first = generator(type); var second = generator(type);
            var system = new SystemChunkGenerator(dev.starfold.planet.PlanetStorage.LAYOUT,List.of(second));
            var f = new SurfaceFunction(first.terrain());
            int size = first.terrain().shape().size();
            int centreU = size/2/16, centreV = centreU;Face selectedFace=Face.NORTH;
            if (type.equals("lush") || type.equals("mushroom")) {
                boolean found = false;
                outer: for(var face:Face.VALUES)for (int v = 48; v < size-48; v += 8) for (int u = 48; u < size-48; u += 16)
                    if (f.visible(face,u,v).objects().stream().anyMatch(r -> canopy(r.block()))) {
                        centreU = u/16; centreV = v/16; selectedFace=face;found = true; break outer;
                    }
                assertTrue(found,type+" must expose an object at a storage chunk border");
            }
            var chunks = new ArrayList<ProtoChunk>();
            for (int z = centreV-2; z <= centreV+2; z++) for (int x = centreU-2; x <= centreU+2; x++)
                chunks.add(chunk(first,selectedFace.ordinal()*256+x,z));
            int objects = 0, borderObjects = 0, crossings = 0;
            var byPosition = new HashMap<ChunkPos,ProtoChunk>();
            for (var a : chunks) byPosition.put(a.getPos(),a);
            Collections.reverse(chunks);
            for (var a : chunks) {
                var b = chunk(system,a.getPos().x,a.getPos().z);
                objects += verify(a,f,selectedFace,0,0);
                verify(b,new SurfaceFunction(second.terrain()),selectedFace,0,0);
                var p = new BlockPos.MutableBlockPos();
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    int wx = a.getPos().getMinBlockX()+x, wz = a.getPos().getMinBlockZ()+z;
                    int cap = f.groundTop(selectedFace,wx-selectedFace.ordinal()*4096,wz);
                    for (int y = a.getMinBuildHeight(); y < a.getMaxBuildHeight(); y++) {
                        var state = a.getBlockState(p.set(wx,y,wz));
                        assertEquals(state,b.getBlockState(p),type+" independent/reversed chunk generation");
                        if ((x == 0 || x == 15 || z == 0 || z == 15) && y > cap && !state.isAir()) borderObjects++;
                    }
                }
            }
            for (var a : chunks) {
                var neighbour = byPosition.get(new ChunkPos(a.getPos().x+1,a.getPos().z));
                if (neighbour == null) continue;
                int wx = neighbour.getPos().getMinBlockX(), z0 = a.getPos().getMinBlockZ();
                for (int z = z0; z < z0+16; z++) {
                    int cap = Math.max(f.groundTop(selectedFace,wx-1-selectedFace.ordinal()*4096,z),
                            f.groundTop(selectedFace,wx-selectedFace.ordinal()*4096,z));
                    for (int y = cap+1; y < a.getMaxBuildHeight(); y++) {
                        var left = a.getBlockState(new BlockPos(wx-1,y,z));
                        var right = neighbour.getBlockState(new BlockPos(wx,y,z));
                        if (canopy(left) && left.getBlock() == right.getBlock()) crossings++;
                    }
                }
            }
            if (type.equals("lush") || type.equals("mushroom")) {
                assertTrue(objects > 0,type+" objects required");
                assertTrue(borderObjects > 0,type+" border objects required");
                assertTrue(crossings > 0,type+" connected crowns must cross chunk borders");
            }
        }
    }
    static boolean canopy(BlockState block) {
        return block.is(net.minecraft.tags.BlockTags.LEAVES) || block.is(Blocks.RED_MUSHROOM_BLOCK) || block.is(Blocks.BROWN_MUSHROOM_BLOCK);
    }
    @Test void templateBlocksTransportAcrossCubeFaceSeams() {
        var g = generator("lush");
        var t = g.terrain(); var scatter = t.scatter();
        int size = t.shape().size(), checked = 0;
        outer: for (var face : Face.values()) for (int edge = 0; edge < 4; edge++) for (int along = 4; along < size; along += 8) {
            int u = edge < 2 ? (edge == 0 ? 4 : size-4) : along;
            int v = edge >= 2 ? (edge == 2 ? 4 : size-4) : along;
            var c = scatter.candidate(face,u,v);
            if (c == null || !scatter.wins(face,c)) continue;
            int r = c.template().radius();
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                var query = new dev.starfold.geometry.CubeSurfaceTopology.Cell(face,c.u()+dx,0,c.v()+dz);
                if (t.shape().topology().owned(query)) continue;
                var route = t.shape().topology().canonical(query);
                var owner = route.cell();
                int cap = new SurfaceFunction(t).groundTop(owner.face(),owner.u(),owner.v());
                var actual = scatter.column(owner.face(),owner.u(),owner.v());
                for (int y = 1; y <= c.template().height(); y++) {
                    var expected = c.template().block(dx,y,dz);
                    if (expected.isAir() || c.base()+y <= cap) continue;
                    assertEquals(expected.rotate(net.minecraft.world.level.block.Rotation.values()[route.turns()]),at(actual,c.base()+y));
                    checked++;
                }
            }
            if (checked > 32) break outer;
        }
        assertTrue(checked > 0,"Real accepted tree templates must span a face seam, not be suppressed at edges");
    }
    @Test void translatedSystemChunksAndSmallFaceGapsRespectOwnership() {
        var lush = generator("lush");
        var system = new SystemChunkGenerator(dev.starfold.planet.PlanetStorage.LAYOUT,List.of(generator("rocky"),lush));
        int ox = dev.starfold.planet.PlanetStorage.column(1)*dev.starfold.planet.PlanetStorage.PLANET_STRIDE;
        int oz = dev.starfold.planet.PlanetStorage.row(1)*dev.starfold.planet.PlanetStorage.ROW_STRIDE;
        var c = chunk(system,ox/16+Face.NORTH.ordinal()*256+32,oz/16+32);
        verify(c,new SurfaceFunction(lush.terrain()),Face.NORTH,ox,oz);
        var small = generator("asteroid");
        var gap = chunk(small,small.terrain().shape().chunks(),0);
        for (var section : gap.getSections()) assertTrue(section.hasOnlyAir());
    }
    @Test void sandboxExecutesVanillaIceRockAndBambooShapesAndRejectsUnboundedWrites() {
        var g = generator("ice");
        var none = net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.INSTANCE;
        var spike = FeatureSandbox.build(new net.minecraft.world.level.levelgen.feature.ConfiguredFeature<>(Feature.ICE_SPIKE,none),g,Blocks.SNOW_BLOCK.defaultBlockState(),42);
        var iceberg = FeatureSandbox.build(new net.minecraft.world.level.levelgen.feature.ConfiguredFeature<>(Feature.ICEBERG,
                new net.minecraft.world.level.levelgen.feature.configurations.BlockStateConfiguration(Blocks.PACKED_ICE.defaultBlockState())),g,Blocks.SNOW_BLOCK.defaultBlockState(),42);
        var rock = FeatureSandbox.build(new net.minecraft.world.level.levelgen.feature.ConfiguredFeature<>(Feature.FOREST_ROCK,
                new net.minecraft.world.level.levelgen.feature.configurations.BlockStateConfiguration(Blocks.MOSSY_COBBLESTONE.defaultBlockState())),g,Blocks.GRASS_BLOCK.defaultBlockState(),42);
        var bamboo = FeatureSandbox.build(new net.minecraft.world.level.levelgen.feature.ConfiguredFeature<>(Feature.BAMBOO,
                new net.minecraft.world.level.levelgen.feature.configurations.ProbabilityFeatureConfiguration(0)),g,Blocks.GRASS_BLOCK.defaultBlockState(),42);
        for (var template : List.of(spike,iceberg,rock,bamboo)) {
            assertTrue(template.blocks() > 0);
            assertTrue(template.radius() <= FeatureSandbox.RADIUS);
            assertTrue(template.height() <= FeatureSandbox.HEIGHT);
        }
        var huge = new Feature<net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration>(
                net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.CODEC) {
            @Override public boolean place(net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration> context) {
                context.level().setBlock(context.origin().offset(33,0,0),Blocks.STONE.defaultBlockState(),2);
                return true;
            }
        };
        assertThrows(UnsupportedOperationException.class,() -> FeatureSandbox.build(
                new net.minecraft.world.level.levelgen.feature.ConfiguredFeature<>(huge,none),g,Blocks.GRASS_BLOCK.defaultBlockState(),42));
    }
    @Test void variantFootprintsAreDisjointAcrossBiomeEntries() {
        var scatter = generator("lush").terrain().scatter();
        var accepted = new ArrayList<BiomeScatter.Candidate>();
        for (int v = 4; v < 512; v += BiomeScatter.SPACING) for (int u = 4; u < 512; u += BiomeScatter.SPACING) {
            var c = scatter.candidate(Face.NORTH,u,v);
            if (c == null || !scatter.wins(Face.NORTH,c)) continue;
            for (var earlier : accepted) {
                int reach = c.template().radius()+earlier.template().radius();
                assertTrue(Math.abs(c.u()-earlier.u()) > reach || Math.abs(c.v()-earlier.v()) > reach);
            }
            accepted.add(c);
        }
        assertTrue(accepted.size() > 10);
    }
    @Test void biomeDecorationWithoutStructuresNeverExecutesVanillaFeatures() {
        var json = PlanetChunkGenerator.CODEC.codec().encodeStart(ExperienceFixtures.ops(), generator("lush"))
                .getOrThrow().getAsJsonObject();
        json.getAsJsonObject("planet_generation").addProperty("structures", false);
        var planet = PlanetChunkGenerator.CODEC.codec().parse(ExperienceFixtures.ops(), json).getOrThrow();
        assertTrue(planet.generation().features());
        assertFalse(planet.generation().structures());
        var system = new SystemChunkGenerator(dev.starfold.planet.PlanetStorage.LAYOUT, List.of(planet));
        var c = chunk(system, 32, 32);
        var world = (WorldGenLevel) java.lang.reflect.Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class},
                (proxy, method, args) -> { throw new AssertionError("Vanilla feature access: " + method); });
        system.applyBiomeDecoration(world, c, null);
    }
    @Test void visibleClassificationRecursesSelectorsButLeavesSmallAndUndergroundFeatures() {
        var placed = ExperienceFixtures.registryLookup().lookupOrThrow(Registries.PLACED_FEATURE);
        for (var holder : placed.listElements().toList()) {
            var p = holder.value();
            boolean expected = p.getFeatures().anyMatch(c -> VisibleFeatures.visible(c.feature()));
            assertEquals(expected,VisibleFeatures.visible(p));
        }
        assertTrue(VisibleFeatures.visible(new PlacedFeature(net.minecraft.core.Holder.direct(
                new net.minecraft.world.level.levelgen.feature.ConfiguredFeature<>(Feature.ICE_SPIKE,
                        net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.INSTANCE)),List.of())));
        for (var holder : placed.listElements().filter(h -> Set.of("ore_diamond").contains(h.key().location().getPath())).toList())
            assertFalse(VisibleFeatures.visible(holder.value()),holder.key().toString());
        assertTrue(VisibleFeatures.visible(Feature.RANDOM_PATCH));
        assertTrue(VisibleFeatures.visible(Feature.FLOWER));
        assertTrue(VisibleFeatures.visible(Feature.SIMPLE_BLOCK));
        var count = new PlacedFeature(net.minecraft.core.Holder.direct(new net.minecraft.world.level.levelgen.feature.ConfiguredFeature<>(Feature.ICE_SPIKE,
                net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.INSTANCE)),List.of(
                net.minecraft.world.level.levelgen.placement.CountPlacement.of(8),net.minecraft.world.level.levelgen.placement.RarityFilter.onAverageOnceEvery(2)));
        assertEquals(4/256.,VisibleFeatures.density(count));
    }
}
