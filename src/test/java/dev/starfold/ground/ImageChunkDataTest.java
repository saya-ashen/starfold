package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.ChartOrientation;

import io.netty.buffer.Unpooled;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;

class ImageChunkDataTest {
    @BeforeAll
    static void boot() {
        ImageStreamTestFixtures.boot();
    }

    private static final class CountingLayer extends DataLayer {
        int copies;

        @Override
        public DataLayer copy() {
            copies++;
            return super.copy();
        }
    }

    private static final class CountingLight extends LevelLightEngine {
        int reads;
        final CountingLayer lit = new CountingLayer();
        final LayerLightEventListener listener =
                (LayerLightEventListener)
                        Proxy.newProxyInstance(
                                LayerLightEventListener.class.getClassLoader(),
                                new Class<?>[] {LayerLightEventListener.class},
                                (proxy, method, args) -> {
                                    if (!method.getName().equals("getDataLayerData"))
                                        throw new AssertionError(method);
                                    reads++;
                                    return lit;
                                });

        CountingLight() {
            super(
                    new LightChunkGetter() {
                        @Override
                        public LightChunk getChunkForLighting(int x, int z) {
                            return null;
                        }

                        @Override
                        public BlockGetter getLevel() {
                            return EmptyBlockGetter.INSTANCE;
                        }
                    },
                    false,
                    false);
            lit.fill(15);
        }

        @Override
        public int getLightSectionCount() {
            return 26;
        }

        @Override
        public int getMinLightSection() {
            return -5;
        }

        @Override
        public LayerLightEventListener getLayerListener(LightLayer layer) {
            return listener;
        }
    }

    private static ImageStreamTestFixtures.Owner image(int turns, boolean empty) {
        var image = ImageStreamTestFixtures.owner(new ChunkPos(13, -19));
        if (!empty) {
            var frame = new ObserverFrame(0, 0, turns, 0, 0);
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int height = -48 + (x * 17 + z * 7) % 320;
                    var rotated = frame.toClient(new BlockPos(x, height, z));
                    var at =
                            new BlockPos(
                                    image.pos.getMinBlockX() + (rotated.getX() & 15),
                                    height,
                                    image.pos.getMinBlockZ() + (rotated.getZ() & 15));
                    var state = (x < 8 ? Blocks.OAK_STAIRS : Blocks.STONE).defaultBlockState();
                    image.data.setBlockState(at, ChartOrientation.state(state, turns), false);
                }
            for (var section : image.getSections())
                section.fillBiomesFromNoise(
                        (x, y, z, sampler) ->
                                ImageStreamTestFixtures.biomes.getHolderOrThrow(
                                        (x + y + z & 1) == 0 ? Biomes.FOREST : Biomes.DESERT),
                        null,
                        0,
                        0,
                        0);
        }
        Heightmap.primeHeightmaps(
                image.data,
                EnumSet.of(
                        Heightmap.Types.WORLD_SURFACE,
                        Heightmap.Types.MOTION_BLOCKING,
                        Heightmap.Types.OCEAN_FLOOR));
        return image;
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(
                Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    private static byte[] bytes(ClientboundLevelChunkPacketData data) {
        var buffer = buffer();
        try {
            data.write(buffer);
            var bytes = new byte[buffer.writerIndex()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static byte[] withCustomLight(ClientboundLevelChunkPacketData data) {
        var buffer = buffer();
        try {
            buffer.writeInt(13);
            buffer.writeInt(-19);
            data.write(buffer);
            var mask = new BitSet();
            mask.set(3);
            buffer.writeBitSet(mask);
            buffer.writeBitSet(new BitSet());
            buffer.writeBitSet(new BitSet());
            buffer.writeBitSet(mask);
            buffer.writeVarInt(1);
            var sky = new byte[2048];
            Arrays.fill(sky, (byte) 0xAF);
            buffer.writeByteArray(sky);
            buffer.writeVarInt(0);
            var wire = new byte[buffer.writerIndex()];
            buffer.getBytes(0, wire);
            var decoded = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer);
            assertEquals(13, decoded.getX());
            assertEquals(-19, decoded.getZ());
            assertEquals(mask, decoded.getLightData().getSkyYMask());
            assertEquals(mask, decoded.getLightData().getEmptyBlockYMask());
            assertArrayEquals(sky, decoded.getLightData().getSkyUpdates().getFirst());
            assertArrayEquals(bytes(data), bytes(decoded.getChunkData()));
            assertEquals(0, buffer.readableBytes());
            return wire;
        } finally {
            assertTrue(buffer.release());
            assertEquals(0, buffer.refCnt());
        }
    }

    @Test
    void publicChunkDataConstructorMatchesOldWrapperForEmptyAndRotatedMixedChunks() {
        for (int scenario = -1; scenario < 4; scenario++) {
            var image = image(Math.max(0, scenario), scenario == -1);
            var light = new CountingLight();
            var old =
                    new ClientboundLevelChunkWithLightPacket(image, light, null, null)
                            .getChunkData();
            assertEquals(52, light.reads);
            assertEquals(52, light.lit.copies);
            light.reads = 0;
            light.lit.copies = 0;
            var direct = new ClientboundLevelChunkPacketData(image);
            assertEquals(0, light.reads);
            assertEquals(0, light.lit.copies);
            assertArrayEquals(bytes(old), bytes(direct), "Scenario " + scenario);
            assertArrayEquals(
                    withCustomLight(old), withCustomLight(direct), "Scenario " + scenario);
            System.out.printf(
                    "EVIDENCE packet: scenario=%d, chunk bytes=%d, discarded vanilla light reads=52/0, discarded nonempty layer copies=52/0\n",
                    scenario, bytes(direct).length);
        }
    }
}
