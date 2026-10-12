package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundSpace;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.ground.GroundCoordinates;
import dev.starfold.ground.GroundSeams;
import dev.starfold.ground.GroundTerrain;
import dev.starfold.ground.GroundTopology;
import dev.starfold.worldgen.PlanetEcologyTest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.junit.jupiter.api.*;

import java.util.*;

class PlanetAccessTest {
    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    static final class BlocksView implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Map<BlockPos, BlockEntity> entities = new HashMap<>();
        final List<BlockPos> reads = new ArrayList<>();

        public BlockState getBlockState(BlockPos p) {
            reads.add(p.immutable());
            return blocks.getOrDefault(p, Blocks.AIR.defaultBlockState());
        }

        public FluidState getFluidState(BlockPos p) {
            return getBlockState(p).getFluidState();
        }

        public BlockEntity getBlockEntity(BlockPos p) {
            return entities.get(p);
        }

        public int getMinBuildHeight() {
            return -64;
        }

        public int getHeight() {
            return 384;
        }
    }

    @Test
    void legacyLayoutDoesNotReinterpretBAddresses() {
        var b = new BlockPos(32768 + 1024, 128, 1024);
        assertFalse(PlanetAccess.LEGACY.inChart(b));
        assertTrue(PlanetAccess.SHARED.inChart(b));
        assertThrows(IllegalArgumentException.class, () -> GroundTopology.address(b));
        assertThrows(IllegalArgumentException.class, () -> PlanetAccess.LEGACY.address(b));
        assertEquals(0, PlanetAccess.LEGACY.storagePlanet(b));
        assertEquals(1, PlanetAccess.SHARED.storagePlanet(b));
        assertEquals(PlanetAccess.LEGACY, PlanetAccess.of(null));
    }

    @Test
    void sameLevelBlockGetterReadsAndWritesTheCorrectPlanetAcrossAllEdges() {
        var access = PlanetAccess.SHARED;
        var source = new BlocksView();
        var view = GroundTerrain.view(source, access, GroundSpace.DEFAULT_MASK);
        for (var f : Face.values())
            for (int[] uv : new int[][] {{-1, 1024}, {2048, 1024}, {1024, -1}, {1024, 2048}}) {
                var a = new BlockPos(f.ordinal() * 4096 + uv[0], 150, uv[1]);
                var b = a.offset(32768, 0, 0);
                var aa = access.address(a);
                var ba = access.address(b);
                source.blocks.put(aa.owner(), aa.write(Blocks.OAK_STAIRS.defaultBlockState()));
                source.blocks.put(ba.owner(), ba.write(Blocks.WATER.defaultBlockState()));
                assertSame(Blocks.OAK_STAIRS.defaultBlockState(), view.getBlockState(a));
                assertSame(Blocks.WATER.defaultBlockState(), view.getBlockState(b));
                assertTrue(view.getFluidState(b).isSource());
                assertTrue(view.getFluidState(a).isEmpty());
                source.blocks.put(ba.owner(), ba.write(Blocks.GOLD_BLOCK.defaultBlockState()));
                assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), view.getBlockState(b));
                assertSame(Blocks.OAK_STAIRS.defaultBlockState(), view.getBlockState(a));
            }
    }

    @Test
    void blockEntityQueriesReturnOnlyTheCanonicalEntityOfTheirPlanet() {
        var access = PlanetAccess.SHARED;
        var source = new BlocksView();
        var view = GroundTerrain.view(source, access, GroundSpace.DEFAULT_MASK);
        var a = new BlockPos(2048, 150, 1024);
        var b = a.offset(32768, 0, 0);
        var ap = access.address(a).owner();
        var bp = access.address(b).owner();
        var ae =
                new net.minecraft.world.level.block.entity.ChestBlockEntity(
                        ap, Blocks.CHEST.defaultBlockState());
        var be =
                new net.minecraft.world.level.block.entity.ChestBlockEntity(
                        bp, Blocks.CHEST.defaultBlockState());
        source.entities.put(ap, ae);
        source.entities.put(bp, be);
        assertSame(ae, view.getBlockEntity(a));
        assertSame(be, view.getBlockEntity(b));
        assertNotSame(view.getBlockEntity(a), view.getBlockEntity(b));
    }

    @Test
    void collisionOnBUsesBsRealShapeNotAsAirOrAsBlock() {
        var access = PlanetAccess.SHARED;
        var source = new BlocksView();
        var view = GroundTerrain.view(source, access, GroundSpace.DEFAULT_MASK);
        var a = new BlockPos(1024, 150, 1024);
        var b = a.offset(32768, 0, 0);
        source.blocks.put(b, Blocks.STONE.defaultBlockState());
        var box = new AABB(a).deflate(.1);
        var bBox = box.move(32768, 0, 0);
        assertFalse(GroundTerrain.overlaps(view, CollisionContext.empty(), box));
        assertTrue(GroundTerrain.overlaps(view, CollisionContext.empty(), bBox));
        source.blocks.remove(b);
        source.blocks.put(a, Blocks.STONE.defaultBlockState());
        assertTrue(GroundTerrain.overlaps(view, CollisionContext.empty(), box));
        assertFalse(GroundTerrain.overlaps(view, CollisionContext.empty(), bBox));
    }

    @Test
    void maskOnBDoesNotQueryOrMutateEitherRealPlanet() {
        var source = new BlocksView();
        var view = GroundTerrain.view(source, PlanetAccess.SHARED, new GroundSpace.Config(16, 128));
        assertSame(
                Blocks.BEDROCK.defaultBlockState(),
                view.getBlockState(new BlockPos(32768 + 1, 128, 1)));
        assertTrue(source.reads.isEmpty());
        assertTrue(source.blocks.isEmpty());
    }

    @Test
    void entityImagesAndBoundingBoxesNeverAliasTheOtherPlanet() {
        var access = PlanetAccess.SHARED;
        for (var f : Face.values()) {
            var pose = new GroundTopology.Pose(f, new Point(1024, 30, 1024));
            var a = access.feet(0, pose);
            var b = access.feet(1, pose);
            assertNull(access.image(a.x(), a.z(), b));
            assertNull(access.image(b.x(), b.z(), a));
            assertEquals(b, access.image(b.x(), b.z(), b));
            var box = new AABB(b.x() - .3, b.y(), b.z() - .3, b.x() + .3, b.y() + 1.8, b.z() + .3);
            assertNull(GroundSeams.box(access, box, a.x(), a.z()));
            assertEquals(box, GroundSeams.box(access, box, b.x(), b.z()));
        }
    }

    @Test
    void crossFaceImagesAreTheSameRigidTransformTranslatedToB() {
        var access = PlanetAccess.SHARED;
        for (var f : Face.values())
            for (var g : Face.values()) {
                var observer = GroundCoordinates.origin(f.ordinal()).add(new Point(1000, 20, 1000));
                var target = GroundCoordinates.origin(g.ordinal()).add(new Point(3, 20, 900));
                var a = PlanetAccess.LEGACY.image(observer.x(), observer.z(), target);
                var b =
                        access.image(
                                observer.x() + 32768,
                                observer.z(),
                                target.add(new Point(32768, 0, 0)));
                if (a == null) assertNull(b);
                else assertEquals(a.add(new Point(32768, 0, 0)), b);
            }
    }
}
