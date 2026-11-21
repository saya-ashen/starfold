package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.*;

import dev.starfold.api.FarViewDiagnostics;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.worldgen.PlanetTerrainTest;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

class GroundFarEncodingDiagnosticsTest {
    private static final int PLANET = 7, FACE = 2, X = 32, Z = 48;
    private static final List<Point> TOP =
            List.of(
                    new Point(33, 9, 49), new Point(33, 9, 50),
                    new Point(34, 9, 50), new Point(34, 9, 49));

    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    @BeforeEach
    void start() {
        FarViewDiagnostics.stop();
        FarViewDiagnostics.observeContext(PLANET, 1, false, "fixture");
    }

    @AfterEach
    void stop() {
        FarViewDiagnostics.stop();
    }

    private static GroundFarDiagnostics.Collector watch() {
        FarViewDiagnostics.watch(PLANET, FACE, X, Z, 0);
        return GroundFarDiagnostics.capture(PLANET);
    }

    private static void encode(GroundFarDiagnostics.Collector c, int face) {
        c.encoded(
                face,
                RenderType.translucent(),
                Blocks.WATER.defaultBlockState(),
                TOP,
                0,
                1,
                2,
                3,
                true,
                243,
                0x123456,
                0x654321,
                true,
                "TOP");
    }

    private static Map<String, Object> onlyRecord() {
        var records = FarViewDiagnostics.snapshot().records();
        assertEquals(1, records.size());
        return records.getFirst().fields();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> samples(Map<String, Object> fields) {
        return (List<Map<String, Object>>) fields.get("sampledQuads");
    }

    @Test
    void disabledCaptureIsAbsentAndDoesNotPublish() {
        assertNull(GroundFarDiagnostics.capture(PLANET));
        assertNull(GroundFarDiagnostics.captureCurrent());
        assertTrue(FarViewDiagnostics.snapshot().records().isEmpty());
    }

    @Test
    void encodingRetainsBlockFluidCutLayerTagAndCoordinatesWithoutInventingLight() {
        var c = watch();
        encode(c, FACE);
        c.close("ENCODED_CPU_BUFFER_CLOSED", false);
        var fields = onlyRecord();
        var sample = samples(fields).getFirst();
        assertTrue(sample.get("block").toString().contains("water"));
        assertEquals(true, sample.get("hasFluid"));
        assertEquals(true, sample.get("cut"));
        assertEquals("TOP", sample.get("kind"));
        assertEquals(243, sample.get("tag"));
        assertEquals(-1, sample.get("packedLight"));
        assertEquals(List.of(33f, 9f, 49f), ((List<?>) sample.get("points")).getFirst());
        assertEquals(RenderType.translucent().toString(), sample.get("layer"));
        assertEquals("UNOBSERVED", fields.get("shaderRejection"));
        assertEquals("UNOBSERVED", fields.get("gpuOutput"));
        assertEquals(false, fields.get("completionKnown"));
    }

    @Test
    void reservoirCountsEverySelectedQuadAndCapsRetainedSamples() {
        var c = watch();
        for (int i = 0; i < 100; i++) encode(c, FACE);
        c.close("ENCODED_CPU_BUFFER_CLOSED", false);
        c.close("ENCODED_CPU_BUFFER_CLOSED", false);
        var fields = onlyRecord();
        assertEquals(100L, fields.get("totalQuads"));
        assertEquals(64, samples(fields).size());
        assertEquals(36L, fields.get("truncatedQuads"));
    }

    @Test
    void watchReplacementAndRendererResetRejectCapturedBuilders() {
        var old = watch();
        encode(old, FACE);
        FarViewDiagnostics.watch(PLANET, FACE, X, Z, 0);
        old.close("ENCODED_CPU_BUFFER_CLOSED", false);
        assertTrue(FarViewDiagnostics.snapshot().records().isEmpty());
        var reset = GroundFarDiagnostics.capture(PLANET);
        encode(reset, FACE);
        FarViewDiagnostics.observeContext(PLANET, 2, true, "fixture reset");
        reset.close("ENCODED_CPU_BUFFER_CLOSED", false);
        assertTrue(FarViewDiagnostics.snapshot().records().isEmpty());
    }

    @Test
    void wrongPlanetFaceAndOutsideGeometryAreExcluded() {
        var c = watch();
        assertNull(GroundFarDiagnostics.capture(PLANET + 1));
        encode(c, FACE + 1);
        c.encoded(
                FACE,
                RenderType.solid(),
                Blocks.STONE.defaultBlockState(),
                TOP.stream().map(p -> new Point(p.x() + 32, p.y(), p.z())).toList(),
                0,
                1,
                2,
                3,
                true,
                243,
                0,
                0,
                false,
                "TOP");
        c.close("ENCODED_CPU_BUFFER_CLOSED", false);
        assertTrue(FarViewDiagnostics.snapshot().records().isEmpty());
    }

    @Test
    void packedEvidenceReadsActualMaterialTagLightAndPositionBytes() {
        var c = watch();
        var data = ByteBuffer.allocate(128).order(ByteOrder.nativeOrder());
        long address = MemoryUtil.nmemAlloc(48);
        try {
            for (int i = 0; i < 4; i++) {
                var p = TOP.get(i);
                data.putFloat(i * 32, (float) p.x());
                data.putFloat(i * 32 + 4, (float) p.y());
                data.putFloat(i * 32 + 8, (float) p.z());
                GroundCompact.put(
                        address + i * 12L, p.x(), p.y(), p.z(), 0x123456 + i, 243, 0xf070);
            }
            var lightPosition = new BlockPos(FACE * 4096 + 33, 138, 49);
            for (int n = 0; n < 64; n++)
                c.packed(FACE, RenderType.solid(), -1, data, 0, address, lightPosition, 0xf070);
            c.close("PACKED_CPU", true);
            var fields = onlyRecord();
            assertEquals(64, samples(fields).size());
            var sample = samples(fields).getFirst();
            assertEquals(List.of(FACE * 4096 + 33, 138, 49), sample.get("lightPosition"));
            assertEquals(0xf070, sample.get("sampledLight"));
            assertEquals("UNAVAILABLE_AT_PACKING", sample.get("blockFluidCutProvenance"));
            var vertices = (List<?>) sample.get("vertices");
            for (int i = 0; i < 4; i++) {
                var vertex = (Map<?, ?>) vertices.get(i);
                assertEquals(0x123456 + i, vertex.get("materialId"));
                assertEquals(243, vertex.get("tag"));
                assertEquals(0xf070, vertex.get("packedLight"));
            }
            assertEquals(
                    List.of(528, (2048 + 9) * 16, 784),
                    ((Map<?, ?>) vertices.getFirst()).get("packedPosition"));
            assertEquals(true, fields.get("complete"));
            assertEquals(true, fields.get("completionKnown"));
        } finally {
            MemoryUtil.nmemFree(address);
        }
    }

    @Test
    void enablingActualPackingHookPreservesBytesAndLightReadCount() {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        byte[] disabled = packTwoIdenticalQuads(reads);
        assertEquals(1, reads.get());
        FarViewDiagnostics.watch(PLANET, FACE, X, Z, 0);
        reads.set(0);
        byte[] enabled = packTwoIdenticalQuads(reads);
        assertEquals(1, reads.get());
        assertArrayEquals(disabled, enabled);
        assertEquals(2L, onlyRecord().get("totalQuads"));
    }

    private static byte[] packTwoIdenticalQuads(java.util.concurrent.atomic.AtomicInteger reads) {
        try (var source = new ByteBufferBuilder(4096);
                var target = new ByteBufferBuilder(4096)) {
            long start = source.reserve(8 * 32);
            for (int i = 0; i < 8; i++) {
                var point = TOP.get(i % 4);
                long p = start + i * 32L;
                MemoryUtil.memPutFloat(p, (float) point.x());
                MemoryUtil.memPutFloat(p + 4, (float) point.y());
                MemoryUtil.memPutFloat(p + 8, (float) point.z());
                MemoryUtil.memPutInt(p + 12, 0xf3123456);
                MemoryUtil.memPutFloat(p + 16, .25f);
                MemoryUtil.memPutFloat(p + 20, .75f);
                MemoryUtil.memPutInt(p + 24, 0x00f000f0);
                MemoryUtil.memPutInt(p + 28, 0x007f00);
            }
            var mesh =
                    new MeshData(
                            source.build(),
                            new MeshData.DrawState(
                                    DefaultVertexFormat.BLOCK,
                                    8,
                                    12,
                                    VertexFormat.Mode.QUADS,
                                    VertexFormat.IndexType.least(8)));
            try (var packing =
                    new GroundCompact.Packing(
                            mesh,
                            target,
                            RenderType.solid(),
                            FACE,
                            -1,
                            pos -> {
                                reads.incrementAndGet();
                                return 0xf070;
                            })) {
                assertTrue(packing.step(new GroundUploadBudget(Long.MAX_VALUE)));
                try (var packed = packing.finish()) {
                    byte[] bytes = new byte[packed.vertexBuffer().remaining()];
                    packed.vertexBuffer().get(bytes);
                    return bytes;
                }
            }
        }
    }

    @Test
    void maximumPackedReservoirFitsApiPayloadBoundAndMarksPartialPacking() {
        var c = watch();
        var data = ByteBuffer.allocate(128).order(ByteOrder.nativeOrder());
        long address = MemoryUtil.nmemAlloc(48);
        try {
            for (int i = 0; i < 4; i++) {
                var p = TOP.get(i);
                data.putFloat(i * 32, (float) p.x());
                data.putFloat(i * 32 + 4, (float) p.y());
                data.putFloat(i * 32 + 8, (float) p.z());
                GroundCompact.put(address + i * 12L, p.x(), p.y(), p.z(), i, 255, 0);
            }
            for (int i = 0; i < 70; i++)
                c.packed(FACE, RenderType.solid(), -1, data, 0, address, BlockPos.ZERO, 0);
            c.close("PACKED_CPU", false);
            var fields = onlyRecord();
            assertEquals(64, samples(fields).size());
            assertEquals(6L, fields.get("truncatedQuads"));
            assertEquals(false, fields.get("complete"));
            assertEquals(true, fields.get("completionKnown"));
        } finally {
            MemoryUtil.nmemFree(address);
        }
    }
}
