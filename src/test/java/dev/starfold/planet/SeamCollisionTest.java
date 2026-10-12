package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundSpace;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.GroundTerrain;
import dev.starfold.render.GroundChunkSnapshot;
import dev.starfold.render.GroundViewCache;
import dev.starfold.worldgen.PlanetEcologyTest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

/** No ClientLevel/GPU: exercises the same collision adapter with explicit residency and snapshots. */
class SeamCollisionTest {
    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    @Test
    void receivedAirAcrossEveryEdgeIsPassableEvenOutsideVanillaChunkWindow() {
        var access = PlanetAccess.SHARED;
        for (int planet = 0; planet < 2; planet++)
            for (var face : CubeSurfaceTopology.Face.values())
                for (int[] uv : new int[][] {{-1, 1024}, {2048, 1024}, {1024, -1}, {1024, 2048}}) {
                    var source = new PlanetAccessTest.BlocksView();
                    var query =
                            new BlockPos(
                                    PlanetStorage.offset(planet) + face.ordinal() * 4096 + uv[0],
                                    150,
                                    uv[1]);
                    var expected = access.address(query).owner();
                    var view =
                            GroundTerrain.view(
                                    source,
                                    access,
                                    GroundSpace.DEFAULT_MASK,
                                    p -> false,
                                    p -> {
                                        assertTrue(access.inChart(p));
                                        assertEquals(planetOf(expected), planetOf(p));
                                        return Blocks.AIR.defaultBlockState();
                                    });
                    assertTrue(view.getBlockState(query).isAir());
                    assertFalse(
                            GroundTerrain.overlaps(
                                    view, CollisionContext.empty(), new AABB(query).deflate(.05)));
                    assertTrue(
                            source.reads.isEmpty(),
                            "Missing native chunks must not be queried/generated");
                }
    }

    private int planetOf(BlockPos pos) {
        return PlanetStorage.planet(pos);
    }

    @Test
    void unknownStillBlocksAndFullSnapshotArrivalRemovesOnlyTheFalseWall() {
        var source = new PlanetAccessTest.BlocksView();
        var access = PlanetAccess.SHARED;
        var query = new BlockPos(32768 + 2048, 150, 1024);
        var cache = new GroundViewCache(k -> {});
        var view =
                GroundTerrain.view(
                        source,
                        access,
                        GroundSpace.DEFAULT_MASK,
                        p -> false,
                        p -> cache.canonical(PlanetStorage.local(1, p)));
        assertTrue(
                GroundTerrain.overlaps(
                        view, CollisionContext.empty(), new AABB(query).deflate(.05)));
        var owner = PlanetStorage.local(1, access.address(query).owner());
        var cell = GroundTopology.cell(owner);
        var values = new int[GroundChunkSnapshot.VALUES];
        Arrays.fill(values, Block.getId(Blocks.AIR.defaultBlockState()));
        cache.accept(
                GroundChunkSnapshot.fromStates(
                        cell.face().ordinal(), cell.u() / 16, cell.v() / 16, 1, values));
        assertTrue(view.getBlockState(query).isAir());
        assertFalse(
                GroundTerrain.overlaps(
                        view, CollisionContext.empty(), new AABB(query).deflate(.05)));
        cache.clear();
        assertSame(Blocks.BEDROCK.defaultBlockState(), view.getBlockState(query));
    }

    @Test
    void synchronizedStairsKeepTheirOrientedShapeRatherThanBecomingAirOrFullCubes() {
        var access = PlanetAccess.SHARED;
        for (int planet = 0; planet < 2; planet++)
            for (var face : CubeSurfaceTopology.Face.values()) {
                var source = new PlanetAccessTest.BlocksView();
                var query =
                        new BlockPos(
                                PlanetStorage.offset(planet) + face.ordinal() * 4096 + 2048,
                                150,
                                1024);
                var address = access.address(query);
                var stair = Blocks.OAK_STAIRS.defaultBlockState();
                var view =
                        GroundTerrain.view(
                                source,
                                access,
                                GroundSpace.DEFAULT_MASK,
                                p -> false,
                                p ->
                                        p.equals(address.owner())
                                                ? address.write(stair)
                                                : Blocks.AIR.defaultBlockState());
                assertSame(stair, view.getBlockState(query));
                double volume =
                        view.getBlockState(query).getCollisionShape(view, query).toAabbs().stream()
                                .mapToDouble(b -> b.getXsize() * b.getYsize() * b.getZsize())
                                .sum();
                assertEquals(.75, volume, 1e-12);
            }
    }

    @Test
    void residentBlocksOverrideOldSnapshotAndMaskStillWins() {
        var source = new PlanetAccessTest.BlocksView();
        var query = new BlockPos(32768 + 1024, 150, 1024);
        source.blocks.put(query, Blocks.STONE.defaultBlockState());
        var view =
                GroundTerrain.view(
                        source,
                        PlanetAccess.SHARED,
                        GroundSpace.DEFAULT_MASK,
                        p -> true,
                        p -> {
                            fail("Resident chunks take precedence");
                            return null;
                        });
        assertSame(Blocks.STONE.defaultBlockState(), view.getBlockState(query));
        var masked =
                GroundTerrain.view(
                        source,
                        PlanetAccess.SHARED,
                        GroundSpace.DEFAULT_MASK,
                        p -> {
                            fail("Mask must precede residency");
                            return false;
                        },
                        p -> Blocks.AIR.defaultBlockState());
        assertSame(
                Blocks.BEDROCK.defaultBlockState(),
                masked.getBlockState(new BlockPos(32769, 128, 1)));
    }

    @Test
    void collisionReadDoesNotInventMissingColumnsFromTheGenerator() throws Exception {
        var source = Sources.text("GroundViewClient");
        int start =
                source.indexOf(
                        "static net.minecraft.world.level.block.state.BlockState collisionState(");
        int end = source.indexOf("private static final Map<Long,Map<", start);
        assertTrue(start >= 0 && end > start);
        var method = source.substring(start, end);
        assertTrue(method.contains("cache.canonical(owner)"));
        assertTrue(method.contains("if(state==null)return null"));
        assertTrue(method.contains("deltas.get(owner)"));
        assertFalse(method.contains("generated("));
        assertTrue(
                source.contains(
                        "world==level&&samePlanet(owner)?collisionState(GroundClient.local(owner)):null"));
    }
}
