package dev.starfold.universe;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.GroundTopology;
import dev.starfold.worldgen.PlanetBiomes;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.SystemBiomes;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.concurrent.CompletableFuture;

/** Generates original Minecraft voxels once. Empty storage gaps are not another planet. */
public final class PlanetChunkGenerator extends FlatLevelSource {
    public static final MapCodec<PlanetChunkGenerator> CODEC =
            RecordCodecBuilder.mapCodec(
                    i ->
                            i.group(
                                            Codec.INT
                                                    .validate(
                                                            v ->
                                                                    v == 3
                                                                            ? DataResult.success(v)
                                                                            : DataResult.error(
                                                                                    () ->
                                                                                            "Unsupported planet format; old saves are not supported"))
                                                    .fieldOf("planet_format")
                                                    .forGetter(g -> 3),
                                            FlatLevelGeneratorSettings.CODEC
                                                    .fieldOf("settings")
                                                    .forGetter(PlanetChunkGenerator::settings),
                                            Codec.LONG
                                                    .fieldOf("terrain_seed")
                                                    .forGetter(PlanetChunkGenerator::seed),
                                            Codec.INT
                                                    .validate(v -> PlanetTerrain.supported(v)
                                                            ? DataResult.success(v)
                                                            : DataResult.error(() -> "Unsupported terrain version: " + v
                                                                    + "; expected 11"))
                                                    .fieldOf("terrain_version")
                                                    .forGetter(PlanetChunkGenerator::version),
                                            PlanetBiomes.CODEC
                                                    .codec()
                                                    .optionalFieldOf("planet_biomes")
                                                    .forGetter(
                                                            g ->
                                                                    java.util.Optional.ofNullable(
                                                                            g.planetBiomes)),
                                            PlanetGeneration.CODEC
                                                    .fieldOf("planet_generation")
                                                    .forGetter(PlanetChunkGenerator::generation),
                                            ResourceLocation.CODEC
                                                    .optionalFieldOf("planet_type_id")
                                                    .forGetter(PlanetChunkGenerator::typeId),
                                            Codec.STRING
                                                    .optionalFieldOf("name")
                                                    .forGetter(PlanetChunkGenerator::displayName))
                                    .apply(
                                            i,
                                            (format,
                                                    settings,
                                                    seed,
                                                    version,
                                                    biomes,
                                                    generation,
                                                    type,
                                                    name) -> new PlanetChunkGenerator(
                                                        settings,
                                                        seed,
                                                        version,
                                                        biomes,
                                                        java.util.Optional.of(generation),
                                                        type,
                                                        name)));
    private final java.util.Optional<ResourceLocation> typeId;
    private final java.util.Optional<String> displayName;

    public java.util.Optional<ResourceLocation> typeId() {
        return typeId;
    }

    public java.util.Optional<String> displayName() {
        return displayName;
    }

    public PlanetChunkGenerator identity(
            java.util.Optional<ResourceLocation> type, java.util.Optional<String> name) {
        return new PlanetChunkGenerator(
                settings(),
                seed(),
                version(),
                java.util.Optional.ofNullable(planetBiomes),
                savedGeneration,
                type,
                name);
    }

    private dev.starfold.worldgen.plan.PlanetPlanStore planStore;
    public void initializePlan(dev.starfold.worldgen.plan.PlanetPlanStore store) { planStore=store;terrain.initializePlan(store::plan); }
    public dev.starfold.worldgen.plan.PlanetPlanStore planStore() { return planStore; }
    public boolean planReady() { return planStore==null || planStore.ready(); }
    private final PlanetTerrain terrain;
    private final PlanetBiomes planetBiomes;
    private final java.util.Optional<PlanetGeneration> savedGeneration;

    public PlanetChunkGenerator(FlatLevelGeneratorSettings settings, long seed, int version) {
        this(settings, seed, version, java.util.Optional.empty());
    }

    public PlanetChunkGenerator(
            FlatLevelGeneratorSettings settings,
            long seed,
            int version,
            java.util.Optional<PlanetBiomes> biomes) {
        this(settings, seed, version, biomes, java.util.Optional.empty());
    }

    public PlanetChunkGenerator(
            FlatLevelGeneratorSettings settings,
            long seed,
            int version,
            java.util.Optional<PlanetBiomes> biomes,
            java.util.Optional<PlanetGeneration> generation) {
        this(
                settings,
                seed,
                version,
                biomes,
                generation,
                java.util.Optional.empty(),
                java.util.Optional.empty());
    }

    public PlanetChunkGenerator(
            FlatLevelGeneratorSettings settings,
            long seed,
            int version,
            java.util.Optional<PlanetBiomes> biomes,
            java.util.Optional<PlanetGeneration> generation,
            java.util.Optional<ResourceLocation> typeId,
            java.util.Optional<String> displayName) {
        super(settings);
        this.typeId = java.util.Objects.requireNonNull(typeId);
        this.displayName = java.util.Objects.requireNonNull(displayName);
        displayName.ifPresent(SystemDefinition::validName);
        savedGeneration = generation;
        terrain = new PlanetTerrain(seed,version,generation.orElseGet(()->PlanetGeneration.defaults(version)));
        planetBiomes=biomes.map(palette->palette.on(terrain)).orElse(null);
        terrain.initializeSurface(getBiomeSource(),this);
    }

    @Override
    public net.minecraft.world.level.biome.BiomeSource getBiomeSource() {
        return planetBiomes == null ? super.getBiomeSource() : planetBiomes;
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(
            RandomState random, Blender blender, StructureManager structures, ChunkAccess chunk) {
        if (planetBiomes == null)
            return super.createBiomes(random, blender, structures, chunk);
        chunk.fillBiomesFromNoise(getBiomeSource(), null);
        return CompletableFuture.completedFuture(chunk);
    }

    public long seed() {
        return terrain.seed();
    }

    public int version() {
        return terrain.version();
    }

    public dev.starfold.planet.PlanetIdentity identity() {
        return new dev.starfold.planet.PlanetIdentity(typeId(), generation().recipe().liquid().defaultBlockState().isAir()?"rocky":"temperate", displayName());
    }

    public PlanetTerrain terrain() {
        return terrain;
    }

    public PlanetGeneration generation() {
        return terrain.generation();
    }

    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> GENERATORS =
            DeferredRegister.create(BuiltInRegistries.CHUNK_GENERATOR, "starfold");
    private static final DeferredRegister<
                    MapCodec<? extends net.minecraft.world.level.biome.BiomeSource>>
            BIOMES = DeferredRegister.create(BuiltInRegistries.BIOME_SOURCE, "starfold");

    public static void initialize(IEventBus modBus) {
        PlanetType.initialize(modBus);
        dev.starfold.worldgen.plan.PlannedStructurePiece.initialize(modBus);
        GENERATORS.register("native_system", () -> SystemChunkGenerator.CODEC);
        BIOMES.register("native_system", () -> SystemBiomes.CODEC);
        BIOMES.register("native_plan", () -> PlanetBiomes.CODEC);
        GENERATORS.register(modBus);
        BIOMES.register(modBus);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public int getMinY() {
        return Math.floorDiv(terrain.floor(), 16) * 16;
    }

    @Override
    public int getGenDepth() {
        return PlanetTerrain.MAX_Y - getMinY();
    }

    @Override
    public int getSeaLevel() {
        return terrain.sea() == null ? getMinY() : terrain.sea().level();
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor height) {
        return 130;
    }

    private int ground(int x, int z) {
        var pos = new BlockPos(x, 128, z);
        if (!GroundCoordinates.owned(
                terrain.shape(), new dev.starfold.geometry.BentStripGeometry.Point(x, 128, z)))
            return Integer.MIN_VALUE;
        var cell = GroundTopology.cell(pos);
        return terrain.groundY(cell.face(), cell.u(), cell.v());
    }

    @Override
    public int getBaseHeight(
            int x, int z, Heightmap.Types type, LevelHeightAccessor height, RandomState random) {
        int ground = ground(x, z);
        if (ground == Integer.MIN_VALUE) return height.getMinBuildHeight();
        var at = GroundTopology.cell(new BlockPos(x, 128, z));
        if (terrain.closure(at.face(), at.u(), at.v()))
            return Math.clamp(
                    terrain.closureTop() + 1,
                    height.getMinBuildHeight(),
                    height.getMaxBuildHeight());
        var states = new dev.starfold.worldgen.surface.SurfaceFunction(terrain).blocks(at.face(), at.u(), at.v());
        for (int y = Math.min(height.getMaxBuildHeight(), PlanetTerrain.MAX_Y) - 1;
                y >= Math.max(height.getMinBuildHeight(), terrain.floor()); y--)
            if (type.isOpaque().test(states[y - terrain.floor()])) return y + 1;
        return height.getMinBuildHeight();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        int ground = ground(x, z);
        var states = new net.minecraft.world.level.block.state.BlockState[height.getHeight()];
        var cell =
                ground == Integer.MIN_VALUE ? null : GroundTopology.cell(new BlockPos(x, 128, z));
        var generated = cell == null ? null
                : new dev.starfold.worldgen.surface.SurfaceFunction(terrain).blocks(cell.face(), cell.u(), cell.v());
        for (int i = 0; i < states.length; i++) {
            int y = height.getMinBuildHeight() + i;
            states[i] = generated != null && y >= terrain.floor() && y < PlanetTerrain.MAX_Y
                    ? generated[y - terrain.floor()]
                    : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        return new NoiseColumn(height.getMinBuildHeight(), states);
    }
}
