package dev.starfold.dev;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import dev.ryanhcode.sable.util.LevelAccelerator;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.ground.ChunkViews;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.sky.GroundClock;
import dev.starfold.universe.SystemChunkGenerator;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;

/** Loaded-only Sable seam reads. Register explicitly from the developer probe entry point. */
public final class DevSableReadProbe {
    private static final Logger LOG = LoggerFactory.getLogger("starfold-probe");
    private static final int MAX_COLUMNS = 120;

    public static void initialize() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        Commands.literal("sablereadtest")
                                                .requires(source -> source.hasPermission(2))
                                                .executes(context -> run(context.getSource()))));
    }

    private static int run(CommandSourceStack source) throws CommandSyntaxException {
        try {
            require(source.hasPermission(2), "Requires permission level 2");
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
            var level = source.getLevel();
            require(
                    PlanetLevels.is(level)
                            && level.getChunkSource().getGenerator()
                                    instanceof SystemChunkGenerator,
                    "Requires native planet level and SystemChunkGenerator");
            var here = source.getPlayerOrException().blockPosition();
            var playerOwner = PlanetStorage.address(here).owner();
            require(playerOwner.block().equals(here), "Stand in a canonical owned planet chart");
            int planet = playerOwner.planet();
            var access = PlanetAccess.of(level);
            var shape = access.shape(here);
            int size = shape.size();
            // Fixed, bounded selection, plus the player's along-edge coordinates for nearby
            // fixtures.
            var along = new LinkedHashSet<Integer>();
            for (int value :
                    new int[] {
                        16, size / 2, size - 17, playerOwner.cell().u(), playerOwner.cell().v()
                    }) along.add(Math.clamp(value, 1, size - 2));
            var candidates = new LinkedHashSet<BlockPos>();
            for (var face : Face.values()) {
                int x = PlanetStorage.offset(planet) + face.ordinal() * GroundCoordinates.STRIDE;
                int z = PlanetStorage.offsetZ(planet);
                for (int t : along) {
                    candidates.add(new BlockPos(x - 1, here.getY(), z + t));
                    candidates.add(new BlockPos(x + size, here.getY(), z + t));
                    candidates.add(new BlockPos(x + t, here.getY(), z - 1));
                    candidates.add(new BlockPos(x + t, here.getY(), z + size));
                }
            }
            require(candidates.size() <= MAX_COLUMNS, "Column budget exceeded");
            int min = level.getMinBuildHeight(), max = level.getMaxBuildHeight();
            require(max > min && (long) max - min <= 1024, "Requires native height span <= 1024");
            var accelerator = new LevelAccelerator(level);
            long samples = 0, nonair = 0, rotatedStates = 0, fluids = 0;
            int columns = 0, unloaded = 0, masked = 0;
            var mask = GroundClock.get(level).mask;
            LOG.info(
                    "SABLE_READ_SCOPE dimension={} planet={} candidates={} maxColumns={} yMin={} yMaxExclusive={} loadedOnly=true generationRequests=0 blockWrites=0",
                    level.dimension().location(),
                    planet,
                    candidates.size(),
                    MAX_COLUMNS,
                    min,
                    max);
            for (var halo : candidates) {
                int cx = halo.getX() >> 4, cz = halo.getZ() >> 4;
                require(ChunkViews.ghost(access, cx, cz), "Expected halo coordinate " + halo);
                var address = access.address(halo);
                var owner = address.owner();
                var chunk =
                        level.getChunkSource().getChunkNow(owner.getX() >> 4, owner.getZ() >> 4);
                var mapping = ChunkViews.columns(access, cx, cz);
                // No Level/accelerator reads until every possible owner is already FULL and
                // current.
                if (chunk == null
                        || !ChunkViews.ready(
                                level,
                                mapping,
                                key ->
                                        level.getChunkSource()
                                                        .getChunkNow(
                                                                ChunkPos.getX(key),
                                                                ChunkPos.getZ(key))
                                                != null)) {
                    unloaded++;
                    continue;
                }
                require(
                        accelerator.getChunk(owner) == chunk,
                        "Canonical chunk lookup changed " + owner);
                var ghost = ChunkViews.view(level, cx, cz);
                require(
                        accelerator.getChunk(cx, cz) == ghost,
                        "Accelerator bypassed ghost topology " + halo);
                outsideHeight(accelerator, ghost, halo, min, max);
                outsideHeight(accelerator, chunk, owner, min, max);
                columns++;
                for (int y = min; y < max; y++) {
                    var query = halo.atY(y);
                    var at = owner.atY(y);
                    if (Anomaly.mask(shape, mask, access.cell(at))) {
                        masked++;
                        continue;
                    }
                    var canonical = chunk.getBlockState(at);
                    var expected = address.read(canonical);
                    var actual = accelerator.getBlockState(query);
                    var direct = accelerator.getBlockState(ghost, query);
                    var view = ghost.getBlockState(query);
                    var ordinary = level.getBlockState(query);
                    boolean matches =
                            expected.equals(actual)
                                    && expected.equals(direct)
                                    && expected.equals(view)
                                    && expected.equals(ordinary)
                                    && expected.getFluidState()
                                            .equals(accelerator.getFluidState(query))
                                    && expected.getFluidState().equals(ghost.getFluidState(query))
                                    && expected.getFluidState().equals(level.getFluidState(query))
                                    && canonical.equals(accelerator.getBlockState(at))
                                    && canonical.equals(accelerator.getBlockState(chunk, at))
                                    && canonical.equals(level.getBlockState(at));
                    if (!matches)
                        throw new IllegalArgumentException(
                                "Read mismatch halo="
                                        + query
                                        + " owner="
                                        + at
                                        + " turns="
                                        + address.turns()
                                        + " canonical="
                                        + canonical
                                        + " expected="
                                        + expected
                                        + " accelerator="
                                        + actual
                                        + " direct="
                                        + direct
                                        + " view="
                                        + view
                                        + " level="
                                        + ordinary
                                        + " expectedFluid="
                                        + expected.getFluidState()
                                        + " acceleratorFluid="
                                        + accelerator.getFluidState(query));
                    boolean rotated = !canonical.equals(expected);
                    boolean wet = !expected.getFluidState().isEmpty();
                    if ((!expected.isAir() && nonair == 0)
                            || (rotated && rotatedStates == 0)
                            || (wet && fluids == 0))
                        LOG.info(
                                "SABLE_READ_SAMPLE halo={} owner={} turns={} canonical={} expected={} actual={} fluid={}",
                                query,
                                at,
                                address.turns(),
                                canonical,
                                expected,
                                actual,
                                actual.getFluidState());
                    samples++;
                    if (!expected.isAir()) nonair++;
                    if (rotated) rotatedStates++;
                    if (wet) fluids++;
                }
                // Alternate ghost/canonical/ghost on the same accelerator to exercise its chunk
                // cache.
                require(
                        accelerator.getChunk(owner) == chunk && accelerator.getChunk(halo) == ghost,
                        "Chunk cache lost canonical/ghost topology");
            }
            LOG.info(
                    "SABLE_READ_RESULT status={} columns={} samples={} nonair={} rotatedStates={} fluids={} skippedUnloadedColumns={} skippedMaskedSamples={} orientationCoverage={} fluidCoverage={}",
                    nonair > 0 ? "PASS" : "INCONCLUSIVE",
                    columns,
                    samples,
                    nonair,
                    rotatedStates,
                    fluids,
                    unloaded,
                    masked,
                    rotatedStates > 0 ? "OBSERVED" : "SKIP-no-state-changing-rotation",
                    fluids > 0 ? "OBSERVED" : "SKIP-no-fluid-sample");
            if (nonair == 0) {
                source.sendFailure(
                        Component.literal(
                                "Sable read INCONCLUSIVE: no loaded, unmasked nonair seam sample; no chunks generated"));
                return 0;
            }
            long passed = samples;
            source.sendSuccess(
                    () ->
                            Component.literal(
                                    "Sable read PASS: "
                                            + passed
                                            + " loaded seam samples; see SABLE_READ_RESULT for orientation/fluid coverage"),
                    false);
            return 1;
        } catch (IllegalArgumentException failure) {
            LOG.error("SABLE_READ_FAIL {}", failure.getMessage());
            throw new SimpleCommandExceptionType(Component.literal(failure.getMessage())).create();
        }
    }

    private static void outsideHeight(
            LevelAccelerator accelerator, LevelChunk chunk, BlockPos column, int min, int max) {
        for (int y : new int[] {min - 1, max}) {
            var at = column.atY(y);
            require(
                    Blocks.AIR.defaultBlockState().equals(accelerator.getBlockState(chunk, at))
                            && Blocks.AIR.defaultBlockState().equals(accelerator.getBlockState(at)),
                    "Sable out-of-height AIR changed " + at);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private DevSableReadProbe() {}
}
