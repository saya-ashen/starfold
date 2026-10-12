package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;

import org.junit.jupiter.api.*;

import java.util.*;

/** Derived data contracts only: no world generation, native gameplay or GPU acceptance. */
class GroundDerivedDataTest {
    @BeforeAll
    static void registry() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    int[] baseline() {
        var states = new int[GroundChunkSnapshot.VALUES];
        for (int y = -64; y < 320; y++) {
            var s =
                    y == -64
                            ? Blocks.BEDROCK.defaultBlockState()
                            : y < 124
                                    ? Blocks.STONE.defaultBlockState()
                                    : y < 127
                                            ? Blocks.DIRT.defaultBlockState()
                                            : y == 127
                                                    ? Blocks.GRASS_BLOCK.defaultBlockState()
                                                    : Blocks.AIR.defaultBlockState();
            Arrays.fill(
                    states, (y + 64) * 256, (y + 65) * 256, Block.BLOCK_STATE_REGISTRY.getId(s));
        }
        return states;
    }

    @Test
    void onlyTheSixCanonicalOwnerPlotsEnterCoverage() {
        for (int f = 0; f < 6; f++) {
            assertEquals(f * 16384L, GroundSurfaceIndex.key(f * 256, 0));
            assertEquals(f * 16384L + 16383, GroundSurfaceIndex.key(f * 256 + 127, 127));
            assertEquals(-1, GroundSurfaceIndex.key(f * 256 + 128, 0));
        }
        assertEquals(-1, GroundSurfaceIndex.key(-1, 0));
        assertEquals(-1, GroundSurfaceIndex.key(0, -1));
    }

    @Test
    void derivedIndexReloadRevalidatesWithoutWritingNativeChunks() {
        var index = new GroundSurfaceIndex();
        var pos = new BlockPos(0, 128, 0);
        index.mark(pos);
        var states = baseline();
        states[(128 + 64) * 256] =
                Block.BLOCK_STATE_REGISTRY.getId(Blocks.CHEST.defaultBlockState());
        var image = GroundChunkSnapshot.fromStates(0, 0, 0, 12, states);
        index.update(image);
        assertFalse(index.dirty(0));
        assertTrue(index.coverage().get(0));
        var tag = index.save(new CompoundTag(), null);
        assertFalse(tag.contains("inventory"));
        var loaded = GroundSurfaceIndex.load(tag, null);
        assertTrue(loaded.dirty(0));
        assertEquals(128, loaded.surface(0).height(0, 0));
        assertEquals(index.revision(), loaded.revision());
        var copy = loaded.coverage();
        copy.clear();
        assertTrue(loaded.coverage().get(0));
    }

    @Test
    void exactNativeBaselineClearsConservativeUnknownCoverage() {
        var index = new GroundSurfaceIndex();
        index.mark(BlockPos.ZERO);
        index.update(GroundChunkSnapshot.fromStates(0, 0, 0, 2, baseline()));
        assertFalse(index.coverage().get(0));
        assertNull(index.surface(0));
        assertFalse(index.dirty(0));
    }

    @Test
    void malformedDerivedSurfaceCannotDefaultToOwnerZero() {
        var index = new GroundSurfaceIndex();
        index.mark(BlockPos.ZERO);
        var s = baseline();
        s[(130 + 64) * 256] = Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        index.update(GroundChunkSnapshot.fromStates(0, 0, 0, 1, s));
        var tag = index.save(new CompoundTag(), null);
        tag.getList("surfaces", 10).getCompound(0).remove("key");
        assertThrows(IllegalArgumentException.class, () -> GroundSurfaceIndex.load(tag, null));
    }

    @Test
    void publicDisplayTagsAndOwnersRoundTripWithoutMutableAliases() {
        var states = baseline();
        states[(128 + 64) * 256] =
                Block.BLOCK_STATE_REGISTRY.getId(Blocks.CHEST.defaultBlockState());
        var chunk = GroundChunkSnapshot.fromStates(0, 0, 0, 3, states);
        var tag = new CompoundTag();
        tag.putString("label", "public");
        var image =
                new GroundDisplayEntities.Image(
                        new BlockPos.MutableBlockPos(0, 128, 0),
                        ResourceLocation.withDefaultNamespace("chest"),
                        tag);
        tag.putString("label", "changed");
        image.update().putString("label", "changed");
        assertEquals("public", image.update().getString("label"));
        var packet = new GroundViewPackets.Image(4, chunk, List.of(image));
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var b = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            GroundViewPackets.Image.CODEC.encode(b, packet);
            assertEquals(packet.entities(), GroundViewPackets.Image.CODEC.decode(b).entities());
            assertEquals(0, b.readableBytes());
        } finally {
            raw.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundViewPackets.Image(4, chunk, List.of(image, image)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundViewPackets.Image(
                                4,
                                chunk,
                                List.of(
                                        new GroundDisplayEntities.Image(
                                                new BlockPos(4096, 128, 0),
                                                image.type(),
                                                image.update()))));
    }

    @Test
    void equalHashPublicTagsAreStillDifferentUpdates() {
        var a = new CompoundTag();
        a.putString("text", "FB");
        var b = new CompoundTag();
        b.putString("text", "Ea");
        var first =
                new GroundDisplayEntities.Image(
                        BlockPos.ZERO, ResourceLocation.withDefaultNamespace("sign"), a);
        var second = new GroundDisplayEntities.Image(BlockPos.ZERO, first.type(), b);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, second);
    }
}
