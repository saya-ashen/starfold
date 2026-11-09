package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;

import io.netty.buffer.Unpooled;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.configurations.BlockPileConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;

/** CPU integration through real ProtoChunk, vanilla feature, index, SavedData and wire codec. */
class GroundGeneratedSurfaceTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    private static ProtoChunk flat(int cx, int cz) {
        var chunk =
                new ProtoChunk(
                        new ChunkPos(cx, cz),
                        UpgradeData.EMPTY,
                        PlanetTerrainTest.HEIGHT,
                        PlanetTerrainTest.biomes,
                        null);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                chunk.setBlockState(
                        new BlockPos(cx * 16 + x, 127, cz * 16 + z),
                        Blocks.STONE.defaultBlockState(),
                        false);
        return chunk;
    }

    private static WorldGenLevel world(ProtoChunk chunk) {
        return (WorldGenLevel)
                Proxy.newProxyInstance(
                        WorldGenLevel.class.getClassLoader(),
                        new Class<?>[] {WorldGenLevel.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getMinBuildHeight" -> chunk.getMinBuildHeight();
                                    case "getHeight" -> chunk.getHeight();
                                    case "getBlockState" -> chunk.getBlockState((BlockPos) args[0]);
                                    case "getFluidState" -> chunk.getFluidState((BlockPos) args[0]);
                                    case "isEmptyBlock" ->
                                            chunk.getBlockState((BlockPos) args[0]).isAir();
                                    case "setBlock" -> {
                                        var pos = (BlockPos) args[0];
                                        assertEquals(chunk.getPos(), new ChunkPos(pos));
                                        chunk.setBlockState(pos, (BlockState) args[1], false);
                                        yield true;
                                    }
                                    default ->
                                            throw new UnsupportedOperationException(
                                                    method.toString());
                                });
    }

    private static int nbtBytes(CompoundTag tag) throws IOException {
        int[] count = {0};
        var sink =
                new OutputStream() {
                    @Override
                    public void write(int b) {
                        count[0]++;
                    }

                    @Override
                    public void write(byte[] b, int offset, int length) {
                        count[0] += length;
                    }
                };
        NbtIo.write(tag, new DataOutputStream(sink));
        return count[0];
    }

    /**
     * Size-only fixture: key/epoch are fixed-width longs, so sharing a record gives exact bytes.
     */
    static int planetNbtBytes(byte[] columns) throws IOException {
        var tag = new GroundSurfaceIndex().save(new CompoundTag(), null);
        long[] bits = new long[1536];
        Arrays.fill(bits, -1L);
        tag.putLongArray("coverage", bits);
        var record = new CompoundTag();
        record.putLong("key", 0);
        record.putLong("epoch", 1);
        record.putByteArray("columns", columns);
        var list = new ListTag();
        for (int i = 0; i < 98304; i++) list.add(record);
        tag.put("surfaces", list);
        return nbtBytes(tag);
    }

    private static CompoundTag diskRoundTrip(CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }

    private static void drain(GroundSurfaceIndex index, long key) {
        int ticks = 0;
        while (index.dirty(key)) {
            assertTrue(index.advance(8192, List.of(), Long.MAX_VALUE) <= 8192);
            assertTrue(++ticks < 1000);
        }
    }

    @Test
    void explicitlyMarkedFeatureSurfaceIsServedAndSurvivesRestart()
            throws Exception {
        var chunk = flat(64, 64);
        assertTrue(
                Feature.BLOCK_PILE.place(
                        new FeaturePlaceContext<>(
                                Optional.empty(),
                                world(chunk),
                                null,
                                RandomSource.create(42),
                                new BlockPos(1032, 128, 1032),
                                new BlockPileConfiguration(
                                        BlockStateProvider.simple(Blocks.GOLD_BLOCK)))));
        // A generated tree crown and a thin plant are part of generation, not index.changed calls.
        chunk.setBlockState(
                new BlockPos(1024, 145, 1024), Blocks.OAK_LEAVES.defaultBlockState(), false);
        chunk.setBlockState(
                new BlockPos(1025, 128, 1024), Blocks.SHORT_GRASS.defaultBlockState(), false);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        long key = GroundSurfaceIndex.key(64, 64);
        var index = new GroundSurfaceIndex();
        index.edited(chunk);
        assertTrue(index.coverage().get((int) key));
        assertTrue(index.dirty(key));
        drain(index, key);
        var image = index.surface(key);
        assertTrue(image.state(0, 0).is(Blocks.OAK_LEAVES));
        assertEquals(145, image.height(0, 0));
        assertTrue(image.state(1, 0).is(Blocks.STONE));
        assertEquals(127, image.height(1, 0));
        boolean gold = false;
        for (int state : image.states()) gold |= Block.stateById(state).is(Blocks.GOLD_BLOCK);
        assertTrue(gold, "real vanilla feature blocks, not procedural approximation");
        var loaded =
                GroundSurfaceIndex.load(diskRoundTrip(index.save(new CompoundTag(), null)), null);
        assertEquals(index.identity(), loaded.identity());
        assertFalse(loaded.dirty(key), "serve without loading native chunk on restart");
        assertTrue(image.sameAs(loaded.surface(key)));
        var eye =
                PlanetShape.STANDARD.position(
                        new PlanetShape.Pose(Face.TOP, new Point(1032, 250, 1032)));
        var sent = new HashMap<Long, Long>();
        var stream = new GroundSurfaceStream();
        assertEquals(key, stream.next(loaded, sent, PlanetShape.STANDARD, eye));
        var raw = Unpooled.buffer();
        try {
            var buffer = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            GroundViewPackets.Surface.CODEC.encode(
                    buffer, new GroundViewPackets.Surface(9, key, loaded.surface(key)));
            int wireBytes = buffer.readableBytes();
            var packet = GroundViewPackets.Surface.CODEC.decode(buffer);
            assertTrue(image.sameAs(packet.image()));
            assertEquals(0, buffer.readableBytes());
            var terrain = new PlanetTerrain(PlanetTerrain.TEST_SEED);
            var column =
                    GroundSurfaceMip.sample(terrain, Face.TOP, 1024, 1024, packet.image(), null);
            assertEquals(127, column.y());
            assertTrue(column.material().is(Blocks.STONE));
            assertEquals(145, column.canopy().top());
            System.out.printf(
                    "SURFACE_SIZE feature packed=%d nbt=%d wire=%d planet2048_nbt=%d%n",
                    GroundSurfaceCodec.pack(image).length,
                    nbtBytes(index.save(new CompoundTag(), null)),
                    wireBytes,
                    planetNbtBytes(GroundSurfaceCodec.pack(image)));
        } finally {
            raw.release();
        }
        // A reconnect has fresh sent/frontier state and receives the same persistent image again.
        assertEquals(
                key,
                new GroundSurfaceStream().next(loaded, new HashMap<>(), PlanetShape.STANDARD, eye));
    }

    @Test
    void ongoingBlockUpdatesCannotRestartFullHeightCaptureForever() {
        var chunk=flat(0,0);
        Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        var index=new GroundSurfaceIndex();
        index.edited(chunk);
        for(int tick=0;tick<1024 && !index.hasSurface(0);tick++) {
            assertTrue(index.advance(256,List.of(),Long.MAX_VALUE)<=256);
            chunk.setBlockState(new BlockPos(15,127,15),
                    (tick%2==0 ? Blocks.GOLD_BLOCK : Blocks.STONE).defaultBlockState(),false);
            index.edited(chunk);
        }
        assertTrue(index.hasSurface(0),"continuous ticks still publish bounded-stale coverage");
        index.finishPending();
        assertFalse(index.dirty(0));
        assertEquals(chunk.getBlockState(new BlockPos(15,127,15)),
                index.surface(0).state(15,15));
    }

    @Test
    void plantTowersAndAirColumnsAreIncrementalAndNeverBecomeFakeTerrain() {
        var chunk = flat(0, 0);
        for (int y = 128; y < 319; y++)
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    chunk.setBlockState(
                            new BlockPos(x, y, z), Blocks.SHORT_GRASS.defaultBlockState(), false);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        var capture = new GroundSurfaceCapture(chunk);
        assertEquals(17, capture.step(17));
        assertFalse(capture.done());
        assertThrows(IllegalStateException.class, () -> capture.result(1));
        while (!capture.done()) assertTrue(capture.step(8192) <= 8192);
        assertEquals(127, capture.result(1).height(15, 15));
        var index = new GroundSurfaceIndex();
        index.edited(chunk);
        assertEquals(1, index.advance(1, List.of(), Long.MAX_VALUE));
        assertNull(index.surface(0));
        // A new chunk incarnation resets the partial capture; no mixed old/new columns.
        var replacement = flat(0, 0);
        Heightmap.primeHeightmaps(replacement, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        index.edited(replacement);
        index.finishPending();
        assertEquals(127, index.surface(0).height(0, 0));
        assertFalse(index.dirty(0));
    }

    @Test
    void paletteStorageBoundAndWireRejectMalformedRecords() throws Exception {
        var heights = new int[256];
        Arrays.fill(heights, 127);
        var states = new int[256];
        for (int i = 0; i < 256; i++) states[i] = i;
        var worst = new GroundSurface(1, heights, states);
        var packed = GroundSurfaceCodec.pack(worst);
        assertEquals(2562, packed.length);
        assertTrue(worst.sameAs(GroundSurfaceCodec.unpack(1, packed)));
        Arrays.fill(states, Block.getId(Blocks.STONE.defaultBlockState()));
        assertEquals(
                1542,
                GroundSurfaceCodec.pack(new GroundSurface(1, heights, states)).length);
        assertThrows(
                IllegalArgumentException.class,
                () -> GroundSurfaceCodec.unpack(1, new byte[GroundSurfaceCodec.MAX_BYTES + 1]));
        var badSlot = GroundSurfaceCodec.pack(new GroundSurface(1, heights, states));
        badSlot[11] = (byte) 255;
        assertThrows(IllegalArgumentException.class, () -> GroundSurfaceCodec.unpack(1, badSlot));
        var badState = packed.clone();
        Arrays.fill(badState, 2, 6, (byte) 255);
        assertThrows(IllegalArgumentException.class, () -> GroundSurfaceCodec.unpack(1, badState));
        var raw = Unpooled.buffer();
        try {
            var buffer = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            buffer.writeLong(1);
            buffer.writeVarLong(0);
            buffer.writeVarLong(1);
            buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(
                    RuntimeException.class, () -> GroundViewPackets.Surface.CODEC.decode(buffer));
        } finally {
            raw.release();
        }
        int singleNbt =
                planetNbtBytes(
                        GroundSurfaceCodec.pack(new GroundSurface(1, heights, states)));
        int worstNbt = planetNbtBytes(packed);
        assertTrue(worstNbt < 2620L * 98304 + 13000);
        System.out.printf(
                "SURFACE_SIZE packed single=1542 palette256=2562 planet2048_single=%d"
                    + " planet2048_palette256=%d nbt_single=%d nbt_palette256=%d%n",
                1542L * 98304, 2562L * 98304, singleNbt, worstNbt);
    }

    @Test
    void frontierUsesCameraDistanceSkipsKnownAndReplaysLateReadyUpdates() {
        var index = new GroundSurfaceIndex();
        for (int x : new int[] {1, 64}) {
            var chunk = flat(x, 64);
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
            index.edited(chunk);
        }
        index.finishPending();
        var eye =
                PlanetShape.STANDARD.position(
                        new PlanetShape.Pose(Face.TOP, new Point(1032, 200, 1032)));
        long near = GroundSurfaceIndex.key(64, 64);
        var stream = new GroundSurfaceStream();
        var sent = new HashMap<Long, Long>();
        assertEquals(
                near, stream.next(index, sent, PlanetShape.STANDARD, eye), "not lowest key first");
        sent.put(near, index.surfaceRevision(near));
        assertNotEquals(near, stream.next(index, sent, PlanetShape.STANDARD, eye));
        // Capture completion after the frontier passed a tile wakes that tile immediately.
        var chunk = flat(64, 64);
        chunk.setBlockState(
                new BlockPos(1024, 210, 1024), Blocks.GOLD_BLOCK.defaultBlockState(), false);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        index.edited(chunk);
        index.finishPending();
        assertEquals(near, stream.next(index, sent, PlanetShape.STANDARD, eye));
    }

    @Test
    void remoteRetainsAcknowledgedColumnsAndSeparatesSavedRevisionsFromSnapshotTicks() {
        var profile =
                SurfaceProfiles.current(new dev.starfold.universe.PlanetPackets.Profile(
                        9, PlanetTerrain.TEST_SEED, PlanetTerrain.VERSION));
        var marked = new BitSet();
        marked.set(0, 4097);
        var remote = new PlanetRemoteView();
        var heights = new int[256];
        var states = new int[256];
        Arrays.fill(heights, 127);
        Arrays.fill(states, Block.getId(Blocks.STONE.defaultBlockState()));
        remote.own(
                profile,
                0,
                marked,
                Map.of(0L, new GroundSurface(100000, heights, states)));
        remote.summary(
                new PlanetRemotePackets.Summary(
                        0, profile, 5000, marked.toLongArray(), new long[0]));
        var served = new GroundSurface(5, heights, states);
        for (long key = 0; key < 4097; key++)
            assertTrue(
                    remote.surface(
                            new PlanetRemotePackets.Surface(
                                    0, new GroundViewPackets.Surface(9, key, served))));
        assertEquals(4097, remote.size());
        assertNotNull(remote.snapshot().surfaces().get(0L));
        assertFalse(
                remote.surface(
                        new PlanetRemotePackets.Surface(
                                0, new GroundViewPackets.Surface(9, 0, served))));
        var unknown = new BitSet();
        unknown.set(0);
        remote.summary(
                new PlanetRemotePackets.Summary(
                        0, profile, 5001, marked.toLongArray(), unknown.toLongArray()));
        assertFalse(
                remote.surface(
                        new PlanetRemotePackets.Surface(
                                0, new GroundViewPackets.Surface(9, 0, served))));
        remote.summary(
                new PlanetRemotePackets.Summary(
                        0, profile, 5002, marked.toLongArray(), new long[0]));
        assertTrue(
                remote.surface(
                        new PlanetRemotePackets.Surface(
                                0, new GroundViewPackets.Surface(9, 0, served))));
    }

    @Test
    void asyncSaveSnapshotsAreImmutableAndFailuresRemainRetryable() throws Exception {
        var dir =
                java.nio.file.Files.createTempDirectory(
                        java.nio.file.Path.of("build"), "surface-save-");
        var file = dir.resolve("index.dat");
        var chunk = flat(0, 0);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        var index = new GroundSurfaceIndex();
        index.edited(chunk);
        index.finishPending();
        var gate = new java.util.concurrent.CountDownLatch(1);
        net.neoforged.neoforge.common.IOUtilities.withIOWorker(
                () -> {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
        try {
            index.save(file.toFile(), null);
            assertFalse(index.isDirty());
            chunk.setBlockState(
                    new BlockPos(0, 230, 0), Blocks.GOLD_BLOCK.defaultBlockState(), false);
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
            index.edited(chunk);
            index.finishPending();
            assertTrue(index.isDirty());
        } finally {
            gate.countDown();
        }
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        var first =
                GroundSurfaceIndex.load(
                        NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
                                .getCompound("data"),
                        null);
        assertEquals(127, first.surface(0).height(0, 0));
        index.save(dir.resolve("missing").resolve("index.dat").toFile(), null);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        assertTrue(index.isDirty(), "I/O failure must remain retryable");
        index.save(file.toFile(), null);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        var latest =
                GroundSurfaceIndex.load(
                        NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
                                .getCompound("data"),
                        null);
        assertEquals(230, latest.surface(0).height(0, 0));
        assertFalse(index.isDirty());
        var old = latest.save(new CompoundTag(), null);
        old.putInt("format", 1);
        assertThrows(IllegalArgumentException.class, () -> GroundSurfaceIndex.load(old, null));
    }

    @Test
    void asyncRestoreIsBoundedAndDoesNotOverwriteNewlyCapturedChunks() throws Exception {
        var dir =
                java.nio.file.Files.createTempDirectory(
                        java.nio.file.Path.of("build"), "surface-restore-");
        var file = dir.resolve("index.dat");
        var chunk = flat(0, 0);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        var original = new GroundSurfaceIndex();
        original.edited(chunk);
        original.finishPending();
        original.save(file.toFile(), null);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        var restored = new GroundSurfaceIndex();
        restored.restoreFrom(file);
        assertFalse(restored.ready());
        assertFalse(restored.isDirty(), "never overwrite the old index while it is still loading");
        assertEquals(
                -1,
                new GroundSurfaceStream()
                        .next(
                                restored,
                                new HashMap<>(),
                                PlanetShape.STANDARD,
                                new Point(0, PlanetShape.STANDARD.radius() + 200, 0)));
        chunk.setBlockState(new BlockPos(0, 240, 0), Blocks.GOLD_BLOCK.defaultBlockState(), false);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        restored.edited(chunk);
        while (restored.dirty(0))
            assertTrue(restored.advance(8192, List.of(), Long.MAX_VALUE) <= 8192);
        assertNull(restored.surface(0));
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!restored.ready()) {
            assertTrue(restored.restore(1, Long.MAX_VALUE) <= 1);
            assertTrue(System.nanoTime() < deadline);
            Thread.sleep(1);
        }
        assertEquals(240, restored.surface(0).height(0, 0));
        assertTrue(restored.coverage().get(0));
        var badFile = dir.resolve("bad.dat");
        var bad = new CompoundTag();
        var invalid = original.save(new CompoundTag(), null);
        invalid.putInt("format", 1);
        bad.put("data", invalid);
        NbtIo.writeCompressed(bad, badFile);
        byte[] before = java.nio.file.Files.readAllBytes(badFile);
        var failed = new GroundSurfaceIndex();
        failed.restoreFrom(badFile);
        failed.edited(chunk);
        failed.finishPending();
        assertFalse(
                failed.isDirty(), "an unreadable index must not be overwritten with a partial one");
        failed.save(badFile.toFile(), null);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(badFile));
    }

    @Test
    void lifecycleIsServerOnlyNonLoadingAndHasNoLegacyReaderOrRegionScan() throws Exception {
        var source = Sources.text("GroundSurfaceIndex");
        assertFalse(source.contains("ChunkEvent.Loadevent"));
        assertTrue(source.contains("instanceofServerLevellevel"));
        assertTrue(source.contains("ServerStoppingEventevent"));
        assertFalse(source.contains("ChunkDataEvent.Saveevent"));
        assertFalse(source.contains("ChunkStatus.FEATURES"));
        assertTrue(Sources.text("PlanetRemoteServer").contains("v.surfaceStream.retry(key)"));
        assertTrue(source.contains("getChunkNow("),"only non-loading lookup resumes persisted dirty edits");
        assertFalse(source.contains(".getChunk("));
        assertFalse(source.contains("resolve(\"region\")"));
        assertFalse(source.contains("Files.list"));
        assertTrue(source.contains("CompletableFuture.supplyAsync"));
        assertTrue(source.contains("if(!loading.isDone())return0"));
        assertFalse(source.contains("GroundVoxelCodec.load"));
        assertTrue(source.contains("tag.getInt(\"format\")!=FORMAT"));
    }
}
