package dev.starfold.worlds;

import dev.starfold.api.PlanetContent;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;

/** Opening settlement policy only. Saved arrivals never call PlanetContent.home. */
public final class SurvivalSpawn implements PlanetContent.HomeSpawn {
    private static final TagKey<Item> ENABLED =
            TagKey.create(
                    Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("starfold", "survival_guide_enabled"));
    private static final Set<EntityType<?>> FOOD =
            Set.of(
                    EntityType.COW,
                    EntityType.PIG,
                    EntityType.SHEEP,
                    EntityType.CHICKEN,
                    EntityType.RABBIT);
    private static final int STEP = 16, WATER = 64, LOGS = 48;
    private static final int MAX_CANDIDATES = 24, MAX_CHUNKS = 192;
    // Core retries failed arrivals. Retain failures until restart rather than repeating chunk
    // generation.
    private final Map<ServerLevel, Map<Integer, String>> failures = new WeakHashMap<>();

    public static void initialize() {
        PlanetContent.installHomeSpawn(new SurvivalSpawn());
    }

    @Override
    public PlanetContent.Spawn home(ServerLevel level, int face) {
        if (!Items.WRITTEN_BOOK.builtInRegistryHolder().is(ENABLED))
            return PlanetContent.terrainHome(level, face);
        var failed = failures.computeIfAbsent(level, ignored -> new HashMap<>());
        if (failed.containsKey(face)) throw new IllegalStateException(failed.get(face));
        var terrain =
                ((SystemChunkGenerator) level.getChunkSource().getGenerator()).planet(0).terrain();
        var search = new Search(level, terrain, Face.VALUES[face]);
        try {
            int[] site = candidate(terrain.shape().size(), search::accept);
            if (site != null) return new PlanetContent.Spawn(search.spawn, 0);
            throw new IllegalStateException("no suitable candidate in bounded rings");
        } catch (IllegalStateException error) {
            String message =
                    "Stage 1 survival home unavailable: terrain seed="
                            + terrain.seed()
                            + ", size="
                            + terrain.shape().size()
                            + ", face="
                            + face
                            + ", live candidates="
                            + search.attempts
                            + ", FULL chunks="
                            + search.chunks.size()
                            + ": "
                            + error.getMessage()
                            + "; no resources placed. Check home terrain/biome data; restart after correction.";
            failed.put(face, message);
            throw new IllegalStateException(message, error);
        }
    }

    /** Deterministic centre-out square rings, with the entire resource disk inside this face. */
    static int[] candidate(int size, BiPredicate<Integer, Integer> accept) {
        int centre = size / 2;
        int radius = Math.min(size * 3 / 8, centre - WATER - 5);
        for (int r = 0; r <= radius; r += STEP)
            for (int du = -r; du <= r; du += STEP)
                for (int dv = -r; dv <= r; dv += STEP)
                    if (Math.max(Math.abs(du), Math.abs(dv)) == r
                            && accept.test(centre + du, centre + dv))
                        return new int[] {centre + du, centre + dv};
        return null;
    }

    static boolean eligible(Biome biome) {
        return biome.getGenerationSettings().features().stream()
                        .flatMap(step -> step.stream())
                        .flatMap(placed -> placed.value().getFeatures())
                        .anyMatch(feature -> feature.feature() == Feature.TREE)
                && biome.getMobSettings().getMobs(MobCategory.CREATURE).unwrap().stream()
                        .anyMatch(spawn -> FOOD.contains(spawn.type));
    }

    private static final class Search {
        private final ServerLevel level;
        private final PlanetTerrain terrain;
        private final Face face;
        private final Map<String, Boolean> biomes = new HashMap<>();
        private final Map<Long, LevelChunk> chunks = new HashMap<>();
        private int attempts;
        private Point spawn;

        Search(ServerLevel level, PlanetTerrain terrain, Face face) {
            this.level = level;
            this.terrain = terrain;
            this.face = face;
        }

        private boolean planned(int u, int v) {
            int ground = terrain.groundY(face, u, v);
            // The scan must fit the dimension, not the crust. A smaller planet's core
            // is a valid scanned obstacle; the miner stops safely when it reaches it.
            if (ground <= level.getMinBuildHeight() + 513
                    || ground <= terrain.floor() + 4
                    || ground + 3 >= level.getMaxBuildHeight()
                    || terrain.closure(face, u, v)
                    || terrain.submerged(face, u, v, ground)
                    || terrain.temperature(face, u, v) <= 0
                    || terrain.humidity(face, u, v) <= 0
                    || !terrain.surfaceState(face, u, v).is(Blocks.GRASS_BLOCK)) return false;
            return biomes.computeIfAbsent(
                    terrain.biome(face, u, v),
                    id ->
                            eligible(
                                    level.registryAccess()
                                            .registryOrThrow(Registries.BIOME)
                                            .getOptional(ResourceLocation.parse(id))
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "Missing home biome " + id))));
        }

        boolean accept(int u, int v) {
            if (!planned(u, v) || !water(u, v, false)) return false;
            if (attempts >= MAX_CANDIDATES)
                throw new IllegalStateException("live candidate budget exhausted");
            attempts++;
            // Try a small clearing around the lattice point; never use a canopy heightmap as
            // ground.
            for (int r = 0; r <= 4; r++)
                for (int du = -r; du <= r; du++)
                    for (int dv = -r; dv <= r; dv++) {
                        if (Math.max(Math.abs(du), Math.abs(dv)) != r) continue;
                        int a = u + du, b = v + dv;
                        if (!planned(a, b)) continue;
                        BlockPos ground = position(a, terrain.groundY(face, a, b), b);
                        if (!openGrass(ground)) continue;
                        if (!water(a, b, true) || !logs(a, b)) return false;
                        spawn =
                                new Point(
                                        ground.getX() + .5, ground.getY() + 1, ground.getZ() + .5);
                        return true;
                    }
            return false;
        }

        private BlockPos position(int u, int y, int v) {
            return new BlockPos(
                    PlanetStorage.offset(0) + face.ordinal() * 4096 + u,
                    y,
                    PlanetStorage.offsetZ(0) + v);
        }

        private LevelChunk chunk(BlockPos pos) {
            long key = net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            return chunks.computeIfAbsent(
                    key,
                    ignored -> {
                        if (chunks.size() >= MAX_CHUNKS)
                            throw new IllegalStateException("FULL chunk budget exhausted");
                        return level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                    });
        }

        private boolean openGrass(BlockPos ground) {
            var owner = chunk(ground);
            var soil = owner.getBlockState(ground);
            if (!soil.is(Blocks.GRASS_BLOCK)
                    || !soil.getFluidState().isEmpty()
                    || !soil.isFaceSturdy(level, ground, Direction.UP)
                    || !soil.isCollisionShapeFullBlock(level, ground)) return false;
            for (int dy = 1; dy <= 2; dy++) {
                var pos = ground.above(dy);
                var state = owner.getBlockState(pos);
                if (!state.isAir()
                        || !state.getFluidState().isEmpty()
                        || !state.getCollisionShape(level, pos).isEmpty()) return false;
            }
            // Keep immediate contact hazards and fluids away from the player's feet/head.
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dy = 0; dy <= 2; dy++) {
                        var pos = ground.offset(dx, dy, dz);
                        var state = chunk(pos).getBlockState(pos);
                        if (hazard(state) || !state.getFluidState().isEmpty()) return false;
                    }
            return true;
        }

        private boolean water(int u, int v, boolean live) {
            for (int r = 0; r <= WATER; r += 8)
                for (int du = -r; du <= r; du += 8)
                    for (int dv = -r; dv <= r; dv += 8) {
                        if (Math.max(Math.abs(du), Math.abs(dv)) != r
                                || du * du + dv * dv > WATER * WATER) continue;
                        int a = u + du, b = v + dv;
                        var sea = terrain.liquid(face, a, b);
                        if (sea == null
                                || !sea.fluid().getFluidState().is(FluidTags.WATER)
                                || !terrain.submerged(face, a, b, terrain.groundY(face, a, b)))
                            continue;
                        if (!live) return true;
                        var pos = position(a, sea.level() - 1, b);
                        var owner = chunk(pos);
                        var state = owner.getBlockState(pos);
                        if (state.getFluidState().is(FluidTags.WATER)
                                && state.getFluidState().isSource()
                                && owner.getBlockState(pos.above()).isAir()) return true;
                    }
            return false;
        }

        private boolean logs(int u, int v) {
            for (int r = 0; r <= LOGS; r++)
                for (int du = -r; du <= r; du++)
                    for (int dv = -r; dv <= r; dv++) {
                        if (Math.max(Math.abs(du), Math.abs(dv)) != r
                                || du * du + dv * dv > LOGS * LOGS) continue;
                        int a = u + du, b = v + dv;
                        int base = terrain.groundY(face, a, b);
                        var pos = position(a, base + 1, b);
                        var owner = chunk(pos);
                        int top =
                                Math.min(
                                        base + 40,
                                        owner.getHeight(
                                                Heightmap.Types.WORLD_SURFACE,
                                                pos.getX() & 15,
                                                pos.getZ() & 15));
                        int trunk = 0;
                        for (int y = base + 1; y <= top; y++) {
                            var state =
                                    owner.getBlockState(new BlockPos(pos.getX(), y, pos.getZ()));
                            trunk = state.is(BlockTags.LOGS) ? trunk + 1 : 0;
                            if (trunk >= 3) return true;
                        }
                    }
            return false;
        }
    }

    private static boolean hazard(BlockState state) {
        return state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.POINTED_DRIPSTONE)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE);
    }
}
