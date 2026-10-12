package dev.starfold.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import dev.starfold.StarfoldNetwork;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.PlanetCore;
import dev.starfold.planet.PlanetPackets;
import dev.starfold.planet.PlanetSpawn;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.planet.PlanetType;
import dev.starfold.planet.PlanetTypeTest;
import dev.starfold.sky.GroundDaylight;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Offline geology and real isolated chunks, not survival/game acceptance. */
class PlanetAndesiteTest {
    @BeforeAll
    static void boot() {
        PlanetTypeTest.boot();
    }

    static SystemChunkGenerator system() throws Exception {
        return SystemChunkGenerator.CODEC
                .codec()
                .parse(PlanetTypeTest.ops(), PlanetTypeTest.preset("native_universe"))
                .getOrThrow();
    }

    static PlanetGeneration policy(PlanetGeneration source, boolean enabled) {
        var json =
                PlanetGeneration.CODEC
                        .encodeStart(JsonOps.INSTANCE, source)
                        .getOrThrow()
                        .getAsJsonObject();
        var minerals = json.has("minerals") ? json.getAsJsonObject("minerals") : new JsonObject();
        minerals.addProperty("shallow_andesite", enabled);
        json.add("minerals", minerals);
        return PlanetGeneration.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    }

    static PlanetTerrain baseline(PlanetTerrain terrain) {
        return new PlanetTerrain(
                terrain.seed(), terrain.version(), policy(terrain.generation(), false));
    }

    static ProtoChunk chunk(PlanetChunkGenerator generator, int x, int z) {
        var height = LevelHeightAccessor.create(-528, 848);
        var chunk =
                new ProtoChunk(
                        new ChunkPos(x, z),
                        UpgradeData.EMPTY,
                        height,
                        PlanetTerrainTest.biomes,
                        null);
        assertSame(chunk, generator.fillFromNoise(null, null, null, chunk).join());
        return chunk;
    }

    @Test
    void defaultHomeHasShallowBlobsWithinThreeChunksAndRecordsDistribution() throws Exception {
        var system = system();
        var terrain = system.planet(0).terrain();
        var old = baseline(terrain);
        var face =
                Face.values()[
                        GroundDaylight.homeFace(
                                PlanetSystem.model(system.groups()), terrain.shape())];
        var spawn = PlanetSpawn.dryLand(terrain, face);
        int width = 128, x0 = spawn[0] - width / 2, z0 = spawn[1] - width / 2;
        long stone = 0, andesite = 0, caveWalls = 0, chunkCrossings = 0;
        int[] histogram = new int[4];
        var found = new ArrayList<int[]>();
        int[] closest = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (int x = 0; x < width; x++)
            for (int z = 0; z < width; z++) {
                int u = x0 + x, v = z0 + z;
                var before = old.column(face, u, v);
                var after = terrain.column(face, u, v);
                boolean has = false;
                for (int depth = 1; depth <= 40; depth++) {
                    int y = before.ground() - 3 - depth;
                    if (before.state(y).is(Blocks.STONE)) {
                        stone++;
                        if (after.state(y).is(Blocks.ANDESITE)) {
                            andesite++;
                            histogram[(depth - 1) / 10]++;
                            if (before.state(y - 1).isAir() || before.state(y + 1).isAir())
                                caveWalls++;
                            if ((u & 15) == 15
                                    && terrain.state(face, u + 1, y, v).is(Blocks.ANDESITE))
                                chunkCrossings++;
                            if ((v & 15) == 15
                                    && terrain.state(face, u, y, v + 1).is(Blocks.ANDESITE))
                                chunkCrossings++;
                            has = true;
                            double distance = Math.hypot(u - spawn[0], v - spawn[1]);
                            if (distance < nearest) {
                                nearest = distance;
                                closest = new int[] {face.ordinal() * 4096 + u, y, v};
                            }
                        }
                    }
                }
                if (has) found.add(new int[] {u, v});
            }
        assertTrue(andesite > 0, "the home planet needs actual shallow andesite");
        assertTrue(caveWalls > 0, "some blobs meet natural cave walls");
        assertTrue(chunkCrossings > 0, "blobs cross actual chunk borders");
        double share = andesite / (double) stone;
        assertTrue(share > .005 && share < .10, "stone share " + share);
        for (int count : histogram) assertTrue(count > 0, Arrays.toString(histogram));
        var random = new Random(6301);
        double sum = 0, max = 0;
        int within = 0;
        for (int i = 0;
                i < 1024;
                i++) { // Central area leaves a 32-block search halo, avoiding optimistic
                       // wraparound.
            int u = x0 + 32 + random.nextInt(64), v = z0 + 32 + random.nextInt(64);
            double best = Double.POSITIVE_INFINITY;
            for (var at : found) best = Math.min(best, Math.hypot(u - at[0], v - at[1]));
            sum += best;
            max = Math.max(max, best);
            if (best <= 48) within++;
        }
        assertTrue(
                sum / 1024 < 48 && within >= 1014, "mean " + sum / 1024 + ", within 48 " + within);
        assertTrue(max < 32, "nearest search stays inside the sampled halo: " + max);
        assertTrue(nearest <= 48, "spawn distance " + nearest);
        var blob = new LinkedHashSet<BlockPos>();
        var pending = new ArrayDeque<BlockPos>();
        pending.add(new BlockPos(closest[0], closest[1], closest[2]));
        while (!pending.isEmpty()) {
            var pos = pending.removeFirst();
            if (blob.contains(pos)
                    || !terrain.state(
                                    face,
                                    pos.getX() - face.ordinal() * 4096,
                                    pos.getY(),
                                    pos.getZ())
                            .is(Blocks.ANDESITE)) continue;
            blob.add(pos);
            assertTrue(blob.size() < 10000, "bounded ordinary blob");
            for (var direction : net.minecraft.core.Direction.values())
                pending.add(pos.relative(direction));
        }
        assertTrue(
                blob.size() >= 14,
                "the nearest connected patch supplies the 14-block workshop budget: "
                        + blob.size());
        var result = new JsonObject();
        result.addProperty("nearest_patch_connected_blocks", blob.size());
        result.add(
                "first_14_patch_blocks_xyz",
                new com.google.gson.Gson()
                        .toJsonTree(
                                blob.stream()
                                        .limit(14)
                                        .map(p -> new int[] {p.getX(), p.getY(), p.getZ()})
                                        .toList()));
        result.addProperty("seed", terrain.seed());
        result.addProperty("face", face.name());
        result.addProperty("spawn_x", face.ordinal() * 4096 + spawn[0]);
        result.addProperty("spawn_z", spawn[1]);
        result.addProperty("spawn_ground_y", terrain.groundY(face, spawn[0], spawn[1]));
        result.addProperty("sample_width", width);
        result.addProperty("eligible_stone", stone);
        result.addProperty("andesite", andesite);
        result.addProperty("share", share);
        result.addProperty("cave_wall_andesite", caveWalls);
        result.addProperty("andesite_pairs_across_chunk_borders", chunkCrossings);
        result.addProperty("mean_horizontal_distance", sum / 1024);
        result.addProperty("max_horizontal_distance", max);
        result.addProperty("within_48_of_1024", within);
        result.add(
                "depth_bins_1_10_11_20_21_30_31_40",
                new com.google.gson.Gson().toJsonTree(histogram));
        result.add("nearest_spawn_andesite_xyz", new com.google.gson.Gson().toJsonTree(closest));
        result.addProperty("spawn_horizontal_distance", nearest);
        Files.createDirectories(Path.of("build/space"));
        Files.writeString(
                Path.of("build/space/andesite-metrics.json"),
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result)
                        + "\n");
    }

    @Test
    void onlyPlainShallowStoneChangesAndOresSoilSpecialBlocksCoreAndClosureSurvive()
            throws Exception {
        var terrain = system().planet(0).terrain();
        var old = baseline(terrain);
        int changed = 0, ore = 0, core = 0, special = 0;
        for (var face : Face.values())
            for (int u : new int[] {0, 1, 8, 32, 320, 700, 1024, 1400, 1800, 2039, 2047})
                for (int v : new int[] {0, 1, 8, 32, 320, 700, 1024, 1400, 1800, 2039, 2047}) {
                    var a = old.column(face, u, v);
                    var b = terrain.column(face, u, v);
                    assertEquals(a.ground(), b.ground());
                    for (int y = terrain.floor(); y < 320; y++) {
                        var before = a.state(y);
                        var after = b.state(y);
                        if (before != after) {
                            changed++;
                            assertTrue(
                                    before.is(Blocks.STONE) && after.is(Blocks.ANDESITE),
                                    before + " -> " + after);
                            assertTrue(
                                    y <= a.ground() && y >= a.ground() - 43 && y > terrain.floor());
                        }
                        if (before.getBlock().toString().contains("_ore")) {
                            ore++;
                            assertSame(before, after);
                        }
                        if (before.getBlock() == PlanetCore.SHELL_BLOCK) {
                            core++;
                            assertSame(before, after);
                        }
                        if (before.getBlock() == Anomaly.CORE_BLOCK) {
                            special++;
                            assertSame(before, after);
                        }
                    }
                }
        assertTrue(
                changed > 100 && ore > 100 && core > 0 && special > 0,
                changed + " " + ore + " " + core + " " + special);
    }

    @Test
    void sameSeedAndEveryDirectedSeamHaveTheSameStone() throws Exception {
        var terrain = system().planet(0).terrain();
        var again = new PlanetTerrain(terrain.seed(), terrain.version(), terrain.generation());
        var other = new PlanetTerrain(terrain.seed() + 1, terrain.version(), terrain.generation());
        int different = 0, andesite = 0;
        for (var face : Face.values())
            for (int[] edge : new int[][] {{-1, 1024}, {2048, 1024}, {1024, -1}, {1024, 2048}}) {
                var cell =
                        terrain.shape()
                                .topology()
                                .canonical(new CubeSurfaceTopology.Cell(face, edge[0], 0, edge[1]))
                                .cell();
                for (int y = 80; y < 180; y++) {
                    var state = terrain.state(face, edge[0], y, edge[1]);
                    assertSame(state, terrain.state(cell.face(), cell.u(), y, cell.v()));
                    assertSame(state, again.state(face, edge[0], y, edge[1]));
                    if (state.is(Blocks.ANDESITE)) andesite++;
                    if (state.is(Blocks.ANDESITE)
                            != other.state(face, edge[0], y, edge[1]).is(Blocks.ANDESITE))
                        different++;
                }
            }
        assertTrue(andesite > 0 && different > 0, andesite + " " + different);
    }

    @Test
    void sharedEdgeNodesSampleOneGlobalPatchField() throws Exception {
        var terrain = system().planet(0).terrain();
        var minerals = terrain.generation().minerals();
        int andesite = 0, edges = 0;
        for (var face : Face.values())
            for (int along = 64; along < 1984; along += 32)
                for (int[] edge :
                        new int[][] {{0, along}, {2048, along}, {along, 0}, {along, 2048}}) {
                    var cube =
                            face.normal
                                    .scale(1024)
                                    .add(face.u.scale(edge[0] - 1024))
                                    .add(face.v.scale(edge[1] - 1024));
                    for (var other : Face.values())
                        if (other != face && cube.dot(other.normal) == 1024) {
                            edges++;
                            var a = terrain.unit(face, edge[0], edge[1]);
                            var b =
                                    terrain.unit(
                                            other,
                                            1024 + cube.dot(other.u),
                                            1024 + cube.dot(other.v));
                            assertEquals(a, b);
                            for (int y = 97; y <= 140; y++) {
                                var first =
                                        minerals.shallow(
                                                terrain,
                                                PlanetTerrain.lattices(),
                                                a,
                                                140,
                                                y,
                                                Blocks.STONE.defaultBlockState());
                                assertSame(
                                        first,
                                        minerals.shallow(
                                                terrain,
                                                PlanetTerrain.lattices(),
                                                b,
                                                140,
                                                y,
                                                Blocks.STONE.defaultBlockState()));
                                if (first.is(Blocks.ANDESITE)) andesite++;
                            }
                        }
                }
        assertTrue(edges > 1000 && andesite > 0, edges + " " + andesite);
    }

    @Test
    void generatedNeighborChunksAgreeWithSavedClientSamplerAndSurfaceCover() throws Exception {
        var generator = system().planet(0);
        var terrain = generator.terrain();
        var buffer =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        PlanetTerrain derived;
        try {
            var profile =
                    new PlanetPackets.Profile(
                            63, generator.seed(), generator.version(), generator.generation());
            PlanetPackets.Profile.CODEC.encode(buffer, profile);
            var received = PlanetPackets.Profile.CODEC.decode(buffer);
            assertEquals(profile, received);
            derived = new PlanetTerrain(received.seed(), received.version(), received.generation());
        } finally {
            buffer.release();
        }
        var heights = new PlanetHeightCache(derived);
        int andesite = 0;
        for (var face : Face.values())
            for (int cx : new int[] {63, 64}) {
                var actual = chunk(generator, face.ordinal() * 256 + cx, 64);
                for (int x = 0; x < 16; x++)
                    for (int z = 0; z < 16; z++) {
                        int u = cx * 16 + x, v = 1024 + z, wx = face.ordinal() * 4096 + u;
                        var column =
                                generator.getBaseColumn(
                                        wx, v, LevelHeightAccessor.create(-528, 848), null);
                        for (int y = terrain.floor(); y < 320; y++) {
                            var state = actual.getBlockState(new BlockPos(wx, y, v));
                            assertSame(state, column.getBlock(y));
                            assertSame(state, derived.state(face, u, y, v));
                            if (state.is(Blocks.ANDESITE)) andesite++;
                        }
                        int top = terrain.surfaceY(face, u, v);
                        assertSame(
                                actual.getBlockState(new BlockPos(wx, top, v)),
                                heights.surfaceState(face, u, v));
                    }
                // Reverse/repeated generation does not depend on a neighbor having been generated.
                var repeat = chunk(generator, face.ordinal() * 256 + cx, 64);
                for (int z = 0; z < 16; z++)
                    for (int y = 80; y < 180; y++)
                        assertSame(
                                actual.getBlockState(
                                        new BlockPos(
                                                actual.getPos().getMinBlockX() + 15, y, 1024 + z)),
                                repeat.getBlockState(
                                        new BlockPos(
                                                actual.getPos().getMinBlockX() + 15, y, 1024 + z)));
            }
        assertTrue(andesite > 0);
    }

    @Test
    void actualSupportedSystemChunksAndBareSurfaceSamplesAgree() throws Exception {
        var system = system();
        var terrain = system.planet(0).terrain();
        int exposed = 0;
        for (var face : Face.values())
            for (int u = 16; u < 2048; u += 32)
                for (int v = 16; v < 2048; v += 32) {
                    var top = terrain.surfaceState(face, u, v);
                    if (top.is(Blocks.ANDESITE)) {
                        exposed++;
                        assertSame(
                                top,
                                terrain.column(face, u, v).state(terrain.surfaceY(face, u, v)));
                    }
                }
        assertTrue(exposed > 0, "andesite sometimes exposed on bare stone slopes");
        var face = Face.values()[GroundDaylight.homeFace(system.homeModel(), terrain.shape())];
        var spawn = PlanetSpawn.dryLand(terrain, face);
        int wx = face.ordinal() * 4096 + spawn[0];
        for (int dx = -1; dx <= 1; dx++) {
            var actual =
                    new ProtoChunk(
                            new ChunkPos((wx >> 4) + dx, spawn[1] >> 4),
                            UpgradeData.EMPTY,
                            LevelHeightAccessor.create(-528, 848),
                            PlanetTerrainTest.biomes,
                            null);
            system.fillFromNoise(null, null, null, actual).join();
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++) {
                    int ax = actual.getPos().getMinBlockX() + x,
                            az = actual.getPos().getMinBlockZ() + z;
                    var col =
                            system.getBaseColumn(
                                    ax, az, LevelHeightAccessor.create(-528, 848), null);
                    for (int y = terrain.floor(); y < 320; y++)
                        assertSame(col.getBlock(y), actual.getBlockState(new BlockPos(ax, y, az)));
                }
        }
    }

    @Test
    void savedPolicyOptsInExplicitlyAndOldWorldsDoNotSilentlyUpgrade() throws Exception {
        assertEquals(
                "3",
                StarfoldNetwork.PROTOCOL,
                "changed mineral and identity wire formats must reject old peers");
        var fresh = system();
        var ops = PlanetTypeTest.ops();
        var saved =
                SystemChunkGenerator.CODEC
                        .codec()
                        .encodeStart(ops, fresh)
                        .getOrThrow()
                        .getAsJsonObject();
        var generation =
                saved.getAsJsonArray("planets")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("planet_generation");
        assertTrue(generation.getAsJsonObject("minerals").get("shallow_andesite").getAsBoolean());
        var loaded = SystemChunkGenerator.CODEC.codec().parse(ops, saved).getOrThrow();
        assertEquals(fresh.planet(0).generation(), loaded.planet(0).generation());
        generation.remove("minerals");
        var legacy = SystemChunkGenerator.CODEC.codec().parse(ops, saved).getOrThrow().planet(0);
        assertEquals(PlanetMinerals.NONE, legacy.generation().minerals());
        var old = baseline(fresh.planet(0).terrain());
        // Chunks first generated after loading the old saved policy still match the old derived
        // baseline.
        var actual = chunk(legacy, 64, 64);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                for (int y = 80; y < 180; y++)
                    assertSame(
                            old.state(Face.values()[0], 1024 + x, y, 1024 + z),
                            actual.getBlockState(new BlockPos(1024 + x, y, 1024 + z)));
        var oldJson =
                PlanetGeneration.CODEC
                        .encodeStart(JsonOps.INSTANCE, legacy.generation())
                        .getOrThrow()
                        .getAsJsonObject();
        assertFalse(oldJson.has("minerals"));
        // A later surface stage must not repaint stored blocks, including mined holes or a player's
        // special block.
        var pos = new BlockPos(1024, 110, 1024);
        actual.setBlockState(pos, Blocks.CHEST.defaultBlockState(), false);
        fresh.planet(0).buildSurface(null, null, null, actual);
        assertTrue(actual.getBlockState(pos).is(Blocks.CHEST));
    }

    @Test
    void onlyTemperateTypeOptsInAndRockyBAndOtherTypesKeepTheirPolicy() throws Exception {
        var temperate = PlanetTypeTest.type("temperate");
        var json =
                PlanetMinerals.CODEC
                        .encodeStart(JsonOps.INSTANCE, temperate.minerals())
                        .getOrThrow()
                        .getAsJsonObject();
        assertTrue(json.get("shallow_andesite").getAsBoolean());
        assertTrue(
                temperate.minerals().ores().isEmpty() && temperate.minerals().strata().isEmpty(),
                "default seven ores remain");
        for (var name :
                List.of("rocky", "desert", "ice", "lush", "arid", "archipelago", "steppe")) {
            var type = PlanetTypeTest.type(name);
            var encoded =
                    PlanetMinerals.CODEC
                            .encodeStart(JsonOps.INSTANCE, type.minerals())
                            .getOrThrow()
                            .getAsJsonObject();
            assertFalse(encoded.has("shallow_andesite"), name);
            var terrain = type.planet(20261001, Optional.empty()).terrain();
            var old = baseline(terrain);
            for (var face : Face.values())
                for (int u = 100; u < terrain.shape().size(); u += 193) {
                    var a = terrain.column(face, u, 100);
                    var b = old.column(face, u, 100);
                    for (int y = terrain.floor(); y < 320; y++)
                        assertSame(b.state(y), a.state(y), name);
                }
        }
        var entry = new PlanetType.Entry(temperate.planet(20261001, Optional.of(2048)));
        var saved =
                PlanetType.PLANET
                        .encodeStart(PlanetTypeTest.ops(), entry)
                        .getOrThrow()
                        .getAsJsonObject();
        assertFalse(saved.has("planet_type"));
        var loaded = PlanetType.PLANET.parse(PlanetTypeTest.ops(), saved).getOrThrow().get();
        assertEquals(temperate.minerals(), loaded.generation().minerals());
        var b = system().planet(1).generation();
        assertEquals(PlanetMinerals.NONE, b.minerals());
        assertEquals(PlanetPalette.ROCK, b.palette());
        assertEquals(326, b.depth());
        assertEquals(652, system().planet(0).generation().depth());
    }
}
