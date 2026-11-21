package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.client.renderer.RenderType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.BitSet;

class GroundFaceArenasTest {
    @BeforeAll
    static void registry() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void replacingAnOpaqueRangeRebuildsOnlyItsPage() {
        try (var page = new GroundLodArena(32); var other = new GroundLodArena(32);
                var batches = new GroundFaceArenas.LayerBatches(false)) {
            var old = slot(page, 0);
            var stable = slot(other, 4);
            batches.put(old, 4, 1);
            batches.put(stable, 3, 2);
            var first = batches.prepare();
            var stableBatch = first.getFirst();
            var stableRun = stableBatch.commands.runs.getFirst();
            var oldRun = first.getLast().commands.runs.getFirst();
            assertSame(other, stableRun.arena());

            var replacement = slot(page, 12);
            batches.remove(old);
            batches.put(replacement, 2, 1);
            var next = batches.prepare();
            assertSame(stableBatch, next.getLast());
            assertSame(stableRun, stableBatch.commands.runs.getFirst());
            assertFalse(stableBatch.prepare(false));
            assertNotSame(oldRun, next.getFirst().commands.runs.getFirst());
            assertEquals(12, next.getFirst().commands.runs.getFirst().commands().bases.get(0));

            batches.remove(replacement);
            assertEquals(1, batches.prepare().size());
            assertSame(stableRun, stableBatch.commands.runs.getFirst());
        }
    }

    @Test
    void viewDistanceOnlyRebuildsAnOpaquePageWhenTheOrderChanges() {
        try (var page = new GroundLodArena(32); var batch = new GroundFaceArenas.DrawBatch()) {
            var near = slot(page, 4);
            var far = slot(page, 8);
            batch.put(near, 2, 1);
            batch.put(far, 4, 2);
            assertTrue(batch.prepare(false));
            var original = batch.commands.runs.getFirst();

            batch.put(near, 2.5, 1);
            batch.put(far, 4.5, 2);
            assertFalse(batch.prepare(false));
            assertSame(original, batch.commands.runs.getFirst());

            batch.put(near, 5, 1);
            assertTrue(batch.prepare(false));
            assertEquals(8, batch.commands.runs.getFirst().commands().bases.get(0));
        }
    }

    @Test
    void incrementalTranslucentChangesPreserveDistanceOrderAcrossPages() {
        try (var a = new GroundLodArena(32); var b = new GroundLodArena(32);
                var batches = new GroundFaceArenas.LayerBatches(true)) {
            var first = slot(a, 4);
            var middle = slot(b, 8);
            var last = slot(a, 12);
            batches.put(first, 3, 1);
            batches.put(middle, 2, 2);
            batches.put(last, 1, 3);
            var batch = batches.prepare().getFirst();
            assertEquals(java.util.List.of(a, b, a), batch.commands.runs.stream()
                    .map(GroundFaceArenas.PageCommands.Run::arena).toList());

            batches.put(last, 4, 3);
            assertSame(batch, batches.prepare().getFirst());
            assertEquals(java.util.List.of(a, b), batch.commands.runs.stream()
                    .map(GroundFaceArenas.PageCommands.Run::arena).toList());
            assertEquals(12, batch.commands.runs.getFirst().commands().bases.get(0));
            assertEquals(4, batch.commands.runs.getFirst().commands().bases.get(1));
        }
    }

    private static GroundFaceArenas.Slot slot(GroundLodArena arena, int first) {
        return new GroundFaceArenas.Slot(null, 0, 0, new GroundLodArena.Range(arena, first, 4), 0);
    }

    @Test
    void productionAllowanceKeepsDetailBelowTheOnDemandGpuCap() {
        assertEquals(1024L << 20, GroundFaceArenas.MAX_BYTES);
        assertEquals(768L << 20, GroundViewPolicy.DETAIL_BYTES);
        assertEquals(GroundViewPolicy.DETAIL_BYTES, GroundQuadtree.DEFAULT_DETAIL_BUDGET);
        try (var arenas = new GroundFaceArenas()) {
            assertEquals(0, arenas.bytes(), "a larger cap must not eagerly allocate VBO storage");
        }
    }

    @Test
    void earlyAdmissionAccountsForCombinedLayerGrowthWithoutAllocating() {
        // Palette/mask are reserved by the production query even before their first upload.
        long reserved = GroundCompact.requiredGpuBytes()
                + (long) GroundFaceArenas.MASK_WIDTH * GroundFaceArenas.MASK_HEIGHT;
        try (var arenas = new GroundFaceArenas(reserved + 4096 * 12L)) {
            assertEquals(GroundLodArena.AllocationStatus.REUSE,
                    arenas.admission(0, new int[] {4, 0, 0, 0}));
            assertEquals(GroundLodArena.AllocationStatus.REJECTED,
                    arenas.admission(0, new int[] {4096, 4096, 0, 0}));
            assertEquals(GroundLodArena.AllocationStatus.REUSE,
                    arenas.admission(0, new int[] {4, 0, 0, 0}));
            assertEquals(GroundCompact.gpuBytes(), arenas.bytes());
        }
    }

    @Test
    void nativeMaskCopiesTheCurrentPassSetAndPreservesAllSixFaceBoundaries() {
        try (var mask = new GroundFaceArenas.NativeMask()) {
            var visible = new BitSet();
            assertFalse(mask.update(visible));
            for (int face = 0; face < 6; face++) {
                visible.set(face * 16384);
                visible.set(face * 16384 + 127);
                visible.set(face * 16384 + 127 * 128);
                visible.set(face * 16384 + 16383);
            }
            assertTrue(mask.update(visible));
            for (int face = 0; face < 6; face++) {
                assertEquals(1, mask.value(face, 0, 0));
                assertEquals(1, mask.value(face, 127, 0));
                assertEquals(1, mask.value(face, 0, 127));
                assertEquals(1, mask.value(face, 127, 127));
                assertEquals(0, mask.value(face, 64, 64));
            }
            assertFalse(mask.update((BitSet) visible.clone()));
            // Opaque and water invoke update separately: removed native tiles must become zero.
            visible.clear();
            visible.set(5 * 16384 + 127 * 128 + 126);
            assertEquals(1, mask.value(0, 0, 0)); // Caller mutation cannot change cached image.
            assertTrue(mask.update(visible));
            assertEquals(0, mask.value(0, 0, 0));
            assertEquals(0, mask.value(5, 127, 127));
            assertEquals(1, mask.value(5, 126, 127));
        }
    }

    @Test
    void maskRejectsOutOfFaceBitsBeforeChangingTheImageAndCloseIsIdempotent() {
        var mask = new GroundFaceArenas.NativeMask();
        try {
            var visible = new BitSet();
            visible.set(98303);
            assertTrue(mask.update(visible));
            visible.set(98304);
            assertThrows(IllegalArgumentException.class, () -> mask.update(visible));
            assertEquals(1, mask.value(5, 127, 127));
        } finally {
            mask.close();
            mask.close();
        }
        assertThrows(IllegalStateException.class, () -> mask.update(new BitSet()));
    }

    @Test
    void layersKeepVanillaStateWhileSelectingStaticCompactPrograms() {
        assertEquals(GroundCompact.Variant.SOLID, GroundCompact.variant(RenderType.solid(), false));
        assertEquals(
                GroundCompact.Variant.CUTOUT, GroundCompact.variant(RenderType.cutout(), false));
        assertEquals(
                GroundCompact.Variant.CUTOUT,
                GroundCompact.variant(RenderType.cutoutMipped(), false));
        assertEquals(
                GroundCompact.Variant.TRANSLUCENT,
                GroundCompact.variant(RenderType.translucent(), false));
        assertEquals(
                GroundCompact.Variant.TRANSLUCENT,
                GroundCompact.variant(RenderType.tripwire(), false));
        for (int layer = 0; layer < GroundFaceArenas.LAYERS; layer++) {
            RenderType type = GroundFaceArenas.layer(layer);
            assertEquals(layer, GroundFaceArenas.layerIndex(type));
            assertEquals(GroundCompact.Variant.MASKED, GroundCompact.variant(type, true));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> GroundFaceArenas.layerIndex(RenderType.lines()));
    }

    @Test
    void independentUploadHandlesRejectInvalidGeometryAndCancellationNeedsNoGl() {
        try (var arenas = new GroundFaceArenas()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> arenas.beginUpload(6, RenderType.solid(), ByteBuffer.allocate(48)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> arenas.beginUpload(0, RenderType.solid(), ByteBuffer.allocate(12)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> arenas.beginUpload(0, RenderType.solid(), ByteBuffer.allocate(0)));
            var upload = arenas.beginUpload(5, RenderType.cutout(), ByteBuffer.allocate(48));
            assertEquals(48, upload.remainingBytes());
            assertThrows(IllegalStateException.class, upload::finish);
            upload.close();
            upload.close();
            assertThrows(IllegalStateException.class, upload::finish);
            assertTrue(arenas.reservedBytes() >= 128L * 768);
            assertEquals(0, arenas.bytes());
        }
    }

    @Test
    void materialGateDecodesAll24BitsWithoutConsumingSourceAndDeadlinePreventsGlWork() {
        var source = ByteBuffer.allocate(96).order(java.nio.ByteOrder.nativeOrder());
        source.position(48);
        source.putShort(54, (short) 0x3456);
        source.putShort(56, (short) 0x12ef);
        assertEquals(0x123456, GroundFaceArenas.materialId(source, 48));
        assertEquals(48, source.position());
        try (var arenas = new GroundFaceArenas()) {
            var upload = arenas.beginUpload(0, RenderType.solid(), source);
            assertFalse(upload.step(new GroundUploadBudget(0, 4096, () -> 0)));
            assertEquals(0, upload.uploadedBytes());
            assertThrows(IllegalStateException.class, upload::finish);
            assertEquals(0, arenas.bytes());
        }
    }

    @Test
    void completedMappingSuppliesGlobalMaterialBoundaryAndLegacyUploadsStillScan() throws Exception {
        var source = ByteBuffer.allocate(48).order(java.nio.ByteOrder.nativeOrder());
        source.putShort(6, (short) 0x3456).putShort(8, (short) 0x12ef);
        var checked = GroundFaceArenas.Upload.class.getDeclaredField("checkedBytes");
        var count = GroundFaceArenas.Upload.class.getDeclaredField("materialCount");
        checked.setAccessible(true);
        count.setAccessible(true);
        try (var arenas = new GroundFaceArenas()) {
            var mapped = arenas.beginUpload(0, RenderType.solid(), source, 0x123457);
            var legacy = arenas.beginUpload(0, RenderType.solid(), source);
            assertEquals(48, checked.getInt(mapped));
            assertEquals(0x123457, count.getInt(mapped), "global ids can exceed local palette size");
            assertEquals(0, checked.getInt(legacy));
            assertEquals(0, source.position());
            assertThrows(IllegalArgumentException.class,
                    () -> arenas.beginUpload(0, RenderType.solid(), source, 0x1000001));
            assertThrows(IllegalArgumentException.class,
                    () -> arenas.beginUpload(0, RenderType.solid(), source, -2));
        }
    }

    @Test
    void unpublishedMaterialMemoryRejectionCannotAllocateOrFinishANode() {
        try (var arenas = new GroundFaceArenas(0)) {
            assertFalse(arenas.materialsReady(1));
            var upload = arenas.beginUpload(0, RenderType.solid(), ByteBuffer.allocate(48));
            assertFalse(upload.step(new GroundUploadBudget(Long.MAX_VALUE, 8192, () -> 0)));
            assertTrue(upload.allocationRejected());
            assertEquals(0, upload.uploadedBytes());
            assertEquals(0, arenas.bytes());
            assertThrows(IllegalStateException.class, upload::finish);
        }
    }
}
