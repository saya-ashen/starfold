package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.*;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelColumn;

import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

class GroundMipSnapshotTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    private PlanetTerrain terrain;
    private GroundBaseCache.Entry base;
    private GroundMipCache cache;

    @BeforeEach
    void prepare() throws Exception {
        terrain = GroundFarReadinessTest.terrain();
        base = GroundFarReadinessTest.install(terrain);
        cache = new GroundMipCache();
    }

    @AfterEach
    void clear() {
        cache.clear();
        GroundBaseCache.clear();
    }

    @Test
    void transientCoarseStartupIsNeverCachedAsFinishedGeometry() {
        var captures = new HashMap<Long, GroundSurface>();
        captures.put(0L, image(1, true));
        var first = field(captures, 0, 0);
        assertFalse(base.cache.complete());
        assertNull(first.cacheInputs(4)); assertNull(first.cacheInputs(64));
        assertNotNull(first.cacheInputs(1)); assertNotNull(first.cacheInputs(2));
        assertEquals(first.cacheInputs(1), first.cacheLookupInputs(64),
                "a verified final packed entry can restore without waiting for coarse base IO");
        assertNull(first.cacheInputs(64), "a miss must still never save transient midpoint geometry");
        captures.put(0L, image(2, false));
        var edited = field(captures, 1, 0);
        assertNotEquals(first.cacheInputs(1), edited.cacheInputs(1));
        assertNotEquals(first.cacheLookupInputs(64), edited.cacheLookupInputs(64));
        assertEquals(first.cacheInputs(1), GroundMeshCache.fingerprint(Map.of(0L, image(99, true))),
                "immutable worker snapshot and content identity, not a later mutable provider or revision");
    }

    @Test
    void authoritativeOverlayCaptureDoesNotReinvalidateItsJustAdmittedQuadtreeTicket() {
        var captures = new HashMap<Long, GroundSurface>();
        field(captures, 0, 0);
        cache.pollBase();
        captures.put(0L, image(1, true));
        field(captures, 1, 0);
        assertTrue(cache.pollBase().isEmpty(), "Surface receipt already invalidated the quadtree");
        assertTrue(cache.polledOverlays().get(0));
        captures.put(0L, image(2, false));
        field(captures, 2, 0);
        assertTrue(cache.poll().get(0), "remote cohort callers retain combined poll semantics");
    }

    private GroundFarMesh.Field field(Map<Long, GroundSurface> captures, long version, int patch) {
        return cache.field(terrain.shape(), terrain, captures, version, patch);
    }

    private static GroundSurface image(long revision, boolean gold) {
        var image =
                GroundSurfaceMipTest.image(
                        210, (gold ? Blocks.GOLD_BLOCK : Blocks.DIAMOND_BLOCK).defaultBlockState());
        return new GroundSurface(revision, image.heights(), image.states());
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, GroundSurface> inputs(GroundFarMesh.Field field) throws Exception {
        var member = field.getClass().getDeclaredField("near");
        member.setAccessible(true);
        return (Map<Long, GroundSurface>) member.get(field);
    }

    private static Object derived(GroundFarMesh.Field field) throws Exception {
        var member = field.getClass().getDeclaredField("derived");
        member.setAccessible(true);
        return member.get(field);
    }

    private static final class CountingCaptures extends HashMap<Long, GroundSurface> {
        int reads;
        boolean open = true;

        @Override
        public GroundSurface get(Object key) {
            assertTrue(open, "a worker must never query the mutable provider");
            reads++;
            return super.get(key);
        }

        @Override
        public Set<Map.Entry<Long, GroundSurface>> entrySet() {
            throw new AssertionError("capture must not copy or scan the entire provider");
        }
    }

    @Test
    void residentJobsReuseBoundedImmutableInputsAndNeverTheMutableDerivedMap() throws Exception {
        var captures = new CountingCaptures();
        captures.put(0L, image(2, true));
        long distant = GroundTileKeys.key(terrain.shape(), Face.BOTTOM.ordinal(), 256, 256);
        captures.put(distant, image(2, false));
        // First retain an unrelated patch, to catch accidental copies of the accumulated edited
        // map.
        field(captures, 1, GroundViewPolicy.patch(distant));
        captures.reads = 0;
        var first = field(captures, 1, 0);
        int reads = captures.reads;
        assertTrue(reads > 0 && reads <= 576);
        assertEquals(Set.of(0L), inputs(first).keySet());
        assertThrows(UnsupportedOperationException.class, () -> inputs(first).clear());
        for (int i = 0; i < 32; i++) {
            var next = field(captures, 1, 0);
            assertNotSame(first, next);
            assertSame(inputs(first), inputs(next));
            assertNotSame(derived(first), derived(next));
        }
        assertEquals(
                reads, captures.reads, "same patch/version must perform no more capture lookups");
        captures.open = false;
        assertEquals(
                Blocks.GOLD_BLOCK.defaultBlockState(), first.cell(Face.TOP, 0, 0, 2).material());
        captures.open = true;
        captures.put(0L, image(3, false));
        var changed = field(captures, 2, 0);
        assertTrue(captures.reads > reads);
        assertEquals(
                Blocks.DIAMOND_BLOCK.defaultBlockState(),
                changed.cell(Face.TOP, 0, 0, 1).material());
        assertEquals(
                Blocks.GOLD_BLOCK.defaultBlockState(), first.cell(Face.TOP, 0, 0, 1).material());
    }

    @Test
    void newerIdenticalRevisionAndRemovalBothPreventStaleRollback() {
        var captures = new HashMap<Long, GroundSurface>();
        captures.put(0L, image(2, true));
        var original = field(captures, 0, 0);
        assertEquals(BitSet.valueOf(new long[] {1}), cache.poll());
        captures.put(0L, image(8, true));
        field(captures, 1, 0);
        assertTrue(cache.poll().isEmpty(), "notification-only revision must not dirty geometry");
        captures.put(0L, image(5, false));
        assertEquals(
                Blocks.GOLD_BLOCK.defaultBlockState(),
                field(captures, 2, 0).cell(Face.TOP, 0, 0, 1).material());
        assertTrue(cache.poll().isEmpty());
        captures.clear();
        var removed = field(captures, 3, 0);
        assertEquals(BitSet.valueOf(new long[] {1}), cache.poll());
        captures.put(0L, image(8, true));
        var stale = field(captures, 4, 0);
        assertNotEquals(
                Blocks.GOLD_BLOCK.defaultBlockState(), stale.cell(Face.TOP, 0, 0, 1).material());
        assertEquals(
                removed.cell(Face.TOP, 0, 0, 1).column(), stale.cell(Face.TOP, 0, 0, 1).column());
        assertTrue(cache.poll().isEmpty());
        captures.put(0L, image(10, false));
        assertEquals(
                Blocks.DIAMOND_BLOCK.defaultBlockState(),
                field(captures, 5, 0).cell(Face.TOP, 0, 0, 1).material());
        assertEquals(
                Blocks.GOLD_BLOCK.defaultBlockState(), original.cell(Face.TOP, 0, 0, 1).material());
    }

    @Test
    void removalAtMaximumRevisionDoesNotOverflowItsWatermark() {
        var captures = new HashMap<Long, GroundSurface>();
        captures.put(0L, image(Long.MAX_VALUE, true));
        field(captures, 0, 0);
        captures.clear();
        field(captures, 1, 0);
        captures.put(0L, image(2, false));
        assertNotEquals(
                Blocks.DIAMOND_BLOCK.defaultBlockState(),
                field(captures, 2, 0).cell(Face.TOP, 0, 0, 1).material());
    }

    @Test
    void providerIdentityAndBaseReplacementInvalidateReuseEvenAtTheSameVersion() throws Exception {
        var captures = new CountingCaptures();
        captures.put(0L, image(20, true));
        var first = field(captures, 7, 0);
        var replacement = new CountingCaptures();
        replacement.put(0L, image(1, false));
        var second = field(replacement, 7, 0);
        assertNotSame(inputs(first), inputs(second));
        assertEquals(
                Blocks.DIAMOND_BLOCK.defaultBlockState(),
                second.cell(Face.TOP, 0, 0, 1).material());
        GroundFarReadinessTest.install(terrain);
        int reads = replacement.reads;
        var rebuilt = field(replacement, 7, 0);
        assertTrue(replacement.reads > reads);
        assertNotSame(inputs(second), inputs(rebuilt));
        cache.clear();
        reads = replacement.reads;
        field(replacement, 7, 0);
        assertTrue(replacement.reads > reads);
    }

    @Test
    void baseReadinessDoesNotRescanCapturesOrReuseDerivedColumns() throws Exception {
        var captures = new CountingCaptures();
        captures.put(0L, image(2, true));
        var first = field(captures, 4, 0);
        first.cell(Face.TOP, 0, 0, 2);
        cache.poll();
        base.ready.set(33, 1);
        assertEquals(BitSet.valueOf(new long[] {1}), cache.poll());
        assertTrue(
                cache.polledOverlays().isEmpty(),
                "base readiness is independent of capture versions");
        int reads = captures.reads;
        var next = field(captures, 4, 0);
        assertEquals(reads, captures.reads);
        assertSame(inputs(first), inputs(next));
        assertNotSame(derived(first), derived(next));
        assertTrue(((Map<?, ?>) derived(next)).isEmpty());
        assertTrue(cache.poll().isEmpty());
    }

    @Test
    void latchedCohortsStayIndependentOfLiveWatermarksAndEachOther() throws Exception {
        var old = new GroundCoverSnapshot(null, Map.of(0L, image(2, true)), new BitSet());
        var newer = new GroundCoverSnapshot(null, Map.of(0L, image(8, false)), new BitSet());
        var captures = new HashMap<Long, GroundSurface>();
        captures.put(0L, image(20, false));
        var live = field(captures, 1, 0);
        cache.poll();
        var latched = cache.field(terrain.shape(), terrain, old, 0);
        var repeat = cache.field(terrain.shape(), terrain, old, 0);
        assertSame(inputs(latched), inputs(repeat));
        assertEquals(
                Blocks.GOLD_BLOCK.defaultBlockState(), latched.cell(Face.TOP, 0, 0, 1).material());
        assertEquals(
                Blocks.DIAMOND_BLOCK.defaultBlockState(),
                cache.field(terrain.shape(), terrain, newer, 0).cell(Face.TOP, 0, 0, 1).material());
        assertEquals(
                Blocks.GOLD_BLOCK.defaultBlockState(),
                cache.field(terrain.shape(), terrain, old, 0).cell(Face.TOP, 0, 0, 1).material());
        assertSame(inputs(live), inputs(field(captures, 1, 0)));
        assertTrue(
                cache.poll().isEmpty(),
                "reading a frozen cohort must not mutate live invalidations");
        captures.clear();
        field(captures, 2, 0);
        assertEquals(
                Blocks.GOLD_BLOCK.defaultBlockState(), latched.cell(Face.TOP, 0, 0, 1).material());
    }

    @Test
    void fullSixtyFourBlockHaloSurvivesEveryFaceEdgeAndCorner() throws Exception {
        var shape = terrain.shape();
        var gold = image(2, true);
        var captures = new HashMap<Long, GroundSurface>();
        for (var face : Face.VALUES)
            for (int z0 : new int[] {0, shape.size() - 256})
                for (int x0 : new int[] {0, shape.size() - 256}) {
                    var expected = new HashSet<Long>();
                    for (int z = z0 - 64; z < z0 + 320; z += 16)
                        for (int x = x0 - 64; x < x0 + 320; x += 16) {
                            var at = shape.topology().canonical(new Cell(face, x, 0, z)).cell();
                            long key =
                                    GroundTileKeys.key(shape, at.face().ordinal(), at.u(), at.v());
                            expected.add(key);
                            captures.put(key, gold);
                        }
                    int patch =
                            GroundViewPolicy.patch(
                                    GroundTileKeys.key(shape, face.ordinal(), x0, z0));
                    var field = field(captures, patch, patch);
                    assertEquals(
                            expected,
                            inputs(field).keySet(),
                            "complete canonical halo for " + face + ":" + patch);
                    for (int z : new int[] {z0 - 64, z0 + 256, z0 + 304})
                        for (int x : new int[] {x0 - 64, x0 + 256, x0 + 304}) {
                            var at = shape.topology().canonical(new Cell(face, x, 0, z)).cell();
                            assertEquals(
                                    Blocks.GOLD_BLOCK.defaultBlockState(),
                                    field.cell(at.face(), at.u(), at.v(), 1).material());
                        }
                }
    }

    @Test
    void workersCanDeriveTheSameSharedInputsConcurrently() throws Exception {
        var captures = new CountingCaptures();
        captures.put(0L, image(2, true));
        var first = field(captures, 1, 0);
        var second = field(captures, 1, 0);
        assertSame(inputs(first), inputs(second));
        captures.open = false;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<List<VoxelColumn>>>();
            for (var field : List.of(first, second))
                tasks.add(
                        workers.submit(
                                () -> {
                                    assertTrue(start.await(5, TimeUnit.SECONDS));
                                    var columns = new ArrayList<VoxelColumn>();
                                    for (int i = 0; i < 128; i++)
                                        columns.add(
                                                field.cell(Face.TOP, i % 8 * 2, i / 8 % 8 * 2, 2)
                                                        .column());
                                    return columns;
                                }));
            start.countDown();
            assertEquals(
                    tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS));
        }
        assertNotSame(derived(first), derived(second));
    }
}
