package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.*;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.worldgen.PlanetTerrainTest;

import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

class GroundFarReadinessTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    static GroundCoverSnapshot empty() {
        return GroundCoverSnapshot.parent(null, new BitSet());
    }

    static dev.starfold.worldgen.PlanetTerrain terrain() {
        return new dev.starfold.worldgen.PlanetTerrain(42,dev.starfold.worldgen.PlanetTerrain.VERSION,new dev.starfold.worldgen.PlanetGeneration("rocky",dev.starfold.worldgen.PlanetGeneration.SURFACE_VERSION,dev.starfold.worldgen.PlanetGeneration.defaults(dev.starfold.worldgen.PlanetTerrain.VERSION).recipe(),512,191,"core_shell",true,false,false,dev.starfold.worldgen.PlanetEnvironment.defaults(false,512)));
    }

    static GroundBaseCache.Entry install(dev.starfold.worldgen.PlanetTerrain terrain) throws Exception {
        GroundBaseCache.clear();
        var entry = new GroundBaseCache.Entry(terrain);
        var field = GroundBaseCache.class.getDeclaredField("planets");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var planets = (Map<dev.starfold.worldgen.surface.SurfaceKey, GroundBaseCache.Entry>) field.get(null);
        planets.put(dev.starfold.worldgen.surface.SurfaceKey.of(terrain), entry);
        return entry;
    }

    @AfterEach void clearBase() { GroundBaseCache.clear(); }

    @Test void initialCoverageUsesVoxelColumnsWithoutOptionalJobs() throws Exception {
        var terrain = terrain(); var entry = install(terrain);
        var cache = new GroundMipCache();
        var field = cache.initialField(terrain);
        for (var face : Face.VALUES) {
            for (int step : new int[] {16, 32, 64}) {
                var expected = dev.starfold.worldgen.lod.VoxelColumn.reduce(List.of(
                        dev.starfold.worldgen.lod.VoxelColumn.visible(
                                entry.function, face, step / 2, step / 2)), step);
                assertEquals(expected, field.cell(face, 0, 0, step).column(),
                        "startup sampling must match its mesh level on " + face + " at L" + step);
                assertFalse(GroundFarMesh.build(terrain.shape(), face.ordinal()*64, step, field).isEmpty());
            }
        }
        cache.field(terrain.shape(), terrain, empty(), 0);
        assertTrue(cache.poll().isEmpty());
    }

    @Test void voxelPublicationsInvalidateOnlyConsumersAndEdgeHalosOnce() throws Exception {
        var terrain=terrain(); var entry=install(terrain); var cache=new GroundMipCache();
        cache.initialField(terrain);
        entry.ready.set(33,1); // Interior 64-block tile and all four halos belong to patch zero.
        assertEquals(BitSet.valueOf(new long[]{1}),cache.poll());
        assertTrue(cache.poll().isEmpty());
        entry.cache.fill(entry.function,1,Face.TOP,0,0,p->entry.ready.set(p,1));
        var changed=cache.poll();
        var expected=new BitSet(); for(int p=0;p<384;p++) if(GroundViewPolicy.exists(terrain.shape(),p)) expected.set(p);
        assertEquals(expected,changed);
        assertTrue(cache.poll().isEmpty());
        assertEquals(entry.cache.retainedBytes(),cache.coarseBytes());
        assertTrue(cache.coarseBytes()>0);
    }

    @Test void capturedUpdatesFreezeOldFieldsRejectOlderRevisionsAndRemoveEdits() throws Exception {
        var terrain=terrain(); install(terrain); var cache=new GroundMipCache();
        var shape=terrain.shape(); var gold=Blocks.GOLD_BLOCK.defaultBlockState();
        var image=GroundSurfaceMipTest.image(200,gold);
        var old=cache.field(shape,terrain,empty(),0);
        var newer=new GroundSurface(2,image.heights(),image.states());
        var ready=cache.field(shape,terrain,new GroundCoverSnapshot(null,Map.of(0L,newer),new BitSet()),0);
        assertEquals(gold,ready.cell(Face.TOP,0,0,1).material());
        assertEquals(201,ready.cell(Face.TOP,0,0,1).column().runs().getLast().top());
        assertNotEquals(gold,old.cell(Face.TOP,0,0,1).material());
        assertEquals(BitSet.valueOf(new long[]{1}),cache.poll());
        assertEquals(BitSet.valueOf(new long[]{1}),cache.polledOverlays());
        assertTrue(cache.poll().isEmpty());
        var stale=cache.field(shape,terrain,new GroundCoverSnapshot(null,Map.of(0L,GroundSurfaceMipTest.image(127,Blocks.STONE.defaultBlockState())),new BitSet()),0);
        assertEquals(gold,stale.cell(Face.TOP,0,0,1).material());
        assertTrue(cache.poll().isEmpty());
        var reverted=cache.field(shape,terrain,empty(),0);
        assertEquals(old.cell(Face.TOP,0,0,1).column(),reverted.cell(Face.TOP,0,0,1).column());
        assertEquals(gold,ready.cell(Face.TOP,0,0,1).material());
        assertEquals(BitSet.valueOf(new long[]{1}),cache.poll());
    }

    @Test void packedFineBudgetEvictsAndRegeneratesWithoutDroppingCoarse() throws Exception {
        var terrain=terrain(); var entry=install(terrain); var cache=new GroundMipCache();
        entry.cache.fill(entry.function,1,Face.TOP,0,0,p->entry.ready.set(p,1));
        var field=cache.field(terrain.shape(),terrain,empty(),0);
        var first=field.cell(Face.TOP,32,32,1).column();
        var payload=entry.fine.values().iterator().next();
        long coarse=cache.coarseBytes();
        entry.finePayload=GroundBaseCache.Entry.FINE_LIMIT;
        field.cell(Face.TOP,33,32,1);
        assertFalse(entry.fine.containsValue(payload));
        assertTrue(cache.fineBytes()<=GroundBaseCache.Entry.FINE_LIMIT);
        assertEquals(first,field.cell(Face.TOP,32,32,1).column());
        assertEquals(coarse,cache.coarseBytes());
        cache.clear(); assertEquals(0,cache.fineBytes()); assertEquals(0,cache.coarseBytes());
    }

    @Test void equivalentTerrainReusesBaseAndNullTerrainCannotStartMeshing() throws Exception {
        var terrain=terrain(); var entry=install(terrain); var cache=new GroundMipCache();
        cache.initialField(terrain);
        assertSame(entry,GroundBaseCache.get(terrain(),null));
        assertThrows(IllegalStateException.class,()->cache.initialField(null));
    }

    @Test
    void visibleMeshesPrecedeRefinementsAndCancelledWorkLeavesTheQueue() throws Exception {
        try (var pool = new GroundMeshScheduler(1)) {
            var running = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var blocker =
                    pool.submit(
                            () -> {
                                running.countDown();
                                release.await(5, TimeUnit.SECONDS);
                                return 0;
                            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            var order = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
            var refinement = pool.submitRefinement(() -> { order.add(3); return 3; });
            assertFalse(pool.canSubmit(true));
            assertTrue(pool.canSubmit(false));
            var visible =
                    pool.submit(
                            () -> {
                                order.add(1);
                                return 1;
                            });
            var cancelled = pool.submitRefinement(() -> 4);
            cancelled.cancel(false);
            assertEquals(2, pool.getQueue().size());
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            visible.get(5, TimeUnit.SECONDS);
            refinement.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1, 3), order);
            pool.readyBytes(2L * 1024 * 1024);
            assertFalse(pool.canSubmit(true));
            assertTrue(pool.canSubmit(false));
            pool.readyBytes(4L * 1024 * 1024);
            assertFalse(pool.canSubmit(false));
            pool.readyBytes(0);
            assertTrue(pool.canSubmit(true));
        }
    }

    @Test
    void liveLookupIsBoundedAndNeverRetainedByTheWorker() throws Exception {
        var terrain=terrain(); install(terrain);
        var cache = new GroundMipCache();
        var open = new java.util.concurrent.atomic.AtomicBoolean(true);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var field =
                cache.field(
                        terrain.shape(),
                        terrain,
                        k -> {
                            assertTrue(open.get(), "worker must not use a live capture lookup");
                            reads.incrementAndGet();
                            return null;
                        },
                        0);
        assertTrue(reads.get() <= 5 * 256);
        open.set(false);
        cache.poll();
        assertFalse(GroundFarMesh.build(terrain.shape(), 0, 64, field).isEmpty());
        cache.clear();
    }

    @Test
    void schedulingAndWorkersUseOneDataPathWithoutTouchingNearOwnership() throws Exception {
        for (String name :
                List.of("GroundMipCache", "GroundFarMesh")) {
            var src = Sources.text(name);
            for (String forbidden :
                    List.of(
                            "PlanetHeightCache",
                            "GroundViewClient",
                            "Minecraft.getInstance",
                            "GroundAlbedo",
                            "RenderSystem"))
                assertFalse(src.contains(forbidden), name + ": " + forbidden);
        }
        var renderer = Sources.text("GroundRenderer");
        assertTrue(renderer.contains("lodWorkers.submitFirst(()->GroundFarMesh.build(observed"));
        for (String obsolete :
                List.of(
                        "GroundLodMesh",
                        "GroundLodClosure",
                        "GroundLodFallback",
                        "GroundFarPyramid",
                        "pyramidWorker",
                        "GroundFarMesh.field(")) assertFalse(renderer.contains(obsolete), obsolete);
        int start = renderer.indexOf("privatestaticvoidupdateCover(");
        var completion =
                renderer.substring(
                        start, renderer.indexOf("if(GroundViewClient.coverageReady()", start));
        assertTrue(completion.contains("farVersions[p]++"));
        assertTrue(completion.contains("dirty.patch(p)"));
        for (String forbidden :
                List.of("dirty.tile(", "dirtyDetails", "ownership", "window", ".close()"))
            assertFalse(completion.contains(forbidden));
        assertTrue(
                Sources.text("RemotePlanetMeshes")
                        .contains("pyramidDirty.stream().forEach(p->inputs[p]=null)"));
    }
}
