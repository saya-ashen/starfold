package dev.starfold.worldgen;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.GroundCoordinates;
import dev.starfold.ground.GroundTopology;
import dev.starfold.planet.PlanetType;
import dev.starfold.planet.SystemDefinition;
import dev.starfold.render.GroundSurfaceIndex;

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
                                            FlatLevelGeneratorSettings.CODEC
                                                    .fieldOf("settings")
                                                    .forGetter(PlanetChunkGenerator::settings),
                                            Codec.LONG
                                                    .fieldOf("terrain_seed")
                                                    .forGetter(PlanetChunkGenerator::seed),
                                            Codec.INT
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
                                                    .optionalFieldOf("planet_generation")
                                                    .forGetter(g -> g.savedGeneration),
                                            Codec.BOOL
                                                    .optionalFieldOf("multi_planet", false)
                                                    .forGetter(PlanetChunkGenerator::multiple),
                                            ResourceLocation.CODEC
                                                    .optionalFieldOf("planet_type_id")
                                                    .forGetter(PlanetChunkGenerator::typeId),
                                            Codec.STRING
                                                    .optionalFieldOf("planet_name")
                                                    .forGetter(PlanetChunkGenerator::displayName),
                                            // Authored per-planet name alias; keep planet_name in
                                            // saved generators for compatibility.
                                            Codec.STRING
                                                    .optionalFieldOf("name")
                                                    .forGetter(
                                                            g ->
                                                                    java.util.Optional
                                                                            .<String>empty()))
                                    .apply(
                                            i,
                                            (settings,
                                                    seed,
                                                    version,
                                                    biomes,
                                                    generation,
                                                    multiple,
                                                    type,
                                                    legacy,
                                                    name) -> {
                                                if (name.isPresent()
                                                        && legacy.isPresent()
                                                        && !name.equals(legacy))
                                                    throw new IllegalArgumentException(
                                                            "Conflicting name and planet_name");
                                                return new PlanetChunkGenerator(
                                                        settings,
                                                        seed,
                                                        version,
                                                        biomes,
                                                        generation,
                                                        multiple,
                                                        type,
                                                        name.isPresent() ? name : legacy);
                                            }));
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
                multiple,
                type,
                name);
    }

    private final PlanetTerrain terrain;
    private final PlanetBiomes planetBiomes;
    private final java.util.Optional<PlanetGeneration> savedGeneration;
    private final boolean multiple;

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
        this(settings, seed, version, biomes, generation, false);
    }

    public PlanetChunkGenerator(
            FlatLevelGeneratorSettings settings,
            long seed,
            int version,
            java.util.Optional<PlanetBiomes> biomes,
            java.util.Optional<PlanetGeneration> generation,
            boolean multiple) {
        this(
                settings,
                seed,
                version,
                biomes,
                generation,
                multiple,
                java.util.Optional.empty(),
                java.util.Optional.empty());
    }

    public PlanetChunkGenerator(
            FlatLevelGeneratorSettings settings,
            long seed,
            int version,
            java.util.Optional<PlanetBiomes> biomes,
            java.util.Optional<PlanetGeneration> generation,
            boolean multiple,
            java.util.Optional<ResourceLocation> typeId,
            java.util.Optional<String> displayName) {
        super(settings);
        this.typeId = java.util.Objects.requireNonNull(typeId);
        this.displayName = java.util.Objects.requireNonNull(displayName);
        displayName.ifPresent(SystemDefinition::validName);
        if (multiple && version != 4 && version != 5)
            throw new IllegalArgumentException("Multi-planet generator version");
        this.multiple = multiple;
        if (version == PlanetTerrain.ROCK_VERSION && generation.isEmpty())
            throw new IllegalArgumentException("Rocky terrain requires saved generation policy");
        savedGeneration = generation;
        terrain =
                new PlanetTerrain(
                        seed,
                        version,
                        generation.orElseGet(() -> PlanetGeneration.defaults(version)));
        planetBiomes =
                biomes.map(
                                palette ->
                                        version == PlanetTerrain.ECOLOGY_VERSION
                                                        && palette.seed() == seed
                                                ? palette.on(terrain)
                                                : palette)
                        .orElse(null);
        if (version == PlanetTerrain.ECOLOGY_VERSION
                ? (planetBiomes == null || planetBiomes.seed() != seed)
                : planetBiomes != null)
            throw new IllegalArgumentException("Native biome palette/version/seed mismatch");
    }

    @Override
    public net.minecraft.world.level.biome.BiomeSource getBiomeSource() {
        return planetBiomes == null ? super.getBiomeSource() : planetBiomes;
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(
            RandomState random, Blender blender, StructureManager structures, ChunkAccess chunk) {
        if (planetBiomes == null) return super.createBiomes(random, blender, structures, chunk);
        chunk.fillBiomesFromNoise(planetBiomes, null);
        return CompletableFuture.completedFuture(chunk);
    }

    public long seed() {
        return terrain.seed();
    }

    public int version() {
        return terrain.version();
    }

    public PlanetTerrain terrain() {
        return terrain;
    }

    public PlanetGeneration generation() {
        return terrain.generation();
    }

    public boolean multiple() {
        return multiple;
    }

    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> GENERATORS =
            DeferredRegister.create(BuiltInRegistries.CHUNK_GENERATOR, "starfold");
    private static final DeferredRegister<
                    MapCodec<? extends net.minecraft.world.level.biome.BiomeSource>>
            BIOMES = DeferredRegister.create(BuiltInRegistries.BIOME_SOURCE, "starfold");

    public static void initialize(IEventBus modBus) {
        PlanetType.initialize(modBus);
        GENERATORS.register("native_system", () -> SystemChunkGenerator.CODEC);
        BIOMES.register("native_system", () -> SystemBiomes.CODEC);
        GENERATORS.register("native_planet", () -> CODEC);
        BIOMES.register("native_ecology", () -> PlanetBiomes.CODEC);
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

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
        var surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        var floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        var pos = new BlockPos.MutableBlockPos();
        var start = chunk.getPos();
        if (GroundSurfaceIndex.key(start.x, start.z) >= 0) {
            var face = CubeSurfaceTopology.Face.values()[Math.floorDiv(start.x, 256)];
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++) {
                    int wx = start.getMinBlockX() + x,
                            wz = start.getMinBlockZ() + z,
                            ground = terrain.groundY(face, wx - face.ordinal() * 4096, wz);
                    var column =
                            version() >= PlanetTerrain.MINING_VERSION
                                    ? terrain.column(face, wx - face.ordinal() * 4096, wz)
                                    : null;
                    for (int y = terrain.floor();
                            y
                                    <= Math.max(
                                            terrain.surfaceY(face, wx - face.ordinal() * 4096, wz),
                                            version() == PlanetTerrain.ECOLOGY_VERSION
                                                    ? ground + 10
                                                    : ground);
                            y++) {
                        var state =
                                column == null
                                        ? terrain.state(face, wx - face.ordinal() * 4096, y, wz)
                                        : column.state(y);
                        chunk.setBlockState(pos.set(wx, y, wz), state, false);
                        surface.update(x, y, z, state);
                        floor.update(x, y, z, state);
                        if (!state.getFluidState().isEmpty()
                                && (y == 127 || x == 0 || x == 15 || z == 0 || z == 15))
                            chunk.markPosForPostprocessing(pos);
                    }
                }
        }
        return CompletableFuture.completedFuture(chunk);
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
        if (version() == PlanetTerrain.ECOLOGY_VERSION) {
            var cell = GroundTopology.cell(new BlockPos(x, 128, z));
            var col = terrain.column(cell.face(), cell.u(), cell.v());
            for (int y = Math.min(height.getMaxBuildHeight(), 320) - 1;
                    y >= Math.max(height.getMinBuildHeight(), terrain.floor());
                    y--) if (type.isOpaque().test(col.state(y))) return y + 1;
            return height.getMinBuildHeight();
        }
        return Math.clamp(
                (terrain.sea() != null && type.isOpaque().test(terrain.sea().fluid())
                                ? terrain.surfaceTop(ground)
                                : ground)
                        + 1,
                height.getMinBuildHeight(),
                height.getMaxBuildHeight());
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        int ground = ground(x, z);
        var states = new net.minecraft.world.level.block.state.BlockState[height.getHeight()];
        var cell =
                ground == Integer.MIN_VALUE ? null : GroundTopology.cell(new BlockPos(x, 128, z));
        var column =
                cell != null && version() >= PlanetTerrain.MINING_VERSION
                        ? terrain.column(cell.face(), cell.u(), cell.v())
                        : null;
        for (int i = 0; i < states.length; i++)
            states[i] =
                    ground == Integer.MIN_VALUE
                            ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                            : column == null
                                    ? terrain.state(
                                            cell.face(),
                                            cell.u(),
                                            height.getMinBuildHeight() + i,
                                            cell.v())
                                    : column.state(height.getMinBuildHeight() + i);
        return new NoiseColumn(height.getMinBuildHeight(), states);
    }
}
