package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.*;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;

class GroundPackingTest {
    @BeforeAll
    static void registry() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void largePackingKeepsTransferBytesAndReusesExactLightPositions() {
        var samples = new HashSet<BlockPos>();
        var calls = new AtomicInteger();
        try (var fixture =
                new Fixture(
                        12000,
                        false,
                        pos -> {
                            samples.add(pos);
                            calls.incrementAndGet();
                            return 0xf0a0;
                        })) {
            var budget = new GroundUploadBudget(100, 48, () -> 10);
            assertTrue(fixture.packing.step(budget));
            assertEquals(1, samples.size());
            assertEquals(1, calls.get());
            assertEquals(48, budget.take(48, 12));
            try (var packed = fixture.packing.finish()) {
                var bytes = packed.vertexBuffer();
                assertEquals(12000 * 12, bytes.remaining());
                int material = Short.toUnsignedInt(bytes.getShort(6));
                for (int vertex = 0; vertex < 12000; vertex++) {
                    int p = vertex * 12;
                    assertEquals(
                            (vertex % 4 == 1 || vertex % 4 == 2 ? 2 : 1) * 16,
                            Short.toUnsignedInt(bytes.getShort(p)));
                    assertEquals(2048 * 16, Short.toUnsignedInt(bytes.getShort(p + 2)));
                    assertEquals(
                            (vertex % 4 >= 2 ? 2 : 1) * 16,
                            Short.toUnsignedInt(bytes.getShort(p + 4)));
                    assertEquals(material, Short.toUnsignedInt(bytes.getShort(p + 6)));
                    assertEquals(243, Short.toUnsignedInt(bytes.getShort(p + 8)) & 255);
                    assertEquals(0xf0a0, Short.toUnsignedInt(bytes.getShort(p + 10)));
                }
            }
        }
    }

    @Test
    void lodKeysDoNotSwitchVertexCoordinatesToTheRemovedTileEncoding() {
        try (var nativeMesh = new Fixture(4, false, pos -> 0xf0f0);
                var lodMesh = new Fixture(4, false, pos -> 0xf0f0, 127)) {
            assertTrue(nativeMesh.packing.step(new GroundUploadBudget(Long.MAX_VALUE)));
            assertTrue(lodMesh.packing.step(new GroundUploadBudget(Long.MAX_VALUE)));
            try (var nativePacked = nativeMesh.packing.finish();
                    var lodPacked = lodMesh.packing.finish()) {
                assertEquals(nativePacked.vertexBuffer(), lodPacked.vertexBuffer());
            }
        }
    }

    @Test
    void sameColumnAtDifferentHeightsHasSeparateLightSamples() {
        var samples = new HashSet<BlockPos>();
        try (var fixture =
                new Fixture(
                        8,
                        true,
                        pos -> {
                            samples.add(pos);
                            return pos.getY() * 16;
                        })) {
            assertTrue(fixture.packing.step(new GroundUploadBudget(100, 0, () -> 10)));
            assertEquals(2, samples.size());
            try (var packed = fixture.packing.finish()) {
                var bytes = packed.vertexBuffer();
                assertNotEquals(bytes.getShort(10), bytes.getShort(4 * 12 + 10));
            }
        }
    }

    @Test
    void deadlineYieldsBetweenQuadsAndResumingReadsLiveLightAgain() {
        var clock = new AtomicLong(10);
        var calls = new AtomicInteger();
        try (var fixture =
                new Fixture(
                        8,
                        false,
                        pos -> {
                            int call = calls.incrementAndGet();
                            if (call == 1) clock.set(100);
                            return call * 16;
                        })) {
            var first = new GroundUploadBudget(100, 48, clock::get);
            assertFalse(fixture.packing.step(first));
            assertThrows(IllegalStateException.class, fixture.packing::finish);
            assertEquals(1, calls.get());
            clock.set(10);
            // The same BlockPos is sampled again when the next step resumes.
            var second = new GroundUploadBudget(100, 0, clock::get);
            assertTrue(fixture.packing.step(second));
            assertEquals(2, calls.get());
            try (var packed = fixture.packing.finish()) {
                var bytes = packed.vertexBuffer();
                for (int vertex = 0; vertex < 8; vertex++)
                    assertEquals(
                            vertex < 4 ? 16 : 32,
                            Short.toUnsignedInt(bytes.getShort(vertex * 12 + 10)));
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ByteBufferBuilder source = new ByteBufferBuilder(4096);
        final ByteBufferBuilder target = new ByteBufferBuilder(4096);
        final GroundCompact.Packing packing;

        Fixture(int vertices, boolean differentHeight, ToIntFunction<BlockPos> light) {
            this(vertices, differentHeight, light, -1);
        }

        Fixture(int vertices, boolean differentHeight, ToIntFunction<BlockPos> light, long lodKey) {
            long start = source.reserve(vertices * 32);
            for (int vertex = 0; vertex < vertices; vertex++) {
                long p = start + vertex * 32L;
                MemoryUtil.memPutFloat(p, vertex % 4 == 1 || vertex % 4 == 2 ? 2 : 1);
                MemoryUtil.memPutFloat(p + 4, differentHeight && vertex >= 4 ? 1 : 0);
                MemoryUtil.memPutFloat(p + 8, vertex % 4 >= 2 ? 2 : 1);
                MemoryUtil.memPutInt(p + 12, 0xf3563412);
                MemoryUtil.memPutFloat(p + 16, .25f);
                MemoryUtil.memPutFloat(p + 20, .75f);
                MemoryUtil.memPutInt(p + 24, 0xffff0000);
                MemoryUtil.memPutInt(p + 28, 0x007f00);
            }
            var mesh =
                    new MeshData(
                            source.build(),
                            new MeshData.DrawState(
                                    DefaultVertexFormat.BLOCK,
                                    vertices,
                                    vertices / 4 * 6,
                                    VertexFormat.Mode.QUADS,
                                    VertexFormat.IndexType.least(vertices)));
            packing = new GroundCompact.Packing(mesh, target, RenderType.solid(), 0, lodKey, light);
        }

        public void close() {
            packing.close();
            target.close();
            source.close();
        }
    }
}
