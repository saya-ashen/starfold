package dev.starfold.universe;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.planet.GroundTopology;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.worldgen.PlanetDecoration;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.SystemBiomes;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.concurrent.CompletableFuture;

/** One level's generator, with independently saved planet policies in storage order. */
public final class SystemChunkGenerator extends FlatLevelSource implements PlanetSystem.Saved {
    private static final MapCodec<SystemChunkGenerator> SAVED_CODEC =
            RecordCodecBuilder.mapCodec(
                    i ->
                            i.group(
                                            Codec.STRING
                                                    .fieldOf("storage_layout")
                                                    .forGetter(g -> g.layout),
                                            PlanetType.PLANET
                                                    .listOf()
                                                    .fieldOf("planets")
                                                    .forGetter(g -> g.entries),
                                            Codec.INT
                                                    .listOf()
                                                    .optionalFieldOf("stars")
                                                    .forGetter(
                                                            g ->
                                                                    g.celestial != null
                                                                                    || g.groups
                                                                                                    .length
                                                                                            == 1
                                                                            ? java.util.Optional
                                                                                    .empty()
                                                                            : java.util.Optional.of(
                                                                                    java
                                                                                            .util
                                                                                            .Arrays
                                                                                            .stream(
                                                                                                    g.groups)
                                                                                            .boxed()
                                                                                            .toList())),
                                            UniverseLayout.HOLDER
                                                    .optionalFieldOf("universe")
                                                    .forGetter(g -> g.universe),
                                            SystemDefinition.Sky.CODEC
                                                    .optionalFieldOf("celestial")
                                                    .forGetter(
                                                            g ->
                                                                    java.util.Optional.ofNullable(
                                                                            g.celestial)),
                                            StarterShipOption.CODEC
                                                    .optionalFieldOf("starter_ship")
                                                    .forGetter(SystemChunkGenerator::starterShip))
                                    .apply(
                                            i,
                                            (layout, planets, stars, universe, celestial, ship) ->
                                                    withStarter(
                                                            saved(
                                                                    layout, planets, stars,
                                                                    universe, celestial),
                                                            ship)));
    private static final MapCodec<SystemChunkGenerator> AUTHORED_CODEC =
            RecordCodecBuilder.mapCodec(
                    i ->
                            i.group(
                                            Codec.STRING
                                                    .fieldOf("storage_layout")
                                                    .forGetter(g -> g.layout),
                                            SystemDefinition.HOLDER
                                                    .fieldOf("system")
                                                    .forGetter(g -> g.pending),
                                            net.minecraft.resources.RegistryOps.retrieveElement(
                                                    net.minecraft.world.level.biome.Biomes.PLAINS),
                                            StarterShipOption.CODEC
                                                    .optionalFieldOf("starter_ship")
                                                    .forGetter(SystemChunkGenerator::starterShip))
                                    .apply(
                                            i,
                                            (layout, system, plains, ship) ->
                                                    withStarter(
                                                            new SystemChunkGenerator(
                                                                    layout, system, plains),
                                                            ship)));
    public static final MapCodec<SystemChunkGenerator> CODEC =
            new MapCodec<>() {
                @Override
                public <T> java.util.stream.Stream<T> keys(DynamicOps<T> ops) {
                    return java.util.stream.Stream.concat(
                                    SAVED_CODEC.keys(ops), AUTHORED_CODEC.keys(ops))
                            .distinct();
                }

                @Override
                public <T> DataResult<SystemChunkGenerator> decode(
                        DynamicOps<T> ops, MapLike<T> input) {
                    if (input.get("system") != null) {
                        for (String key :
                                java.util.List.of(
                                        "planets", "a", "b", "stars", "universe", "celestial"))
                            if (input.get(key) != null)
                                return DataResult.error(
                                        () -> "system cannot be combined with " + key);
                        return AUTHORED_CODEC.decode(ops, input);
                    }
                    if (input.get("a") != null || input.get("b") != null)
                        return DataResult.error(() -> "A system needs a planets list");
                    return SAVED_CODEC.decode(ops, input);
                }

                @Override
                public <T> RecordBuilder<T> encode(
                        SystemChunkGenerator g, DynamicOps<T> ops, RecordBuilder<T> prefix) {
                    return (g.pending == null ? SAVED_CODEC : AUTHORED_CODEC)
                            .encode(g, ops, prefix);
                }
            };
    private java.util.Optional<StarterShipOption> starterShip = java.util.Optional.empty();

    public java.util.Optional<StarterShipOption> starterShip() {
        return starterShip;
    }

    private static SystemChunkGenerator withStarter(
            SystemChunkGenerator g, java.util.Optional<StarterShipOption> ship) {
        g.starterShip = ship;
        return g;
    }

    private net.minecraft.core.Holder<SystemDefinition> pending;
    private SystemDefinition.Sky celestial;

    private SystemChunkGenerator(
            String layout,
            net.minecraft.core.Holder<SystemDefinition> system,
            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> plains) {
        super(
                new net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings(
                        java.util.Optional.of(net.minecraft.core.HolderSet.direct()),
                        plains,
                        java.util.List.of()));
        if (!PlanetStorage.LAYOUT.equals(layout))
            throw new IllegalArgumentException(
                    "An authored system requires storage_layout six_face_square_v2");
        this.layout = layout;
        this.pending = system;
        entries = java.util.List.of();
        groups = new int[0];
    }

    /** World presets load before holders are bound and before a world seed exists. Never guess that seed for a preview. */
    public synchronized void materialize(long worldSeed) {
        if (pending == null) return;
        var made = pending.value().materialize(worldSeed);
        entries = made.planets().stream().map(PlanetType.Entry::new).toList();
        celestial = made.sky();
        groups = celestial.groups().stream().mapToInt(Integer::intValue).toArray();
        universe = made.beyond();
        pending = null;
        planets();
    }

    @Override
    public void validate() {
        super.validate();
        if (pending != null) pending.value().validate();
    }

    @Override
    public boolean startsWithShip() {
        return starterShip.isPresent();
    }

    @Override
    public boolean beyond(net.minecraft.server.level.ServerLevel level) {
        return Universe.of(level) != null;
    }

    @Override
    public boolean authored() {
        return celestial != null || pending != null;
    }

    @Override
    public GroundEphemeris.Model model(net.minecraft.server.level.ServerLevel level) {
        var home = homeModel();
        var universe = Universe.of(level);
        return universe == null ? home : universe.model(home);
    }

    public GroundEphemeris.Model homeModel() {
        if (pending != null)
            throw new IllegalStateException(
                    "Authored system needs its world seed before its model");
        return celestial == null ? PlanetSystem.model(groups) : celestial.model();
    }

    public java.util.List<String> names() {
        if (celestial == null) return java.util.List.of();
        var names = new java.util.ArrayList<>(celestial.names());
        while (names.size() < count()) names.add(PlanetSystem.defaultName(names.size()));
        return java.util.List.copyOf(names);
    }

    /** The layout of the universe beyond the planets written here, if the system has one; its suns and planets are
     *  made of the world's seed when the world is first loaded, kept by Universe and told to this generator. */
    private java.util.Optional<net.minecraft.core.Holder<UniverseLayout>> universe =
            java.util.Optional.empty();

    private volatile java.util.List<PlanetChunkGenerator> beyond = java.util.List.of();

    public java.util.Optional<net.minecraft.core.Holder<UniverseLayout>> universe() {
        return universe;
    }

    /** The planets of the universe beyond those written here: they come after them in storage order. */
    public synchronized void bind(java.util.List<PlanetChunkGenerator> generated) {
        if (authored() && entries.size() + generated.size() > Universe.CAPACITY)
            throw new IllegalArgumentException("Authored system exceeds 1600 synchronised planets");
        if (!generated.isEmpty() && !PlanetStorage.LAYOUT.equals(layout))
            throw new IllegalArgumentException("A universe is laid out in the square");
        if (entries.size() + generated.size() > PlanetStorage.MAX_PLANETS)
            throw new IllegalArgumentException(
                    "System planet count " + (entries.size() + generated.size()));
        beyond = java.util.List.copyOf(generated);
        planets = null;
    }

    /** A planet named by its type becomes one the first time it is asked for: see PlanetType.Entry. */
    private java.util.List<PlanetType.Entry> entries;

    private volatile java.util.List<PlanetChunkGenerator> planets;

    /** How many planets each sun has, in storage order; one sun unless saved otherwise ("stars"). */
    private int[] groups;

    /** The saved storage layout. */
    private final String layout;

    private volatile SystemBiomes biomes;

    private static SystemChunkGenerator saved(
            String layout,
            java.util.List<PlanetType.Entry> planets,
            java.util.Optional<java.util.List<Integer>> stars,
            java.util.Optional<net.minecraft.core.Holder<UniverseLayout>> universe,
            java.util.Optional<SystemDefinition.Sky> celestial) {
        if (celestial.isPresent() && stars.isPresent())
            throw new IllegalArgumentException("Saved celestial cannot be combined with stars");
        if (celestial.isPresent()) {
            var sky = celestial.get();
            if (planets.size() != sky.names().size()
                    || planets.stream().anyMatch(p -> !p.written()))
                throw new IllegalArgumentException(
                        "Saved celestial requires matching full planet generators");
            var made =
                    new SystemChunkGenerator(
                            layout,
                            planets,
                            sky.groups().stream().mapToInt(Integer::intValue).toArray(),
                            sky);
            made.universe = universe;
            return made;
        }
        var made =
                new SystemChunkGenerator(
                        layout,
                        planets,
                        stars.map(s -> s.stream().mapToInt(Integer::intValue).toArray())
                                .orElse(new int[] {planets.size()}),
                        null);
        made.universe = universe;
        return made;
    }

    public SystemChunkGenerator(String layout, PlanetChunkGenerator a, PlanetChunkGenerator b) {
        this(
                layout,
                java.util.List.of(new PlanetType.Entry(a), new PlanetType.Entry(b)),
                new int[] {2},
                null);
        if (a.version() != 4 || b.version() != 5)
            throw new IllegalArgumentException("System requires ecology v4 and rocky v5 policies");
    }

    public SystemChunkGenerator(String layout, java.util.List<PlanetChunkGenerator> planets) {
        this(layout, planets, new int[] {planets.size()});
    }

    public SystemChunkGenerator(
            String layout, java.util.List<PlanetChunkGenerator> planets, int[] groups) {
        this(layout, planets.stream().map(PlanetType.Entry::new).toList(), groups, null);
    }

    private SystemChunkGenerator(
            String layout,
            java.util.List<PlanetType.Entry> entries,
            int[] groups,
            SystemDefinition.Sky celestial) {
        super(first(entries).settings());
        this.celestial = celestial;
        if (celestial != null && !PlanetStorage.LAYOUT.equals(layout))
            throw new IllegalArgumentException(
                    "An authored system requires storage_layout six_face_square_v2");
        if (!PlanetStorage.LAYOUT.equals(layout))
            throw new IllegalArgumentException("Unknown native system storage layout");
        this.layout = layout;
        if (entries.size() < 1 || entries.size() > PlanetStorage.MAX_PLANETS)
            throw new IllegalArgumentException("System planet count " + entries.size());
        if (java.util.Arrays.stream(groups).sum() != entries.size())
            throw new IllegalArgumentException(
                    "Suns share out "
                            + java.util.Arrays.stream(groups).sum()
                            + " of "
                            + entries.size()
                            + " planets");
        if (celestial == null) PlanetSystem.model(groups);
        this.groups = groups.clone();
        this.entries = java.util.List.copyOf(entries);
        // Planets written out in full are checked now, as they always were; the rest when they are
        // made.
        if (entries.stream().allMatch(PlanetType.Entry::written)) planets();
    }

    /** The level's own settings are its first planet's, which is therefore written out in full. */
    private static PlanetChunkGenerator first(java.util.List<PlanetType.Entry> entries) {
        if (entries.isEmpty() || !entries.get(0).written())
            throw new IllegalArgumentException(
                    "The first planet of a system is written out in full");
        return entries.get(0).get();
    }

    private java.util.List<PlanetChunkGenerator> planets() {
        if (pending != null)
            throw new IllegalStateException(
                    "Authored system needs its world seed before generating terrain");
        var made = planets;
        if (made != null) return made;
        synchronized (this) {
            if (planets == null) {
                var list =
                        java.util.stream.Stream.concat(
                                        entries.stream().map(PlanetType.Entry::get),
                                        beyond.stream())
                                .toList();
                for (var planet : list)
                    if (planet.version() != 4 && planet.version() != 5)
                        throw new IllegalArgumentException(
                                "System requires ecology v4 and rocky v5 policies");
                biomes =
                        new SystemBiomes(
                                list.stream().map(PlanetChunkGenerator::getBiomeSource).toList());
                planets = list;
            }
            return planets;
        }
    }

    @Override
    public int count() {
        return entries.size() + beyond.size();
    }

    public int[] groups() {
        return groups.clone();
    }

    @Override
    public PlanetShape shape(int planet) {
        return planet(planet).terrain().shape();
    }

    public PlanetChunkGenerator planet(int planet) {
        if (pending != null)
            throw new IllegalStateException(
                    "Authored system needs its world seed before generating terrain");
        if (planet < 0 || planet >= count()) throw new IllegalArgumentException("Planet");
        return planets().get(planet);
    }

    /** The planet whose face this chunk belongs to, by that planet's own saved size, or -1 in gaps and halos. */
    private int owner(int chunkX, int chunkZ) {
        var all = planets();
        int id =
                PlanetStorage.index(
                        Math.floorDiv(chunkX, PlanetStorage.PLANET_STRIDE / 16),
                        Math.floorDiv(chunkZ, PlanetStorage.ROW_STRIDE / 16));
        if (id < 0 || id >= all.size()) return -1;
        return dev.starfold.storage.ChunkKeys.local(
                                chunkX - originX(id) / 16,
                                chunkZ - originZ(id) / 16,
                                all.get(id).terrain().shape().chunks())
                        >= 0
                ? id
                : -1;
    }

    /** Where a planet's storage begins: by its place in the square alone, whatever system is running. */
    private static int originX(int id) {
        return PlanetStorage.column(id) * PlanetStorage.PLANET_STRIDE;
    }

    private static int originZ(int id) {
        return PlanetStorage.row(id) * PlanetStorage.ROW_STRIDE;
    }

    /** Nothing spawns on a planet that has no creatures, nor in the gaps between planets. */
    @Override
    public net.minecraft.util.random.WeightedRandomList<
                    net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData>
            getMobsAt(
                    net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome,
                    StructureManager structures,
                    net.minecraft.world.entity.MobCategory category,
                    BlockPos pos) {
        return lives(pos)
                ? super.getMobsAt(biome, structures, category, pos)
                : net.minecraft.util.random.WeightedRandomList.create();
    }

    /** Whether a place is on a planet that has creatures. */
    public boolean lives(BlockPos pos) {
        int id = owner(pos.getX() >> 4, pos.getZ() >> 4);
        return id >= 0 && planet(id).generation().creatures();
    }

    /** The animals of a chunk's biome are put on it as it is made, as on any world: they do not come later. */
    @Override
    public void spawnOriginalMobs(net.minecraft.server.level.WorldGenRegion region) {
        var chunk = region.getCenter();
        int id = owner(chunk.x, chunk.z);
        if (id < 0 || !planet(id).generation().creatures()) return;
        var biome = region.getBiome(chunk.getWorldPosition().atY(region.getMaxBuildHeight() - 1));
        var random =
                new net.minecraft.world.level.levelgen.WorldgenRandom(
                        new net.minecraft.world.level.levelgen.LegacyRandomSource(
                                net.minecraft.world.level.levelgen.RandomSupport
                                        .generateUniqueSeed()));
        random.setDecorationSeed(region.getSeed(), chunk.getMinBlockX(), chunk.getMinBlockZ());
        net.minecraft.world.level.NaturalSpawner.spawnMobsForChunkGeneration(
                region, biome, chunk, random);
    }

    /** The structure sets that may stand on planets: those of this tag. A data pack adds its own set to the tag, and
     *  the set's structures say in which biomes they stand, as on any world. */
    public static final net.minecraft.tags.TagKey<
                    net.minecraft.world.level.levelgen.structure.StructureSet>
            STRUCTURES =
                    net.minecraft.tags.TagKey.create(
                            net.minecraft.core.registries.Registries.STRUCTURE_SET,
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                    "starfold", "planets"));

    /** A structure begins no nearer than this many chunks to the edge of a face: nothing built reaches further from
     *  where it begins, so none is cut by a seam. */
    public static final int MARGIN = 8;

    public static boolean interior(int chunks, int u, int v) {
        return u >= MARGIN && v >= MARGIN && u < chunks - MARGIN && v < chunks - MARGIN;
    }

    @Override
    public ChunkGeneratorStructureState createState(
            net.minecraft.core.HolderLookup<
                            net.minecraft.world.level.levelgen.structure.StructureSet>
                    sets,
            RandomState random,
            long seed) {
        materialize(seed);
        return ChunkGeneratorStructureState.createForFlat(
                random,
                seed,
                getBiomeSource(),
                sets.listElements()
                        .filter(set -> set.is(STRUCTURES))
                        .map(
                                set ->
                                        (net.minecraft.core.Holder<
                                                        net.minecraft.world.level.levelgen.structure
                                                                .StructureSet>)
                                                set));
    }

    /** As any generator begins its structures, but only on a planet that has them, away from the edges of its faces,
     *  and by the biomes of the planet the chunk is on. */
    @Override
    public void createStructures(
            net.minecraft.core.RegistryAccess registries,
            ChunkGeneratorStructureState state,
            StructureManager manager,
            ChunkAccess chunk,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
                    templates) {
        var pos = chunk.getPos();
        int id = owner(pos.x, pos.z);
        if (id < 0 || !planet(id).generation().structures()) return;
        if (!interior(
                planet(id).terrain().shape().chunks(),
                Math.floorMod(pos.x - originX(id) / 16, 256),
                pos.z - originZ(id) / 16)) return;
        var section = net.minecraft.core.SectionPos.bottomOf(chunk);
        state.possibleStructureSets()
                .forEach(
                        set -> {
                            var entries = set.value().structures();
                            for (var entry : entries) {
                                var begun =
                                        manager.getStartForStructure(
                                                section, entry.structure().value(), chunk);
                                if (begun != null && begun.isValid()) return;
                            }
                            if (!set.value().placement().isStructureChunk(state, pos.x, pos.z))
                                return;
                            var left = new java.util.ArrayList<>(entries);
                            var random = new WorldgenRandom(new LegacyRandomSource(0L));
                            random.setLargeFeatureSeed(state.getLevelSeed(), pos.x, pos.z);
                            int weight = 0;
                            for (var entry : left) weight += entry.weight();
                            while (!left.isEmpty()) {
                                int pick = left.size() == 1 ? 0 : random.nextInt(weight), at = 0;
                                if (left.size() > 1)
                                    for (var entry : left) {
                                        pick -= entry.weight();
                                        if (pick < 0) break;
                                        at++;
                                    }
                                var chosen = left.get(at);
                                var structure = chosen.structure().value();
                                var before =
                                        manager.getStartForStructure(section, structure, chunk);
                                var start =
                                        structure.generate(
                                                registries,
                                                this,
                                                getBiomeSource(),
                                                state.randomState(),
                                                templates,
                                                state.getLevelSeed(),
                                                pos,
                                                before == null ? 0 : before.getReferences(),
                                                chunk,
                                                structure.biomes()::contains);
                                if (start.isValid()) {
                                    manager.setStartForStructure(section, structure, start, chunk);
                                    return;
                                }
                                left.remove(at);
                                weight -= chosen.weight();
                            }
                        });
    }

    private volatile PlanetDecoration decoration;

    /** A planet that has them gets the features of its biomes and the pieces of its structures as its chunks are made. */
    @Override
    public void applyBiomeDecoration(
            WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
        var start = chunk.getPos();
        int id = owner(start.x, start.z);
        if (id < 0) return;
        var generation = planet(id).generation();
        if (!generation.features() && !generation.structures()) return;
        var all = decoration;
        if (all == null) {
            synchronized (this) {
                if (decoration == null) decoration = new PlanetDecoration(getBiomeSource());
                all = decoration;
            }
        }
        all.apply(
                this,
                level,
                chunk,
                structures,
                generation.features(),
                generation.structures(),
                planet(id).version() == PlanetTerrain.ECOLOGY_VERSION);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public BiomeSource getBiomeSource() {
        if (pending != null) return super.getBiomeSource();
        planets();
        return biomes;
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(
            RandomState random, Blender blender, StructureManager structures, ChunkAccess chunk) {
        chunk.fillBiomesFromNoise(getBiomeSource(), null);
        return CompletableFuture.completedFuture(chunk);
    }

    /** One Level holds every planet, so its range reaches the deepest planet's floor; a shallower planet leaves
     *  the layers below its own floor empty. */
    @Override
    public int getMinY() {
        return Math.floorDiv(
                        planets().stream().mapToInt(p -> p.terrain().floor()).min().getAsInt(), 16)
                * 16;
    }

    @Override
    public int getGenDepth() {
        return PlanetTerrain.MAX_Y - getMinY();
    }

    /** A Level has one sea-level hint; B's real columns/heights remain dry and are not clamped. */
    @Override
    public int getSeaLevel() {
        return planets().get(0).getSeaLevel();
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor height) {
        return planets().get(0).getSpawnHeight(height);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
        var start = chunk.getPos();
        int id = owner(start.x, start.z);
        if (id < 0) return CompletableFuture.completedFuture(chunk);
        long began = System.nanoTime();
        try {
            return fill(chunk, start, id);
        } finally {
            GENERATED.increment();
            NANOS.add(System.nanoTime() - began);
        }
    }

    /** Chunks of planets made since the server began, and the time that took: read by /starfold perf. */
    public static final java.util.concurrent.atomic.LongAdder
            GENERATED = new java.util.concurrent.atomic.LongAdder(),
            NANOS = new java.util.concurrent.atomic.LongAdder();

    private CompletableFuture<ChunkAccess> fill(
            ChunkAccess chunk, net.minecraft.world.level.ChunkPos start, int id) {
        var terrain = planet(id).terrain();
        var surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        var floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        var pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                int wx = start.getMinBlockX() + x, wz = start.getMinBlockZ() + z;
                var cell =
                        GroundTopology.cell(new BlockPos(wx - originX(id), 128, wz - originZ(id)));
                var column = terrain.column(cell.face(), cell.u(), cell.v());
                int ceiling =
                        Math.max(
                                terrain.surfaceY(cell.face(), cell.u(), cell.v()),
                                column.ground() + (terrain.version() == 4 ? 10 : 0));
                // Written straight into the sections, from the top: the generation heightmaps are
                // kept here, so the chunk's
                // own per-block bookkeeping would only repeat them for every layer of a deep
                // column.
                for (int y = Math.min(ceiling, chunk.getMaxBuildHeight() - 1),
                                low = Math.max(terrain.floor(), chunk.getMinBuildHeight());
                        y >= low;
                        y--) {
                    var state = column.state(y);
                    if (state.isAir()) continue;
                    chunk.getSection(chunk.getSectionIndex(y))
                            .setBlockState(x, y & 15, z, state, false);
                    surface.update(x, y, z, state);
                    floor.update(x, y, z, state);
                    if (!state.getFluidState().isEmpty()
                            && (y == 127 || x == 0 || x == 15 || z == 0 || z == 15))
                        chunk.markPosForPostprocessing(pos.set(wx, y, wz));
                }
            }
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getBaseHeight(
            int x, int z, Heightmap.Types type, LevelHeightAccessor height, RandomState random) {
        int id = owner(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        if (id < 0) return height.getMinBuildHeight();
        return planet(id).getBaseHeight(x - originX(id), z - originZ(id), type, height, random);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        int id = owner(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        if (id >= 0) {
            return planet(id).getBaseColumn(x - originX(id), z - originZ(id), height, random);
        }
        var air = new BlockState[height.getHeight()];
        java.util.Arrays.fill(air, Blocks.AIR.defaultBlockState());
        return new NoiseColumn(height.getMinBuildHeight(), air);
    }
}
