package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.*;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

class GroundCompactTest {
    @Test
    void twelveBytePositionsTagsAndLightRoundTripIncludingLastPlanetEdge() {
        assertEquals(12, GroundCompact.FORMAT.getVertexSize());
        long p = MemoryUtil.nmemAlloc(12);
        try {
            GroundCompact.put(p, 2048, -192.0625, 256.5, 65535, 243, 0xf0f0);
            assertEquals(2048, Short.toUnsignedInt(MemoryUtil.memGetShort(p)) / 16.0);
            assertEquals(
                    -192.0625, Short.toUnsignedInt(MemoryUtil.memGetShort(p + 2)) / 16.0 - 2048);
            assertEquals(256.5, Short.toUnsignedInt(MemoryUtil.memGetShort(p + 4)) / 16.0);
            assertEquals(65535, Short.toUnsignedInt(MemoryUtil.memGetShort(p + 6)));
            assertEquals(243, Short.toUnsignedInt(MemoryUtil.memGetShort(p + 8)));
            assertEquals(0xf0f0, Short.toUnsignedInt(MemoryUtil.memGetShort(p + 10)));
            GroundCompact.put(p, 0, 0, 0, 0x123456, 243, 0xf0f0);
            int packed = Short.toUnsignedInt(MemoryUtil.memGetShort(p + 8));
            assertEquals(243, packed & 255);
            assertEquals(
                    0x123456,
                    Short.toUnsignedInt(MemoryUtil.memGetShort(p + 6)) | (packed >>> 8) << 16);
        } finally {
            MemoryUtil.nmemFree(p);
        }
    }

    @Test
    void closureLightSamplesTheExposedRimRatherThanTheBuriedWallCentre() {
        var vertices = java.nio.ByteBuffer.allocate(128).order(java.nio.ByteOrder.nativeOrder());
        var points = dev.starfold.worldgen.lod.VoxelMesher.points(0, 48, -640, 64, 64, 10, 80);
        for (int i = 0; i < 4; i++) {
            var p = points.get(i);
            vertices.putFloat(i * 32, (float) p.x())
                    .putFloat(i * 32 + 4, (float) p.y())
                    .putFloat(i * 32 + 8, (float) p.z());
        }
        assertEquals(
                new net.minecraft.core.BlockPos(4 * 4096 + 47, 138, 72),
                GroundCompact.lightPosition(4, vertices, 0));
    }

    @Test
    void unpackHasOneCoordinatePathAndRetainsWaterResiduals() {
        String source =
                """
                in vec3 Position;
                in vec4 Color;
                in vec2 UV0;
                in ivec2 UV2;
                in vec3 Normal;
                vec4 cpNativeModelView;
                void main() {
                cpNativePackedBounds=UV2;cpNativeLightUV2=UV2;
                }
                """;
        String result = GroundCompact.unpack(source);
        assertTrue(result.contains("in vec3 PackedPosition;"));
        assertTrue(result.contains("Position=PackedPosition/16.0"));
        assertTrue(result.contains("Position.y+=cpMaterial(material+1).w;"));
        assertFalse(result.contains("CPNativeLodPass"));
        assertFalse(result.contains("512.0"));
        assertFalse(result.contains("in vec4 Color;"));
    }

    @Test
    void runtimeAppearanceAliasKeepsActualVariantAndDelegatesProgramLifetime() {
        class Program extends com.mojang.blaze3d.shaders.Program {
            int attached, closed;

            Program() {
                super(Type.FRAGMENT, -1, GroundCompact.Variant.SOLID.name);
            }

            @Override
            public void attachToShader(com.mojang.blaze3d.shaders.Shader shader) {
                attached++;
            }

            @Override
            public void close() {
                closed++;
            }
        }
        var actual = new Program();
        var alias = GroundCompact.appearanceFragment(actual);
        assertEquals(GroundCompact.NAME, alias.getName());
        assertEquals(GroundCompact.Variant.SOLID.name, actual.getName());
        alias.attachToShader(null);
        alias.close();
        assertEquals(1, actual.attached);
        assertEquals(1, actual.closed);
    }

    @Test
    void materialRegistrationRejectsBackgroundWorkers() throws InterruptedException {
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var worker =
                new Thread(
                        () -> {
                            try {
                                GroundCompact.registerMaterial(0, 0, 0, 0xffffff, 0x7f00, -1, 0);
                            } catch (Throwable thrown) {
                                failure.set(thrown);
                            }
                        });
        worker.start();
        worker.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
    }

    private static final class FakePaletteGpu implements GroundCompact.PaletteGpu {
        final java.util.Map<Integer, float[]> images = new java.util.HashMap<>();
        final java.util.List<Integer> deleted = new java.util.ArrayList<>();
        final java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong();
        int created, writes;
        boolean fail;

        public int create(int height) {
            clock.addAndGet(100);
            images.put(++created, new float[height * 256 * 4]);
            return created;
        }

        public void upload(int texture, int texel, java.nio.FloatBuffer data) {
            clock.addAndGet(10);
            if (fail) throw new IllegalStateException("injected transfer failure");
            assertTrue(texel % 256 + data.remaining() / 4 <= 256);
            data.get(images.get(texture), texel * 4, data.remaining());
            writes++;
        }

        public void delete(int texture) {
            deleted.add(texture);
            images.remove(texture);
        }
    }

    private static java.util.List<GroundCompact.Material> paletteMaterials(int count) {
        var result = new java.util.ArrayList<GroundCompact.Material>();
        for (int i = 0; i < count; i++)
            result.add(new GroundCompact.Material(i, i + .5f, -1, 0x332211, 0x7f, -.5f, .01f));
        return result;
    }

    private static GroundUploadBudget paletteBudget(FakePaletteGpu gpu, int bytes) {
        return new GroundUploadBudget(Long.MAX_VALUE, bytes, gpu.clock::get);
    }

    @Test
    void paletteAllocationRequiresAvailableTimeTransferAllowanceAndPeakMemoryBeforeAnyGl() {
        var gpu = new FakePaletteGpu();
        try (var palette = new GroundCompact.Palette(gpu)) {
            var source = paletteMaterials(1);
            assertFalse(palette.prepare(source, paletteBudget(gpu, 0), 4096));
            assertFalse(palette.prepare(source, paletteBudget(gpu, 4096), 4095));
            var reserved = paletteBudget(gpu, 8192);
            reserved.reserve(8192);
            assertFalse(palette.prepare(source, reserved, 8192));
            var expired = new GroundUploadBudget(0, 8192, gpu.clock::get);
            assertFalse(palette.prepare(source, expired, 8192));
            assertEquals(0, gpu.created);
            assertEquals(0, palette.bytes());
            assertFalse(palette.ready(1));
        }
    }

    @Test
    void paletteChargesActualTransferBytesAndAllocationTimeAndPublishesCompleteMaterials() {
        var gpu = new FakePaletteGpu();
        try (var palette = new GroundCompact.Palette(gpu)) {
            var source = paletteMaterials(1);
            var first = paletteBudget(gpu, 16);
            assertFalse(palette.prepare(source, first, 4096));
            assertEquals(16, first.consumedBytes());
            assertEquals(110, first.transferNanos());
            assertFalse(palette.ready(1));
            assertEquals(4096, palette.bytes());
            assertFalse(palette.prepare(source, paletteBudget(gpu, 16), 4096));
            assertTrue(palette.prepare(source, paletteBudget(gpu, 16), 4096));
            assertTrue(palette.ready(1));
            assertFalse(palette.ready(2));
            assertEquals(1, gpu.created);
            assertEquals(.5f, gpu.images.get(1)[1]);
            assertEquals(.01f, gpu.images.get(1)[7]);
            assertEquals(-.5f, gpu.images.get(1)[11]);
        }
        assertEquals(java.util.List.of(1), gpu.deleted);
    }

    @Test
    void paletteAppendTransfersOnlyNewTexelsWithoutReallocatingOrRepublishingOldData() {
        var gpu = new FakePaletteGpu();
        try (var palette = new GroundCompact.Palette(gpu)) {
            var source = paletteMaterials(1);
            assertTrue(palette.prepare(source, paletteBudget(gpu, 8192), 8192));
            source.add(paletteMaterials(2).get(1));
            var append = paletteBudget(gpu, 48);
            assertTrue(palette.prepare(source, append, 8192));
            assertEquals(48, append.consumedBytes());
            assertEquals(10, append.transferNanos());
            assertEquals(1, gpu.created);
            assertTrue(palette.ready(2));
            assertEquals(1f, gpu.images.get(1)[12]);
        }
    }

    @Test
    void paletteGrowthRetainsOldPublicationUntilSnapshotFinishesAndReservesBothTextures() {
        var gpu = new FakePaletteGpu();
        try (var palette = new GroundCompact.Palette(gpu)) {
            var source = paletteMaterials(85);
            assertTrue(palette.prepare(source, paletteBudget(gpu, 16384), 16384));
            source.add(paletteMaterials(86).get(85));
            assertEquals(12288, palette.requiredBytes(source.size()));
            assertFalse(palette.prepare(source, paletteBudget(gpu, 16384), 12287));
            assertTrue(palette.ready(85));
            assertEquals(1, gpu.created);
            assertFalse(palette.prepare(source, paletteBudget(gpu, 16), 12288));
            assertEquals(12288, palette.bytes());
            assertEquals(12288, palette.requiredBytes(source.size()));
            assertTrue(palette.ready(85));
            assertFalse(palette.ready(86));
            assertTrue(gpu.deleted.isEmpty());
            // Registration may continue while replacement copying is suspended.
            source.add(paletteMaterials(87).get(86));
            assertFalse(palette.prepare(source, paletteBudget(gpu, 8192), 12288));
            assertTrue(palette.ready(86));
            assertFalse(palette.ready(87));
            assertEquals(8192, palette.bytes());
            assertEquals(java.util.List.of(1), gpu.deleted);
            assertTrue(palette.prepare(source, paletteBudget(gpu, 48), 12288));
            assertTrue(palette.ready(87));
            assertEquals(85f, gpu.images.get(2)[85 * 12]);
        }
    }

    @Test
    void paletteFailureAndCancelledGrowthKeepOldPublicationAndReleaseBothAllocations() {
        var gpu = new FakePaletteGpu();
        var palette = new GroundCompact.Palette(gpu);
        var source = paletteMaterials(85);
        assertTrue(palette.prepare(source, paletteBudget(gpu, 16384), 16384));
        source.add(paletteMaterials(86).get(85));
        gpu.fail = true;
        assertThrows(
                IllegalStateException.class,
                () -> palette.prepare(source, paletteBudget(gpu, 16384), 16384));
        assertTrue(palette.ready(85));
        assertFalse(palette.ready(86));
        assertEquals(12288, palette.bytes());
        assertTrue(gpu.deleted.isEmpty());
        palette.close();
        palette.close();
        assertEquals(java.util.List.of(2, 1), gpu.deleted);
        assertEquals(0, palette.bytes());
        assertFalse(palette.ready(1));
    }

    @Test
    void storageLargerThanFrameQuotaStillMakesProgressWithBoundedTexelUploads() {
        var gpu = new FakePaletteGpu();
        try (var palette = new GroundCompact.Palette(gpu)) {
            var budget = new GroundUploadBudget(Long.MAX_VALUE, 0L, gpu.clock::get);
            // 87382 materials need 2048 rows (8 MiB), beyond even the 4 MiB burst quota.
            var source = paletteMaterials(87382);
            assertFalse(palette.prepare(source, budget, GroundFaceArenas.MAX_BYTES));
            assertEquals(1, gpu.created);
            assertEquals(GroundUploadBudget.FRAME_BYTES, budget.consumedBytes());
            assertFalse(palette.ready(source.size()));
            for (int frame = 0; frame < 4; frame++) {
                var next = new GroundUploadBudget(Long.MAX_VALUE, 0L, gpu.clock::get);
                palette.prepare(source, next, GroundFaceArenas.MAX_BYTES);
                assertTrue(next.consumedBytes() <= GroundUploadBudget.FRAME_BYTES);
            }
            assertTrue(palette.ready(source.size()));
            assertEquals(8 * 1024 * 1024, palette.bytes());
        }
    }

    @Test
    void paletteTransferDeadlineYieldsBetweenRowsWithoutPublishingAnIncompleteReplacement() {
        var gpu = new FakePaletteGpu();
        try (var palette = new GroundCompact.Palette(gpu)) {
            var source = paletteMaterials(86);
            var budget = new GroundUploadBudget(110, 20000, gpu.clock::get);
            assertFalse(palette.prepare(source, budget, 20000));
            assertEquals(4096, budget.consumedBytes());
            assertEquals(110, budget.transferNanos());
            assertFalse(palette.ready(1));
            assertEquals(1, gpu.writes);
            var resume = paletteBudget(gpu, 32);
            assertTrue(palette.prepare(source, resume, 20000));
            assertEquals(32, resume.consumedBytes());
            assertTrue(palette.ready(86));
            assertEquals(85f, gpu.images.get(1)[85 * 12]);
        }
    }

    @Test
    void largePaletteAllocationCanBeAdmittedByTheExistingAdaptiveCatchUpBudget() {
        var gpu = new FakePaletteGpu();
        try (var palette = new GroundCompact.Palette(gpu)) {
            var source = paletteMaterials(21846);
            var budget =
                    new GroundUploadBudget(
                            Long.MAX_VALUE, GroundUploadBudget.BACKLOG_BYTES + 1L, gpu.clock::get);
            assertTrue(palette.prepare(source, budget, GroundFaceArenas.MAX_BYTES));
            assertEquals(source.size() * 48, budget.consumedBytes());
            assertTrue(budget.consumedBytes() <= GroundUploadBudget.BURST_BYTES);
            assertTrue(palette.ready(source.size()));
        }
    }
}
