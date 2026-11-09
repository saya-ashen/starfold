package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelColumns;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;

class GroundVoxelCodecTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    private static VoxelColumns image(boolean hollow) {
        var capture = new VoxelColumns.Capture(-64, 320);
        while (!capture.done())
            capture.step(
                    (x, y, z) ->
                            y == 150 || !hollow && y >= 128 && y < 150
                                    ? Blocks.STONE.defaultBlockState()
                                    : Blocks.AIR.defaultBlockState(),
                    4096);
        return capture.result().orElseThrow();
    }

    private static GroundSurface surface(VoxelColumns voxels) {
        var heights = new int[256];
        var states = new int[256];
        Arrays.fill(heights, 150);
        Arrays.fill(states, Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState()));
        return new GroundSurface(8, heights, states, voxels);
    }

    @Test
    void nearAndRemotePacketsKeepExactOptionalGeometry() {
        for (var surface : new GroundSurface[] {surface(image(true)), surface(null)}) {
            var raw = Unpooled.buffer();
            try {
                var buffer = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
                var packet = new GroundViewPackets.Surface(1, 0, surface);
                GroundViewPackets.Surface.CODEC.encode(buffer, packet);
                assertTrue(surface.sameAs(GroundViewPackets.Surface.CODEC.decode(buffer).image()));
                assertEquals(0, buffer.readableBytes());
                PlanetRemotePackets.Surface.CODEC.encode(
                        buffer, new PlanetRemotePackets.Surface(0, packet));
                var remote = PlanetRemotePackets.Surface.CODEC.decode(buffer);
                assertEquals(0, remote.planet());
                assertTrue(surface.sameAs(remote.surface().image()));
                assertEquals(0, buffer.readableBytes());
            } finally {
                raw.release();
            }
        }
    }

    @Test
    void sameRoofButDifferentVoidInvalidatesBakedCover() {
        var hollow = surface(image(true));
        var filled = surface(image(false));
        assertArrayEquals(hollow.heights(), filled.heights());
        assertArrayEquals(hollow.states(), filled.states());
        assertFalse(hollow.sameColumns(filled));
        assertFalse(hollow.sameAs(filled));
        var a = new GroundCoverSnapshot(null, Map.of(0L, hollow), new BitSet());
        var b = new GroundCoverSnapshot(null, Map.of(0L, filled), new BitSet());
        assertFalse(a.sameInputs(b));
        assertFalse(hollow.sameColumns(surface(null)));
        assertTrue(hollow.sameColumns(surface(image(true))));
    }

    @Test
    void oversizedWireArrayIsRejectedBeforeAllocation() {
        var raw = Unpooled.buffer();
        try {
            var buffer = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            buffer.writeBoolean(true);
            buffer.writeInt(-64);
            buffer.writeInt(320);
            buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(RuntimeException.class, () -> GroundVoxelCodec.read(buffer));
        } finally {
            raw.release();
        }
    }
}
