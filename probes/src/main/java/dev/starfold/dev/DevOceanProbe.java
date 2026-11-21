package dev.starfold.dev;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import dev.starfold.geometry.CubeSurfaceTopology.Cell;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Observational ocean receipts. FULL requests can generate terrain; no gameplay writes or ticks. */
public final class DevOceanProbe {
    private static final Logger LOG = LoggerFactory.getLogger("starfold-probe");
    private static final int MAX_CHUNKS = 192;
    private static final String[] ECOLOGY = {
        "kelp", "kelp_plant", "seagrass", "tall_seagrass", "sea_pickle",
        "tube_coral", "brain_coral", "bubble_coral", "fire_coral", "horn_coral"
    };

    private record PendingSpawn(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.entity.MobSpawnType reason,
            long tick) {}

    private static final Map<java.util.UUID, PendingSpawn> pendingSpawns = new LinkedHashMap<>();
    private static final Map<String, Integer> confirmedSpawns = new TreeMap<>();
    private static boolean watchingSpawns;

    public static void initialize() {
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(
                (net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent event) -> {
                    if (!watchingSpawns || event.isSpawnCancelled()) return;
                    var level = event.getLevel().getLevel();
                    var entity = event.getEntity();
                    if (!(entity instanceof net.minecraft.world.entity.animal.WaterAnimal)
                            && !(entity instanceof net.minecraft.world.entity.animal.Turtle))
                        return;
                    if (!testLevel(level)) return;
                    if (pendingSpawns.size() >= 128)
                        pendingSpawns.remove(pendingSpawns.keySet().iterator().next());
                    pendingSpawns.put(
                            entity.getUUID(),
                            new PendingSpawn(level, event.getSpawnType(), level.getGameTime()));
                });
        bus.addListener(
                (net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) -> {
                    if (!watchingSpawns || pendingSpawns.isEmpty()) return;
                    var iterator = pendingSpawns.entrySet().iterator();
                    while (iterator.hasNext()) {
                        var entry = iterator.next();
                        var pending = entry.getValue();
                        if (pending.level().getServer() != event.getServer()) {
                            iterator.remove();
                            continue;
                        }
                        var entity = pending.level().getEntity(entry.getKey());
                        if (entity != null && entity.isAlive()) {
                            var pos = entity.blockPosition();
                            String type =
                                    BuiltInRegistries.ENTITY_TYPE
                                            .getKey(entity.getType())
                                            .toString();
                            confirmedSpawns.merge(type + ":" + pending.reason(), 1, Integer::sum);
                            LOG.info(
                                    "OCEAN_SPAWN_CONFIRMED uuid={} type={} reason={} pos={} biome={} localSea={} blockLight={} skyLight={} tick={} evidence=finalize-reason-and-live-server-entity",
                                    entry.getKey(),
                                    type,
                                    pending.reason(),
                                    pos,
                                    pending.level()
                                            .getBiome(pos)
                                            .unwrapKey()
                                            .orElseThrow()
                                            .location(),
                                    dev.starfold.planet.PlanetWaterSpawning.seaLevel(
                                            pending.level(), pos, pending.level().getSeaLevel()),
                                    pending.level()
                                            .getBrightness(
                                                    net.minecraft.world.level.LightLayer.BLOCK,
                                                    pos),
                                    pending.level()
                                            .getBrightness(
                                                    net.minecraft.world.level.LightLayer.SKY, pos),
                                    pending.level().getGameTime());
                            iterator.remove();
                        } else if (pending.level().getGameTime() - pending.tick() > 200)
                            iterator.remove();
                    }
                });
        bus.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> {
                    watchingSpawns = false;
                    pendingSpawns.clear();
                    confirmedSpawns.clear();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        Commands.literal("oceantest")
                                                .requires(source -> source.hasPermission(2))
                                                .then(
                                                        Commands.literal("nearby")
                                                                .executes(
                                                                        context ->
                                                                                run(
                                                                                        context
                                                                                                .getSource(),
                                                                                        "nearby",
                                                                                        32))
                                                                .then(
                                                                        Commands.argument(
                                                                                        "radius",
                                                                                        IntegerArgumentType
                                                                                                .integer(
                                                                                                        1,
                                                                                                        64))
                                                                                .executes(
                                                                                        context ->
                                                                                                run(
                                                                                                        context
                                                                                                                .getSource(),
                                                                                                        "nearby",
                                                                                                        IntegerArgumentType
                                                                                                                .getInteger(
                                                                                                                        context,
                                                                                                                        "radius")))))
                                                .then(
                                                        Commands.literal("allfaces")
                                                                .executes(
                                                                        context ->
                                                                                run(
                                                                                        context
                                                                                                .getSource(),
                                                                                        "allfaces",
                                                                                        0)))
                                                .then(
                                                        Commands.literal("seams")
                                                                .executes(
                                                                        context ->
                                                                                run(
                                                                                        context
                                                                                                .getSource(),
                                                                                        "seams",
                                                                                        0)))
                                                .then(
                                                        Commands.literal("recipes")
                                                                .executes(
                                                                        context ->
                                                                                run(
                                                                                        context
                                                                                                .getSource(),
                                                                                        "recipes",
                                                                                        0)))
                                                .then(
                                                        Commands.literal("spawn-watch")
                                                                .executes(
                                                                        context ->
                                                                                run(
                                                                                        context
                                                                                                .getSource(),
                                                                                        "spawn-watch",
                                                                                        0)))
                                                .then(
                                                        Commands.literal("spawns")
                                                                .executes(
                                                                        context ->
                                                                                run(
                                                                                        context
                                                                                                .getSource(),
                                                                                        "spawns",
                                                                                        0)))
                                                .then(
                                                        Commands.literal("require-natural")
                                                                .executes(
                                                                        context ->
                                                                                run(
                                                                                        context
                                                                                                .getSource(),
                                                                                        "require-natural",
                                                                                        0)))));
    }

    private static int run(CommandSourceStack source, String action, int radius)
            throws CommandSyntaxException {
        try {
            String world =
                    source.getServer()
                            .getWorldPath(LevelResource.ROOT)
                            .toAbsolutePath()
                            .normalize()
                            .getFileName()
                            .toString();
            require(
                    world.startsWith("regression-stage2-"),
                    "Requires world basename prefix regression-stage2-");
            require(
                    source.getLevel().getChunkSource().getGenerator()
                            instanceof SystemChunkGenerator,
                    "Requires native SystemChunkGenerator");
            var generator =
                    (SystemChunkGenerator) source.getLevel().getChunkSource().getGenerator();
            if (action.equals("recipes")) {
                recipes(source);
            } else if (action.equals("spawn-watch")) {
                watchingSpawns = true;
                pendingSpawns.clear();
                confirmedSpawns.clear();
                LOG.info(
                        "OCEAN_SPAWN_WATCH tick={} observations-only no-spawn-or-rule-mutations",
                        source.getLevel().getGameTime());
            } else if (action.equals("spawns")) {
                LOG.info(
                        "OCEAN_SPAWN_TOTAL tick={} watching={} confirmed={} pending={} no-observation-is-not-proof-of-absence",
                        source.getLevel().getGameTime(),
                        watchingSpawns,
                        confirmedSpawns,
                        pendingSpawns.size());
            } else if (action.equals("require-natural")) {
                require(watchingSpawns, "Start spawn-watch before requiring natural observations");
                int count =
                        confirmedSpawns.entrySet().stream()
                                .filter(entry -> entry.getKey().endsWith(":NATURAL"))
                                .mapToInt(Map.Entry::getValue)
                                .sum();
                require(
                        count > 0,
                        "No confirmed live NATURAL aquatic spawn in this observation session");
                LOG.info(
                        "OCEAN_NATURAL_REQUIRED confirmed={} tick={}",
                        count,
                        source.getLevel().getGameTime());
            } else {
                ServerPlayer player = source.getPlayerOrException();
                var owner = PlanetStorage.address(player.blockPosition()).owner();
                require(
                        owner.block().getX() == player.getBlockX()
                                && owner.block().getZ() == player.getBlockZ(),
                        "Stand in a canonical owned planet chart, outside halos");
                var terrain = generator.planet(owner.planet()).terrain();
                Selection selection = new Selection(owner.planet());
                if (action.equals("seams")) {
                    seams(source, terrain, selection);
                } else if (action.equals("nearby")) {
                    var here = owner.cell();
                    for (int du = -radius; du <= radius; du++)
                        for (int dv = -radius; dv <= radius; dv++) {
                            var cell =
                                    terrain.shape()
                                            .topology()
                                            .canonical(here.add(du, 0, dv))
                                            .cell();
                            selection.column(PlanetStorage.block(owner.planet(), cell));
                        }
                } else if (action.equals("allfaces")) {
                    candidates(terrain, selection);
                } else {
                    throw new IllegalArgumentException("Unknown action " + action);
                }
                if (!action.equals("seams"))
                    scan(source, player, terrain, selection, action, radius);
            }
            // DONE means the observation completed, not that every ecology type was found.
            String receipt = "OCEAN_TEST_DONE " + action;
            LOG.info("{} world={}", receipt, world);
            source.sendSuccess(() -> Component.literal(receipt), false);
            return 1;
        } catch (Exception error) {
            String message = "OCEAN_TEST_FAIL " + action + ": " + error.getMessage();
            LOG.error(message, error);
            source.sendFailure(Component.literal(message));
            throw new SimpleCommandExceptionType(Component.literal(message)).create();
        }
    }

    /** 16x16 terrain-only queries per face; eight sectors each select at most one 2x2 FULL patch.
     * No path search or chunk requests happen in this candidate phase. */
    private static void candidates(PlanetTerrain terrain, Selection selection) {
        int size = terrain.shape().size();
        for (Face face : Face.VALUES) {
            Cell[] best = new Cell[8];
            double[] scores = new double[8];
            java.util.Arrays.fill(scores, Double.POSITIVE_INFINITY);
            for (int a = 0; a < 16; a++)
                for (int b = 0; b < 16; b++) {
                    int u = (2 * a + 1) * size / 32, v = (2 * b + 1) * size / 32;
                    var liquid = terrain.liquid(face, u, v);
                    int ground = terrain.groundY(face, u, v);
                    if (liquid == null
                            || !liquid.fluid().getFluidState().is(FluidTags.WATER)
                            || ground >= liquid.level() - 1
                            || terrain.closure(face, u, v)) continue;
                    int sector = (a / 4) * 2 + b / 8;
                    int targetDepth = new int[] {4, 8, 16, 24}[sector % 4];
                    double score = Math.abs(liquid.level() - 1 - ground - targetDepth);
                    if (score < scores[sector]) {
                        scores[sector] = score;
                        best[sector] = new Cell(face, u, 0, v);
                    }
                }
            int selected = 0;
            for (Cell cell : best)
                if (cell != null) {
                    selected++;
                    LOG.info(
                            "OCEAN_CANDIDATE planet={} face={} u={} v={} ground={} liquid={} biome={} evidence=terrain-only",
                            selection.planet,
                            face,
                            cell.u(),
                            cell.v(),
                            terrain.groundY(face, cell.u(), cell.v()),
                            terrain.liquid(face, cell.u(), cell.v()),
                            terrain.biome(face, cell.u(), cell.v()));
                    // Stay inside this face and away from its halo. Four chunks per selected
                    // sector.
                    int cu = Math.clamp(cell.u() / 16 - 1, 0, terrain.shape().chunks() - 2);
                    int cv = Math.clamp(cell.v() / 16 - 1, 0, terrain.shape().chunks() - 2);
                    for (int du = 0; du < 2; du++)
                        for (int dv = 0; dv < 2; dv++) {
                            BlockPos pos =
                                    PlanetStorage.block(
                                            selection.planet,
                                            new Cell(face, (cu + du) * 16, 0, (cv + dv) * 16));
                            selection.chunk(new ChunkPos(pos)).set(0, 256);
                        }
                }
            LOG.info(
                    "OCEAN_CANDIDATES planet={} face={} coarseColumns=256 patches={} maxFullChunks=32",
                    selection.planet,
                    face,
                    selected);
        }
    }

    private static void scan(
            CommandSourceStack source,
            ServerPlayer player,
            PlanetTerrain terrain,
            Selection selection,
            String action,
            int radius) {
        var level = source.getLevel();
        Map<Face, Counts> faces = new LinkedHashMap<>();
        Counts total = new Counts();
        int full = 0;
        // The selection is complete and budget-checked before the first FULL request.
        for (var entry : selection.chunks.entrySet()) {
            ChunkPos at = entry.getKey();
            var chunk = level.getChunk(at.x, at.z, ChunkStatus.FULL, true);
            require(
                    chunk != null && chunk.getPersistedStatus() == ChunkStatus.FULL,
                    "Expected FULL chunk " + at);
            full++;
            for (int index = entry.getValue().nextSetBit(0);
                    index >= 0;
                    index = entry.getValue().nextSetBit(index + 1)) {
                int x = at.getMinBlockX() + index / 16, z = at.getMinBlockZ() + index % 16;
                var cell = PlanetStorage.address(new BlockPos(x, 128, z)).owner().cell();
                Counts face = faces.computeIfAbsent(cell.face(), ignored -> new Counts());
                int top =
                        Math.min(
                                level.getMaxBuildHeight() - 1,
                                chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15));
                boolean waterColumn = false, ecologyColumn = false, bedFound = false;
                boolean surfaceFound = false;
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int y = top; y >= level.getMinBuildHeight(); y--) {
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.isAir()) continue;
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (!surfaceFound && !state.isAir()) {
                        total.surface.merge(id, 1L, Long::sum);
                        face.surface.merge(id, 1L, Long::sum);
                        surfaceFound = true;
                    }
                    boolean aquatic = ecology(id);
                    boolean water = state.getFluidState().is(FluidTags.WATER);
                    if (water) {
                        waterColumn = true;
                        total.waterMaterials.merge(id, 1L, Long::sum);
                        face.waterMaterials.merge(id, 1L, Long::sum);
                        String fluid =
                                BuiltInRegistries.FLUID.getKey(state.getFluidState().getType())
                                        + ":source="
                                        + state.getFluidState().isSource();
                        total.fluids.merge(fluid, 1L, Long::sum);
                        face.fluids.merge(fluid, 1L, Long::sum);
                    }
                    if (waterColumn && !bedFound && state.isSolid() && !aquatic && !water) {
                        total.bed.merge(id, 1L, Long::sum);
                        face.bed.merge(id, 1L, Long::sum);
                        bedFound = true;
                    }
                    if (!aquatic) continue;
                    ecologyColumn = true;
                    String waterState =
                            id
                                    + ":water="
                                    + water
                                    + ":waterlogged="
                                    + (state.hasProperty(BlockStateProperties.WATERLOGGED)
                                            ? state.getValue(BlockStateProperties.WATERLOGGED)
                                            : "unsupported");
                    total.states.merge(waterState, 1L, Long::sum);
                    face.states.merge(waterState, 1L, Long::sum);
                    double distance = pos.distSqr(player.blockPosition());
                    String biome =
                            chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2)
                                    .unwrapKey()
                                    .map(key -> key.location().toString())
                                    .orElse("unkeyed");
                    String location =
                            "storage="
                                    + pos.toShortString()
                                    + " face="
                                    + cell.face()
                                    + " u="
                                    + cell.u()
                                    + " v="
                                    + cell.v()
                                    + " biome="
                                    + biome
                                    + " state="
                                    + state
                                    + " below="
                                    + chunk.getBlockState(pos.below());
                    total.observe(id, distance, location);
                    face.observe(id, distance, location);
                }
                total.column(surfaceFound, waterColumn, ecologyColumn, bedFound);
                face.column(surfaceFound, waterColumn, ecologyColumn, bedFound);
            }
        }
        String scope =
                "action="
                        + action
                        + " planet="
                        + selection.planet
                        + " worldSeed="
                        + level.getSeed()
                        + " terrainSeed="
                        + terrain.seed()
                        + " dimension="
                        + level.dimension().location()
                        + " origin="
                        + player.blockPosition().toShortString()
                        + (action.equals("nearby")
                                ? " radius=" + radius + " square=true"
                                : " coarseColumns=1536")
                        + " requestedFullChunks="
                        + full
                        + " requestBudget="
                        + MAX_CHUNKS;
        LOG.info(
                "OCEAN_SCOPE {} evidence=actual-FULL-columns nearestMetric=storage-distance generationDependencies=engine-managed",
                scope);
        total.report("all");
        for (Face face : Face.VALUES) {
            Counts counts = faces.get(face);
            if (counts != null) counts.report(face.name());
            else LOG.info("OCEAN_FACE face={} sampledColumns=0 evidence=unsampled", face);
        }
        source.sendSuccess(
                () ->
                        Component.literal(
                                "OCEAN_SAMPLE "
                                        + scope
                                        + " columns="
                                        + total.columns
                                        + " waterColumns="
                                        + total.waterColumns
                                        + " ecologyColumns="
                                        + total.ecologyColumns
                                        + " ecology="
                                        + total.blocks
                                        + "; details in starfold-probe log; inspection does not prove growth/harvest"),
                false);
    }

    private record SeamPair(Face face, String edge, int along, List<BlockPos> halos) {}

    private static final String[] SEAM_EDGES = {"u-", "u+", "v-", "v+"};

    /** All 24 oriented edges, at most two 16-column strips each: <=96 owner FULL requests.
     * Terrain selects water candidates only; it is not an oracle for a live, mutable world. */
    private static List<SeamPair> seamCandidates(PlanetTerrain terrain, Selection selection) {
        var pairs = new ArrayList<SeamPair>();
        var topology = terrain.shape().topology();
        int size = terrain.shape().size(), chunks = terrain.shape().chunks();
        for (Face face : Face.VALUES)
            for (int edge = 0; edge < 4; edge++) {
                int[] best = {-1, -1};
                int[] scores = {Integer.MAX_VALUE, Integer.MAX_VALUE};
                // At most 126 candidates per edge at the maximum face size; omit corner chunks.
                for (int c = 1; c < chunks - 1; c++) {
                    var halo = seamCell(face, edge, c * 16 + 8, size);
                    var inside = seamInside(halo, edge);
                    var other = topology.canonical(halo).cell();
                    if (!seamWater(terrain, inside) || !seamWater(terrain, other)) continue;
                    int score =
                            Math.abs(
                                            terrain.liquid(inside.face(), inside.u(), inside.v())
                                                            .level()
                                                    - terrain.groundY(
                                                            inside.face(), inside.u(), inside.v())
                                                    - 9)
                                    + Math.abs(
                                            terrain.liquid(other.face(), other.u(), other.v())
                                                            .level()
                                                    - terrain.groundY(
                                                            other.face(), other.u(), other.v())
                                                    - 9);
                    int sector = c < chunks / 2 ? 0 : 1;
                    if (score < scores[sector]) {
                        scores[sector] = score;
                        best[sector] = c;
                    }
                }
                int selected = 0;
                for (int c : best)
                    if (c >= 0) {
                        var halos = new ArrayList<BlockPos>();
                        for (int t = c * 16; t < c * 16 + 16; t++) {
                            var halo = seamCell(face, edge, t, size);
                            var inside = seamInside(halo, edge);
                            var across = topology.notification(halo, inside);
                            if (!seamWater(terrain, inside) || !seamWater(terrain, across.target()))
                                continue;
                            // notification.source is A viewed through B's halo; halo is B viewed
                            // from A.
                            for (Cell query : List.of(across.source(), halo)) {
                                var pos =
                                        new BlockPos(
                                                PlanetStorage.offset(selection.planet)
                                                        + query.face().ordinal()
                                                                * GroundCoordinates.STRIDE
                                                        + query.u(),
                                                GroundCoordinates.FLOOR,
                                                PlanetStorage.offsetZ(selection.planet)
                                                        + query.v());
                                var address = PlanetStorage.address(pos);
                                require(
                                        !pos.equals(address.owner().block()),
                                        "Expected a halo query " + pos);
                                selection.column(address.owner().block());
                                halos.add(pos);
                            }
                        }
                        pairs.add(new SeamPair(face, SEAM_EDGES[edge], c * 16, List.copyOf(halos)));
                        selected++;
                    }
                LOG.info(
                        "OCEAN_SEAM_EDGE planet={} face={} edge={} candidates={} selectedPairs={} evidence=terrain-water-selection-only",
                        selection.planet,
                        face,
                        SEAM_EDGES[edge],
                        Math.max(0, chunks - 2),
                        selected);
            }
        require(
                selection.chunks.size() <= 96 && selection.chunks.size() < MAX_CHUNKS,
                "Seam FULL budget exceeded before generation");
        return List.copyOf(pairs);
    }

    private static Cell seamCell(Face face, int edge, int along, int size) {
        return switch (edge) {
            case 0 -> new Cell(face, -1, 0, along);
            case 1 -> new Cell(face, size, 0, along);
            case 2 -> new Cell(face, along, 0, -1);
            default -> new Cell(face, along, 0, size);
        };
    }

    private static Cell seamInside(Cell halo, int edge) {
        return halo.add(edge == 0 ? 1 : edge == 1 ? -1 : 0, 0, edge == 2 ? 1 : edge == 3 ? -1 : 0);
    }

    private static boolean seamWater(PlanetTerrain terrain, Cell cell) {
        var liquid = terrain.liquid(cell.face(), cell.u(), cell.v());
        return liquid != null
                && liquid.fluid().getFluidState().is(FluidTags.WATER)
                && terrain.groundY(cell.face(), cell.u(), cell.v()) < liquid.level() - 1
                && !terrain.closure(cell.face(), cell.u(), cell.v());
    }

    private static void seams(
            CommandSourceStack source, PlanetTerrain terrain, Selection selection) {
        var pairs = seamCandidates(terrain, selection);
        var level = source.getLevel();
        LOG.info(
                "OCEAN_SEAM_SCOPE planet={} worldSeed={} terrainSeed={} dimension={} orientedEdges=24 selectedPairs={} selectedFullChunks={} requestBudget=96 columnsPerPairMax=32 yMin={} yMaxExclusive={} evidence=actual-runtime-seam-query-consistency independentGeneration=false terrain=selection-only surfaceComparison=SKIP-shared-canonical-path-and-live-mutations generationDependencies=engine-managed",
                selection.planet,
                level.getSeed(),
                terrain.seed(),
                level.dimension().location(),
                pairs.size(),
                selection.chunks.size(),
                level.getMinBuildHeight(),
                level.getMaxBuildHeight());
        require(
                !pairs.isEmpty(),
                "No terrain-predicted seam water candidates; no useful water sampling");
        var loaded = new LinkedHashMap<ChunkPos, net.minecraft.world.level.chunk.ChunkAccess>();
        // The complete fixed selection, including both owners of every pair, precedes generation.
        for (ChunkPos at : selection.chunks.keySet()) {
            var chunk = level.getChunk(at.x, at.z, ChunkStatus.FULL, true);
            require(
                    chunk != null && chunk.getPersistedStatus() == ChunkStatus.FULL,
                    "Expected FULL seam owner " + at);
            loaded.put(at, chunk);
        }
        long columns = 0,
                blocks = 0,
                water = 0,
                flora = 0,
                substrate = 0,
                mismatches = 0,
                wetPairs = 0;
        int pairIndex = 0, usefulPairs = 0;
        for (SeamPair pair : pairs) {
            long pairBlocks = 0,
                    pairWater = 0,
                    pairFlora = 0,
                    pairSubstrate = 0,
                    pairMismatches = 0;
            int[] wetSides = new int[2], bedSides = new int[2];
            boolean[] floraSides = new boolean[2];
            int columnIndex = 0;
            for (BlockPos halo : pair.halos()) {
                var address = PlanetStorage.address(halo);
                var owner = address.owner().block();
                var chunk = loaded.get(new ChunkPos(owner));
                require(chunk != null, "Unprepared seam owner " + owner);
                long columnWater = 0, columnFlora = 0, columnSubstrate = 0;
                String waterSample = "none", floraSample = "none", bedSample = "none";
                boolean belowWater = false;
                for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
                    var at = owner.atY(y);
                    // Same source: compare B's halo to B's own state, never A's adjacent state.
                    var canonical = chunk.getBlockState(at);
                    var expected =
                            address.oriented().read(canonical); // -turns, as ChunkViews/Level do.
                    var query = halo.atY(y);
                    var actual = level.getBlockState(query);
                    var fluid = level.getFluidState(query);
                    boolean blockMismatch = !actual.equals(expected);
                    boolean fluidMismatch = !fluid.equals(expected.getFluidState());
                    if (blockMismatch || fluidMismatch) {
                        if (mismatches + pairMismatches < 16)
                            LOG.error(
                                    "OCEAN_SEAM_MISMATCH pair={} halo={} owner={} turns={} canonical={} expected={} actual={} expectedFluid={} actualFluid={} blockMismatch={} fluidMismatch={}",
                                    pairIndex,
                                    query.toShortString(),
                                    at.toShortString(),
                                    address.turns(),
                                    canonical,
                                    expected,
                                    actual,
                                    expected.getFluidState(),
                                    fluid,
                                    blockMismatch,
                                    fluidMismatch);
                        pairMismatches++;
                    }
                    pairBlocks++;
                    if (canonical.getFluidState().is(FluidTags.WATER)) {
                        if (columnWater == 0) waterSample = seamSample(at, query, canonical);
                        columnWater++;
                        belowWater = true;
                    }
                    String id = BuiltInRegistries.BLOCK.getKey(canonical.getBlock()).toString();
                    if (ecology(id)) {
                        if (columnFlora == 0) floraSample = seamSample(at, query, canonical);
                        columnFlora++;
                    } else if (belowWater
                            && columnSubstrate == 0
                            && canonical.isSolid()
                            && canonical.getFluidState().isEmpty()) {
                        // First solid underwater bed, rather than every deep stone/bedrock block.
                        columnSubstrate++;
                        bedSample = seamSample(at, query, canonical);
                    }
                }
                int side = columnIndex++ % 2;
                if (columnWater > 0 && wetSides[side]++ == 0)
                    LOG.info(
                            "OCEAN_SEAM_SAMPLE pair={} side={} category=water {}",
                            pairIndex,
                            side,
                            waterSample);
                if (columnSubstrate > 0 && bedSides[side]++ == 0)
                    LOG.info(
                            "OCEAN_SEAM_SAMPLE pair={} side={} category=substrate {}",
                            pairIndex,
                            side,
                            bedSample);
                if (columnFlora > 0 && !floraSides[side]) {
                    floraSides[side] = true;
                    LOG.info(
                            "OCEAN_SEAM_SAMPLE pair={} side={} category=flora {}",
                            pairIndex,
                            side,
                            floraSample);
                }
                pairWater += columnWater;
                pairFlora += columnFlora;
                pairSubstrate += columnSubstrate;
                LOG.info(
                        "OCEAN_SEAM_COLUMN pair={} side={} halo={} owner={} face={} u={} v={} turns={} water={} flora={} substrate={} evidence=actual-FULL-owner-and-Level-halo",
                        pairIndex,
                        side,
                        halo.toShortString(),
                        owner.toShortString(),
                        address.owner().cell().face(),
                        address.owner().cell().u(),
                        address.owner().cell().v(),
                        address.turns(),
                        columnWater,
                        columnFlora,
                        columnSubstrate);
            }
            if (wetSides[0] > 0 && wetSides[1] > 0) wetPairs++;
            if (wetSides[0] > 0 && wetSides[1] > 0 && bedSides[0] > 0 && bedSides[1] > 0)
                usefulPairs++;
            LOG.info(
                    "OCEAN_SEAM_PAIR pair={} face={} edge={} along={} columns={} blocks={} water={} flora={} substrate={} wetSides={} bedSides={} mismatches={}",
                    pairIndex++,
                    pair.face(),
                    pair.edge(),
                    pair.along(),
                    pair.halos().size(),
                    pairBlocks,
                    pairWater,
                    pairFlora,
                    pairSubstrate,
                    java.util.Arrays.toString(wetSides),
                    java.util.Arrays.toString(bedSides),
                    pairMismatches);
            columns += pair.halos().size();
            blocks += pairBlocks;
            water += pairWater;
            flora += pairFlora;
            substrate += pairSubstrate;
            mismatches += pairMismatches;
        }
        LOG.info(
                "OCEAN_SEAM_TOTAL pairs={} wetPairs={} usefulPairs={} columns={} blocks={} water={} flora={} substrate={} mismatches={} requestedFullChunks={} evidence=actual-runtime-seam-query-consistency independentGeneration=false growth=UNTESTED persistence=UNTESTED ordinaryGameplay=UNTESTED",
                pairs.size(),
                wetPairs,
                usefulPairs,
                columns,
                blocks,
                water,
                flora,
                substrate,
                mismatches,
                loaded.size());
        require(mismatches == 0, "Actual runtime seam query mismatches=" + mismatches);
        require(usefulPairs > 0, "No useful two-sided actual water and substrate seam sampling");
    }

    private static String seamSample(BlockPos owner, BlockPos halo, BlockState state) {
        return "owner="
                + owner.toShortString()
                + " halo="
                + halo.toShortString()
                + " canonical="
                + state
                + " fluid="
                + state.getFluidState();
    }

    private static boolean ecology(String id) {
        if (!id.startsWith("minecraft:")) return false;
        String path = id.substring("minecraft:".length());
        for (String type : ECOLOGY) if (path.equals(type)) return true;
        // Includes live/dead coral plants, blocks and floor/wall fans, reported individually.
        return path.contains("coral")
                && (path.endsWith("_coral")
                        || path.endsWith("_coral_block")
                        || path.endsWith("_coral_fan")
                        || path.endsWith("_coral_wall_fan"));
    }

    private static void recipes(CommandSourceStack source) {
        var level = source.getLevel();
        var manager = source.getServer().getRecipeManager();
        var input = new SingleRecipeInput(new ItemStack(Items.KELP));
        var smelting = manager.getRecipeFor(RecipeType.SMELTING, input, level);
        require(smelting.isPresent(), "Missing live kelp smelting recipe");
        require(
                smelting.get().value().getResultItem(level.registryAccess()).is(Items.DRIED_KELP),
                "Live kelp smelting does not output dried kelp");
        LOG.info(
                "OCEAN_RECIPE type=smelting input=minecraft:kelp id={} output={}",
                smelting.get().id(),
                smelting.get().value().getResultItem(level.registryAccess()));
        var smoking = manager.getRecipeFor(RecipeType.SMOKING, input, level);
        var campfire = manager.getRecipeFor(RecipeType.CAMPFIRE_COOKING, input, level);
        require(
                smoking.isPresent()
                        && smoking.get()
                                .value()
                                .getResultItem(level.registryAccess())
                                .is(Items.DRIED_KELP),
                "Missing live kelp smoking to dried kelp");
        require(
                campfire.isPresent()
                        && campfire.get()
                                .value()
                                .getResultItem(level.registryAccess())
                                .is(Items.DRIED_KELP),
                "Missing live kelp campfire cooking to dried kelp");
        LOG.info(
                "OCEAN_RECIPE smoking={} campfire={} output=minecraft:dried_kelp evidence=live-recipe-manager",
                smoking.get().id(),
                campfire.get().id());
        var oilId = ResourceLocation.parse("starfold_mining:oil_extractor");
        var oil = manager.byKey(oilId);
        if (BuiltInRegistries.ITEM.containsKey(oilId)) {
            require(oil.isPresent(), "Registered oil extractor lacks live recipe " + oilId);
            require(
                    oil.get()
                            .value()
                            .getResultItem(level.registryAccess())
                            .is(BuiltInRegistries.ITEM.get(oilId)),
                    "Oil extractor recipe output mismatch");
            LOG.info(
                    "OCEAN_RECIPE optionalOil={} output={}",
                    oilId,
                    oil.get().value().getResultItem(level.registryAccess()));
        } else {
            LOG.info(
                    "OCEAN_RECIPE optionalOil={} status=SKIP-item-absent recipePresent={}",
                    oilId,
                    oil.isPresent());
        }
    }

    private static final class Selection {
        final int planet;
        final Map<ChunkPos, BitSet> chunks = new LinkedHashMap<>();

        Selection(int planet) {
            this.planet = planet;
        }

        BitSet chunk(ChunkPos at) {
            require(
                    PlanetStorage.key(at.x, at.z) >= (long) planet * PlanetStorage.CHUNKS_PER_PLANET
                            && PlanetStorage.key(at.x, at.z)
                                    < (long) (planet + 1) * PlanetStorage.CHUNKS_PER_PLANET,
                    "Sample outside canonical planet chunks " + at);
            if (!chunks.containsKey(at)) {
                require(chunks.size() < MAX_CHUNKS, "FULL request budget exceeded before scan");
                chunks.put(at, new BitSet(256));
            }
            return chunks.get(at);
        }

        void column(BlockPos pos) {
            chunk(new ChunkPos(pos)).set((pos.getX() & 15) * 16 + (pos.getZ() & 15));
        }
    }

    private static final class Counts {
        long columns, surfaceColumns, waterColumns, ecologyColumns, bedColumns;
        final Map<String, Long> blocks = new TreeMap<>(),
                states = new TreeMap<>(),
                fluids = new TreeMap<>(),
                surface = new TreeMap<>(),
                bed = new TreeMap<>(),
                waterMaterials = new TreeMap<>();
        final Map<String, Double> distances = new TreeMap<>();
        final Map<String, String> nearest = new TreeMap<>();

        void column(boolean hasSurface, boolean water, boolean ecology, boolean hasBed) {
            columns++;
            if (hasSurface) surfaceColumns++;
            if (water) waterColumns++;
            if (ecology) ecologyColumns++;
            if (hasBed) bedColumns++;
        }

        void observe(String id, double distance, String location) {
            blocks.merge(id, 1L, Long::sum);
            if (distance < distances.getOrDefault(id, Double.POSITIVE_INFINITY)) {
                distances.put(id, distance);
                nearest.put(id, location);
            }
        }

        void report(String face) {
            LOG.info(
                    "OCEAN_COLUMNS face={} sampled={} surface={} water={} ecology={} underwaterBed={} surfaceMaterials={} underwaterBedMaterials={}",
                    face,
                    columns,
                    surfaceColumns,
                    waterColumns,
                    ecologyColumns,
                    bedColumns,
                    surface,
                    bed);
            LOG.info(
                    "OCEAN_BLOCKS face={} ecology={} waterStates={} fluids={} waterMaterials={} evidence=inspection-only growth=UNTESTED harvest=UNTESTED",
                    face,
                    blocks,
                    states,
                    fluids,
                    waterMaterials);
            for (String type : ECOLOGY)
                LOG.info(
                        "OCEAN_TYPE face={} type=minecraft:{} blocks={}",
                        face,
                        type,
                        blocks.getOrDefault("minecraft:" + type, 0L));
            for (var entry : nearest.entrySet())
                LOG.info(
                        "OCEAN_NEAREST face={} type={} distanceSquared={} {}",
                        face,
                        entry.getKey(),
                        distances.get(entry.getKey()),
                        entry.getValue());
        }
    }

    private static boolean testLevel(net.minecraft.server.level.ServerLevel level) {
        return level.getChunkSource().getGenerator() instanceof SystemChunkGenerator
                && level.getServer()
                        .getWorldPath(LevelResource.ROOT)
                        .toAbsolutePath()
                        .normalize()
                        .getFileName()
                        .toString()
                        .startsWith("regression-stage2-");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private DevOceanProbe() {}
}
