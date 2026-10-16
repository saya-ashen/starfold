package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.SourceText;
import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.render.GroundChunkSnapshot;
import dev.starfold.render.GroundShaderSource;
import dev.starfold.render.GroundStreaming;
import dev.starfold.render.GroundViewCache;
import dev.starfold.render.GroundViewClient;
import dev.starfold.render.GroundViewPackets;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.*;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

class GroundViewRuntimeTest {
    @BeforeAll
    static void registry() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void subscriptionWireRoundtripAndBounds() {
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var b = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            var packet =
                    new GroundViewPackets.Request(
                            123,
                            List.of(
                                    new GroundViewPackets.Tile(0, -1),
                                    new GroundViewPackets.Tile(98303, 999)));
            GroundViewPackets.Request.CODEC.encode(b, packet);
            assertEquals(packet, GroundViewPackets.Request.CODEC.decode(b));
            assertEquals(0, b.readableBytes());
        } finally {
            raw.release();
        }
        assertThrows(IllegalArgumentException.class, () -> new GroundViewPackets.Tile(98304, 0));
        assertThrows(IllegalArgumentException.class, () -> new GroundViewPackets.Tile(0, -2));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundViewPackets.Request(0, List.of(new GroundViewPackets.Tile(0, 0))));
        assertThrows(
                IllegalArgumentException.class, () -> new GroundViewPackets.Request(1, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundViewPackets.Request(
                                1,
                                List.of(
                                        new GroundViewPackets.Tile(0, 0),
                                        new GroundViewPackets.Tile(0, 1))));
    }

    @Test
    void actualStateImageWireKeepsPropertiesAndUnknownCoverage() {
        int[] states = new int[GroundChunkSnapshot.VALUES];
        Arrays.fill(states, Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState()));
        var stair =
                Blocks.OAK_STAIRS
                        .defaultBlockState()
                        .setValue(
                                net.minecraft.world.level.block.state.properties
                                        .BlockStateProperties.HORIZONTAL_FACING,
                                net.minecraft.core.Direction.WEST);
        states[123] = Block.BLOCK_STATE_REGISTRY.getId(stair);
        var image = GroundChunkSnapshot.fromStates(5, 127, 127, 20, states);
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var b = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            var packet = new GroundViewPackets.Image(44, image);
            GroundViewPackets.Image.CODEC.encode(b, packet);
            assertTrue(b.readableBytes() < 1_000_000);
            var decoded = GroundViewPackets.Image.CODEC.decode(b);
            assertEquals(44, decoded.session());
            assertEquals(image.fingerprint(), decoded.chunk().fingerprint());
            assertEquals(stair, decoded.chunk().state(11, -64, 7));
            var cache = new GroundViewCache(key -> {});
            assertTrue(cache.accept(decoded.chunk()));
            assertNull(cache.canonical(new net.minecraft.core.BlockPos(0, 128, 0)));
            assertEquals(
                    stair,
                    cache.canonical(
                            new net.minecraft.core.BlockPos(
                                    5 * 4096 + 127 * 16 + 11, -64, 127 * 16 + 7)));
        } finally {
            raw.release();
        }
    }

    @Test
    void maliciousWireCountRefusedBeforeAllocatingList() {
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var b = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            b.writeLong(1);
            b.writeVarInt(Integer.MAX_VALUE);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> GroundViewPackets.Request.CODEC.decode(b));
        } finally {
            raw.release();
        }
    }

    @Test
    void replacementQueueRateLimitedAndDoesNotAccumulate() {
        var queue = new GroundViewPackets.Subscription();
        var a =
                new GroundViewPackets.Request(
                        1,
                        List.of(
                                new GroundViewPackets.Tile(1, -1),
                                new GroundViewPackets.Tile(2, -1)));
        var b = new GroundViewPackets.Request(1, List.of(new GroundViewPackets.Tile(3, -1)));
        assertTrue(queue.replace(a, 100));
        assertFalse(queue.replace(b, 100 + GroundStreaming.REQUEST_TICKS / 2 - 1));
        assertEquals(2, queue.size());
        assertTrue(queue.replace(b, 110));
        assertEquals(1, queue.size());
        assertEquals(3, queue.poll().key());
        assertNull(queue.poll());
        assertFalse(queue.replace(a, 90));
    }

    @Test
    void neighborhoodSubscriptionsCrossAllFacesWithoutGhostKeys() {
        for (var face : CubeSurfaceTopology.Face.values())
            for (double x : new double[] {.01, 1024, 2047.99})
                for (double z : new double[] {.01, 1024, 2047.99}) {
                    var pose = new PlanetShape.Pose(face, new BentStripGeometry.Point(x, 4096, z));
                    var all = new HashSet<Long>();
                    for (int cursor = 0; cursor < 12; cursor++) {
                        var keys = GroundViewClient.plan(pose, cursor);
                        assertEquals(keys.size(), new HashSet<>(keys).size());
                        assertTrue(keys.size() <= 16);
                        assertTrue(
                                keys.stream()
                                        .allMatch(k -> k >= 0 && k < GroundViewPackets.CHUNKS));
                        all.addAll(keys);
                    }
                    assertTrue(all.size() > 16);
                }
        var edge =
                GroundViewClient.plan(
                        new PlanetShape.Pose(
                                CubeSurfaceTopology.Face.TOP,
                                new BentStripGeometry.Point(.1, 5, 1000)),
                        0);
        assertTrue(
                edge.stream()
                        .anyMatch(k -> k / (128 * 128) != CubeSurfaceTopology.Face.TOP.ordinal()));
    }

    @Test
    void actualRuntimeHooksAreRegisteredWithoutGeneratingWorldOrAcceptingWrites() throws Exception {
        SourceText server = Sources.text("GroundViewServer"),
                client = Sources.text("GroundViewClient");
        assertTrue(server.contains("getChunkNow("));
        assertTrue(server.contains("GroundChunkSnapshot.capture("));
        assertTrue(server.contains("packet.session()!=GroundFlightServer.session(p)"));
        assertFalse(server.contains("setBlock("));
        assertTrue(server.contains("if(chunk==null)continue"));
        assertFalse(server.contains("level.getChunk("));
        assertTrue(client.contains("cache.accept(packet.chunk())"));
        assertTrue(client.contains("cache.refill(packet.chunk())"));
        assertTrue(client.contains("known") || client.contains("cache.version(key)"));
        assertTrue(Sources.text("StarfoldServer").contains("GroundViewServer.tick(level,saved)"));
        assertTrue(Sources.text("GroundClient").contains("GroundViewClient.initialize()"));
    }

    @Test
    void nativeShaderAddsOrbitalAndRoundLensButRetainsVanillaMain() throws Exception {
        String s =
                GroundShaderSource.patch(
                        "rendertype_solid",
                        true,
                        "#version 150\nuniform mat4 ProjMat;uniform mat4 ModelViewMat;in vec3 Position;void main(){gl_Position=ProjMat*ModelViewMat*vec4(Position,1);}");
        assertTrue(s.contains("cpNativeOrbitRepair(point)"));
        assertTrue(s.contains("cpNativeProject(view)"));
        assertTrue(s.contains("CPNativeOrbitLimits.w"));
        assertTrue(s.contains("-view.z-r*cosine"));
        assertTrue(s.contains("cpNativeOriginalMain();"));
        assertEquals(s, GroundShaderSource.patch("rendertype_solid", true, s));
    }
}
