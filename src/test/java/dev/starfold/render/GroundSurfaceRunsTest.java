package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrainTest;

import io.netty.buffer.Unpooled;

import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;

import org.junit.jupiter.api.*;

import java.util.*;

class GroundSurfaceRunsTest {
    static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState(),
            LOG = Blocks.OAK_LOG.defaultBlockState(),
            STONE = Blocks.STONE.defaultBlockState();

    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    static GroundSurface.Run run(int top, int bottom, BlockState state) {
        return new GroundSurface.Run(top, bottom, Block.getId(state));
    }

    static GroundSurface forest() {
        var columns = new GroundSurface.Run[256][];
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                var ground = run(127, 81, STONE);
                columns[x + z * 16] =
                        x >= 12
                                ? new GroundSurface.Run[] {ground}
                                : x % 4 == 1 && z % 4 == 1
                                        ? new GroundSurface.Run[] {
                                            run(145, 143, LEAVES), run(142, 128, LOG), ground
                                        }
                                        : new GroundSurface.Run[] {
                                            run(145, 143, LEAVES), ground
                                        };
            }
        return new GroundSurface(1, columns);
    }

    @Test
    void buriedDifferencesDoNotStarveExteriorEditsButAdmittedEditsRetainFullHeight() {
        var terrain=new dev.starfold.worldgen.PlanetTerrain(42,dev.starfold.worldgen.PlanetTerrain.VERSION);
        var function=new dev.starfold.worldgen.surface.SurfaceFunction(terrain);
        var chunk=new ProtoChunk(new ChunkPos(0,0),UpgradeData.EMPTY,PlanetTerrainTest.HEIGHT,
                PlanetTerrainTest.biomes,null);
        int u=1024,v=1024;
        long key=Face.TOP.ordinal()*16384L+u/16+v/16*128L;
        for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            var blocks=function.blocks(Face.TOP,u+x,v+z);
            for(int y=-64;y<320 && y-terrain.floor()<blocks.length;y++)
                chunk.setBlockState(new BlockPos(x,y,z),blocks[y-terrain.floor()],false);
        }
        chunk.setBlockState(new BlockPos(0,-40,0),Blocks.DIAMOND_BLOCK.defaultBlockState(),false);
        Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        var capture=new GroundSurfaceCapture(chunk,function,key);
        int reads=0;
        while(!capture.done()) { int n=capture.step(17); assertTrue(n<=17); reads+=n; }
        assertTrue(capture.matchesBaseline(),"buried variation is not an exterior change");
        assertTrue(reads<65536,"unchanged columns only compare the exterior band");
        chunk.setBlockState(new BlockPos(0,300,0),Blocks.GOLD_BLOCK.defaultBlockState(),false);
        Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        capture.restart(chunk);
        while(!capture.done()) assertTrue(capture.step(17)<=17);
        assertFalse(capture.matchesBaseline());
        var surface=capture.result(1);
        assertEquals(run(300,300,Blocks.GOLD_BLOCK.defaultBlockState()),surface.run(0,0,0));
        assertEquals(-64,java.util.stream.IntStream.range(0,surface.runCount(0,0))
                .map(i->surface.run(0,0,i).bottom()).min().orElseThrow());
    }

    @Test
    void serverAndClientCaptureFullHeightTouchingTreeLayersWithReadQuotas() {
        var chunk =
                new ProtoChunk(
                        new ChunkPos(0, 0),
                        UpgradeData.EMPTY,
                        PlanetTerrainTest.HEIGHT,
                        PlanetTerrainTest.biomes,
                        null);
        for (int y = -64; y <= 145; y++)
            chunk.setBlockState(
                    new BlockPos(0, y, 0), y >= 143 ? LEAVES : y >= 128 ? LOG : STONE, false);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        var capture = new GroundSurfaceCapture(chunk);
        while (!capture.done()) assertTrue(capture.step(7) <= 7);
        var surface = capture.result(1);
        assertEquals(3, surface.runCount(0, 0));
        assertEquals(run(145, 143, LEAVES), surface.run(0, 0, 0));
        assertEquals(run(142, 128, LOG), surface.run(0, 0, 1));
        assertEquals(run(127, -64, STONE), surface.run(0, 0, 2));
        var values = new int[GroundChunkSnapshot.VALUES];
        for (int y = -64; y <= 145; y++)
            values[(y + 64) * 256] = Block.getId(chunk.getBlockState(new BlockPos(0, y, 0)));
        var snapshot = GroundChunkSnapshot.fromStates(0, 0, 0, 1, values);
        var client = GroundViewCache.surfaceImage(snapshot);
        assertTrue(surface.sameAs(client));
        var volume = new dev.starfold.worldgen.lod.VoxelColumns.Capture(-64, 320);
        while (!volume.done()) volume.step((x, y, z) -> snapshot.state(x, y, z), 8192);
        var near =
                new GroundSurface(
                        1, surface.heights(), surface.states(), volume.result().orElseThrow());
        assertTrue(surface.sameAs(near.surfaceOnly()));
        var floating =
                GroundSurfaceCapture.scan(
                        145,
                        -64,
                        y ->
                                y >= 143
                                        ? LEAVES
                                        : y == 140
                                                ? LOG
                                                : y <= 127
                                                        ? STONE
                                                        : Blocks.AIR.defaultBlockState());
        assertArrayEquals(
                new GroundSurface.Run[] {
                    run(145, 143, LEAVES), run(140, 140, LOG), run(127, -64, STONE)
                },
                floating);
        int[] reads = {0};
        var tower =
                GroundSurfaceCapture.scan(
                        300,
                        -64,
                        y -> {
                            reads[0]++;
                            return STONE;
                        });
        assertEquals(365, reads[0]);
        assertEquals(run(300, -64, STONE), tower[0]);
    }

    @Test
    void codecAndWireKeepAllLayersAndRejectInvalidLengthsCountsSlotsAndBounds() {
        var surface = forest();
        var packed = GroundSurfaceCodec.pack(surface);
        assertEquals(2570, packed.length);
        assertTrue(surface.sameAs(GroundSurfaceCodec.unpack(1, packed)));
        for (int length : new int[] {0, 774, packed.length - 1, GroundSurfaceCodec.MAX_BYTES + 1})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> GroundSurfaceCodec.unpack(1, Arrays.copyOf(packed, length)));
        int start = 2 + 3 * 4;
        for (int count : new int[] {0, 4, 255}) {
            var bad = packed.clone();
            bad[start] = (byte) count;
            assertThrows(IllegalArgumentException.class, () -> GroundSurfaceCodec.unpack(1, bad));
        }
        var bad = packed.clone();
        bad[start + 5] = (byte) 255;
        assertThrows(IllegalArgumentException.class, () -> GroundSurfaceCodec.unpack(1, bad));
        var inverted = packed.clone();
        inverted[start + 3] = 1;
        assertThrows(IllegalArgumentException.class, () -> GroundSurfaceCodec.unpack(1, inverted));
        var raw = Unpooled.buffer();
        try {
            var buffer = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            GroundViewPackets.Surface.CODEC.encode(
                    buffer, new GroundViewPackets.Surface(9, 0, surface));
            assertTrue(surface.sameAs(GroundViewPackets.Surface.CODEC.decode(buffer).image()));
            assertEquals(0, buffer.readableBytes());
        } finally {
            raw.release();
        }
    }

    @Test
    void forestCanopySurvivesStepFourButLoneTreeDropsByStepEightAndTrunksDropAtTwo() {
        var image = forest();
        var forest =
                new GroundSurfaceMip(
                        16,
                        16,
                        (x, z) -> GroundSurfaceMip.sample(null, Face.TOP, x, z, image, null));
        assertNotNull(forest.column(1, 1, 1).trunk());
        assertNull(forest.column(0, 0, 2).trunk());
        assertNotNull(forest.column(0, 0, 4).canopy());
        assertEquals(145, forest.column(0, 0, 4).canopy().top());
        assertEquals(127, forest.column(0, 0, 4).y());
        var sparse =
                new GroundSurfaceMip(
                        16,
                        16,
                        (x, z) ->
                                new GroundSurfaceMip.Column(
                                        127,
                                        STONE,
                                        x < 3 && z < 3 ? run(145, 143, LEAVES) : null,
                                        null));
        assertNull(sparse.column(0, 0, 8).canopy());
        var two =
                GroundSurfaceMip.reduce(
                        List.of(
                                new GroundSurfaceMip.Column(
                                        127, STONE, run(145, 143, LEAVES), null),
                                new GroundSurfaceMip.Column(
                                        127, STONE, run(149, 145, LEAVES), null),
                                new GroundSurfaceMip.Column(127, STONE),
                                new GroundSurfaceMip.Column(127, STONE)));
        assertEquals(run(147, 144, LEAVES), two.canopy());
    }

    @Test
    void treeMeshHasFloatingCanopyBottomAndSeparateTrunkWithoutLeafPillars() {
        var shape = PlanetShape.STANDARD;
        GroundFarMesh.Field field =
                (f, x, z, step) ->
                        x == 8 && z == 8
                                ? new GroundFarMesh.Surface(
                                        0, STONE, 0, run(145, 143, LEAVES), run(142, 128, LOG))
                                : new GroundFarMesh.Surface(0, STONE);
        var mesh = GroundFarMesh.build(shape, Face.TOP, 0, 0, 16, 1, field);
        var leaves = mesh.stream().filter(q -> q.material().equals(LEAVES)).toList();
        assertEquals(5, leaves.size()); // The touching trunk hides the shared underside.
        assertTrue(
                leaves.stream()
                        .flatMap(q -> q.points().stream())
                        .allMatch(p -> p.y() >= 15 && p.y() <= 18));
        assertFalse(leaves.stream().anyMatch(q -> q.points().stream().allMatch(p -> p.y() == 15)));
        assertEquals(4, mesh.stream().filter(q -> q.material().equals(LOG)).count());
        var coarse = GroundFarMesh.build(shape, Face.TOP, 0, 0, 16, 2, field);
        assertTrue(coarse.stream().anyMatch(q -> q.material().equals(LOG)));
    }

    @Test
    void residentBudgetFitsExpandedForestBandsIncludingFades() {
        var shape = PlanetShape.STANDARD;
        long maximum = 0;
        int surfaceBytes = forest().memoryBytes();
        for (int altitude : new int[] {0, 256, 1024, 2048}) {
            var eye =
                    shape.position(
                            new PlanetShape.Pose(
                                    Face.TOP,
                                    new dev.starfold.geometry.BentStripGeometry.Point(
                                            1024, altitude, 1024)));
            long bytes = 0;
            for (var face : Face.values())
                for (int z = 0; z < 2048; z += 16)
                    for (int x = 0; x < 2048; x += 16) {
                        int step =
                                dev.starfold.worldgen.lod.VoxelLodPolicy.step(
                                        dev.starfold.worldgen.lod.VoxelLodPolicy.distance(
                                                shape, eye, face, x, z, 0),
                                        -1);
                        if (step == 0) continue;
                        int quads = (step == 1 ? 17 : 11) * 256 / (step * step);
                        bytes += quads * 128L + surfaceBytes;
                    }
            maximum = Math.max(maximum, bytes);
        }
        System.out.printf(
                "ROUND9_RESIDENT forest_bound=%d double=%d cap=%d%n",
                maximum, 2 * maximum, dev.starfold.worldgen.lod.VoxelLodPolicy.RESIDENT_BYTES);
        // Full-sphere pixel fidelity is not a 3-GiB promise: hidden hemispheres retain coarse cover.
        assertTrue(2 * maximum > dev.starfold.worldgen.lod.VoxelLodPolicy.RESIDENT_BYTES);
        assertFalse(dev.starfold.worldgen.lod.VoxelLodPolicy.admits(maximum,128,0));
    }

    @Test
    void tallEditedStructureSurvivesCapturePersistenceAndStreamingWithItsAirGaps() {
        var chunk=new ProtoChunk(new ChunkPos(0,0),UpgradeData.EMPTY,PlanetTerrainTest.HEIGHT,PlanetTerrainTest.biomes,null);
        for(int y=-64;y<128;y++) chunk.setBlockState(new BlockPos(0,y,0),STONE,false);
        for(int y:new int[]{150,180,220,260,300})
            chunk.setBlockState(new BlockPos(0,y,0),Blocks.GOLD_BLOCK.defaultBlockState(),false);
        Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        var index=new GroundSurfaceIndex(); index.edited(chunk); index.finishPending();
        var image=index.surface(0);
        assertEquals(6,image.runCount(0,0));
        assertEquals(300,image.run(0,0,0).top());
        assertEquals(-64,image.run(0,0,5).bottom());
        var loaded=GroundSurfaceIndex.load(index.save(new net.minecraft.nbt.CompoundTag(),null),null);
        assertTrue(image.sameColumns(loaded.surface(0)));
        var raw=Unpooled.buffer();
        try {
            var buffer=new RegistryFriendlyByteBuf(raw,RegistryAccess.EMPTY);
            GroundViewPackets.Surface.CODEC.encode(buffer,new GroundViewPackets.Surface(17,0,image));
            var received=GroundViewPackets.Surface.CODEC.decode(buffer).image();
            assertTrue(image.sameAs(received)); assertFalse(buffer.isReadable());
            assertEquals(6,received.runCount(0,0));
        } finally { raw.release(); }
        var old=index.save(new net.minecraft.nbt.CompoundTag(),null); old.putInt("surface_form",1);
        assertThrows(IllegalArgumentException.class,()->GroundSurfaceIndex.load(old,null));
    }

    @Test
    void exactByteTableIncludingWidePaletteWorstCase() throws Exception {
        var columns = new GroundSurface.Run[256][];
        for (int i = 0; i < 256; i++) {
            columns[i] = new GroundSurface.Run[GroundSurfaceCodec.MAX_RUNS];
            for(int r=0;r<columns[i].length;r++) columns[i][r]=new GroundSurface.Run(300-r*3,300-r*3,i*GroundSurfaceCodec.MAX_RUNS+r);
        }
        var worst = new GroundSurface(1, columns);
        assertEquals(82178, worst.packed().length);
        assertEquals(GroundSurfaceCodec.MAX_BYTES, worst.packed().length);
        assertTrue(worst.sameAs(GroundSurfaceCodec.unpack(1, worst.packed())));
        var flat = GroundSurfaceMipTest.image(127, STONE);
        for (var entry : Map.of("flat", flat, "forest", forest(), "worst", worst).entrySet()) {
            int bytes = entry.getValue().packed().length;
            System.out.printf(
                    "ROUND9_SIZE %s chunk=%d planet=%d nbt=%d%n",
                    entry.getKey(),
                    bytes,
                    bytes * 98304L,
                    GroundGeneratedSurfaceTest.planetNbtBytes(entry.getValue().packed()));
        }
    }
}
