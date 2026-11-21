package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.api.RenderWorkDiagnostics;
import dev.starfold.api.RenderWorkDiagnostics.Metric;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class GroundNativeCoverageTest {
    private static BitSet bits(int... values) {
        var out = new BitSet();
        for (int v : values) out.set(v);
        return out;
    }

    @Test
    void missingOrUncompiledSurfaceKeepsFallback() {
        assertFalse(GroundNativeCoverage.surfaceReady(bits(), bits(), all()));
        assertFalse(GroundNativeCoverage.surfaceReady(bits(0, 1, 10, 11), bits(0, 1), all()));
        assertFalse(GroundNativeCoverage.surfaceReady(bits(0, 1, 10, 11), bits(11), all()));
        // Drawing only the top two is insufficient when lower occupied sections are listed.
        assertFalse(GroundNativeCoverage.surfaceReady(bits(0, 1, 10, 11), bits(10, 11), all()));
        assertTrue(
                GroundNativeCoverage.surfaceReady(bits(0, 1, 10, 11), bits(0, 1, 10, 11), all()));
        assertTrue(GroundNativeCoverage.surfaceReady(bits(40), bits(40), all()));
    }

    @Test
    void highAltitudeAndNearPlayerAirNeverOwnUndrawnGround() {
        // The camera is 200 blocks above ground in the same horizontal chunk. Vanilla's
        // visible list can contain compiled EMPTY air without drawing the ground section.
        var ground = bits(0, 1, 10, 11);
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(24), all()));
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(), all()));
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(10), all()));
        assertFalse(GroundNativeCoverage.surfaceReady(bits(), bits(24), all()));
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(10, 11), all()));
        assertTrue(GroundNativeCoverage.surfaceReady(ground, bits(0, 1, 10, 11), all()));
        // Next frame the ground is culled: last frame's successful draw grants no ownership.
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(), all()));
    }

    @Test
    void tallCliffRequiresEveryListedOccupiedSectionBeforeSuppressingTheColumn() {
        var occupied = bits(0, 1, 20, 21, 22, 23, 40);
        var listed = bits(20, 21, 22, 23);
        // Four exposed sections: the top two cannot replace the lower cliff face.
        assertFalse(GroundNativeCoverage.surfaceReady(occupied, bits(22, 23), listed));
        assertFalse(GroundNativeCoverage.surfaceReady(occupied, bits(21, 22, 23), listed));
        assertFalse(GroundNativeCoverage.surfaceReady(occupied, bits(20, 22, 23), listed));
        // Unlisted buried sections and canopy still do not hold back coverage.
        assertTrue(GroundNativeCoverage.surfaceReady(occupied, bits(20, 21, 22, 23), listed));
    }

    @Test
    void highAltitudeCliffAcrossBitSetWordsStillRequiresEveryListedDraw() {
        var occupied = bits(0, 63, 64, 127, 255, 600);
        // Sparse listed sections span multiple words; 350 and 511 contain only air.
        // Buried section 0 and canopy section 600 are outside the visible list.
        var listed = bits(63, 64, 127, 255, 350, 511);
        assertFalse(GroundNativeCoverage.surfaceReady(occupied, bits(350, 511), listed));
        assertFalse(GroundNativeCoverage.surfaceReady(occupied, bits(127, 255), listed));
        assertFalse(GroundNativeCoverage.surfaceReady(occupied, bits(64, 127, 255), listed));
        assertTrue(GroundNativeCoverage.surfaceReady(occupied, bits(63, 64, 127, 255), listed));
        // A later frame or a list containing only high-altitude air cannot own ground.
        assertFalse(GroundNativeCoverage.surfaceReady(occupied, bits(), listed));
        assertFalse(GroundNativeCoverage.surfaceReady(occupied, bits(350, 511), bits(350, 511)));
    }

    @Test
    void buriedSectionBelowTheDrawnSurfaceNeedNotBeDrawn() {
        var ground = bits(0, 1, 10, 11);
        // Ground and canopy share section 11; solid section 10 is occluded, never listed or drawn.
        assertTrue(GroundNativeCoverage.surfaceReady(ground, bits(11), bits(11)));
        // Listed as visible but not drawn yet: it would show a hole, so keep the fallback.
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(11), bits(10, 11)));
        // An unlisted section never stands in for the top surface itself.
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(10), bits()));
    }

    @Test
    void canopyAboveTheCameraOutOfViewDoesNotHandTheChunkBack() {
        // Looking down in the player's own chunk: canopy section 11 is outside the frustum,
        // unlisted.
        var ground = bits(0, 1, 10, 11);
        assertTrue(GroundNativeCoverage.surfaceReady(ground, bits(10), bits(10)));
        assertTrue(GroundNativeCoverage.surfaceReady(ground, bits(1, 10), bits(1, 10)));
        // The listed ground itself, and a listed section below it, still have to be drawn.
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(), bits(10)));
        assertFalse(GroundNativeCoverage.surfaceReady(ground, bits(10), bits(1, 10)));
    }

    @Test
    void translucentSubmissionMayCompleteButNeverPrecedeTheSurface() {
        var occupied = bits(10, 11);
        var listed = bits(10, 11);
        var submitted = bits(10);
        assertFalse(GroundNativeCoverage.surfaceReady(occupied, submitted, listed));
        // Section 11 has only translucent geometry, submitted after AFTER_ENTITIES.
        submitted.set(11);
        assertTrue(GroundNativeCoverage.surfaceReady(occupied, submitted, listed));
        assertFalse(GroundNativeCoverage.surfaceReady(occupied, bits(), listed));
    }

    @Test
    void actualSubmissionOrderOwnsOpaqueBeforeLateOnlySectionsWithoutUsingLastFrame() {
        long column = 8256L;
        var occupied = bits(10, 63, 64, 65, 66);
        var compiled =
                List.of(
                        compiled(RenderType.solid()),
                        compiled(RenderType.cutoutMipped()),
                        compiled(RenderType.cutout()),
                        compiled(RenderType.translucent()),
                        compiled(RenderType.tripwire()));
        int[] indices = {10, 63, 64, 65, 66};
        var opaque = GroundNativeCoverage.pass(false);
        var complete = GroundNativeCoverage.pass(true);
        try {
            for (int frame = 0; frame < 3; frame++) {
                GroundNativeCoverage.resetDraws(); // renderLevel HEAD, before native opaque layers
                GroundNativeCoverage.setupRender();
                for (int i = 0; i < indices.length; i++)
                    GroundNativeCoverage.recordListed(column, 80, indices[i], compiled.get(i));
                var water = bits();
                assertFalse(opaque.ready(column, 80, occupied::get));
                assertFalse(complete.ready(column, 80, occupied::get));
                GroundNativeCoverage.recordDraw(column, 80, 10, RenderType.solid(), water);
                GroundNativeCoverage.recordDraw(column, 80, 63, RenderType.cutoutMipped(), water);
                assertFalse(
                        opaque.ready(column, 80, occupied::get),
                        "the lower exposed cutout section still has no opaque draw");
                if (frame == 2) {
                    // Losing one real draw must retain fallback despite two completed prior frames.
                    GroundNativeCoverage.recordDraw(
                            column, 80, 64, RenderType.translucent(), water);
                    assertFalse(
                            opaque.ready(column, 80, occupied::get),
                            "a translucent submission cannot stand in for an opaque draw");
                    continue;
                }
                GroundNativeCoverage.recordDraw(column, 80, 64, RenderType.cutout(), water);
                // AFTER_ENTITIES: full readiness is false, yet actual opaque native draws own
                // cover.
                assertTrue(opaque.ready(column, 80, occupied::get));
                assertFalse(complete.ready(column, 80, occupied::get));
                GroundNativeCoverage.recordDraw(column, 80, 65, RenderType.translucent(), water);
                assertFalse(complete.ready(column, 80, occupied::get));
                GroundNativeCoverage.recordDraw(column, 80, 66, RenderType.tripwire(), water);
                // AFTER_PARTICLES: both native late layers have now drawn.
                assertTrue(complete.ready(column, 80, occupied::get));
                assertTrue(opaque.ready(column, 80, occupied::get));
            }
        } finally {
            GroundNativeCoverage.resetDraws();
            GroundNativeCoverage.setupRender();
        }
    }

    @Test
    void opaqueRequirementsKeepUnknownMixedAndCliffSectionsButExcludeEmptyAndLateOnly() {
        assertTrue(
                GroundNativeCoverage.requiresOpaqueSubmission(
                        SectionRenderDispatcher.CompiledSection.UNCOMPILED));
        assertFalse(
                GroundNativeCoverage.requiresOpaqueSubmission(
                        SectionRenderDispatcher.CompiledSection.EMPTY));
        assertFalse(
                GroundNativeCoverage.requiresOpaqueSubmission(
                        compiled(RenderType.translucent(), RenderType.tripwire())));
        assertTrue(
                GroundNativeCoverage.requiresOpaqueSubmission(
                        compiled(RenderType.solid(), RenderType.translucent())));
        assertTrue(GroundNativeCoverage.requiresOpaqueSubmission(compiled(RenderType.cutout())));
    }

    private static SectionRenderDispatcher.CompiledSection compiled(RenderType... layers) {
        var present = Set.of(layers);
        return new SectionRenderDispatcher.CompiledSection() {
            @Override
            public boolean isEmpty(RenderType layer) {
                return !present.contains(layer);
            }

            @Override
            public boolean hasNoRenderableLayers() {
                return present.isEmpty();
            }
        };
    }

    @Test
    void finalWindowIgnoresOpaqueGapsAcrossFramesAndExitsOnlyAfterFinalLoss() {
        var completed = new GroundNativeCoverage.FinalWindow();
        var observed = new ArrayList<Boolean>();
        var occupied = bits(10, 11);
        var listed = bits(10, 11);
        long tile = 8256L;
        assertTrue(completed.snapshot().isEmpty());
        for (int frame = 0; frame < 4; frame++) {
            // beginFrame clears the actual submissions, but not completed-frame evidence.
            var submitted = bits();
            assertFalse(GroundNativeCoverage.surfaceReady(occupied, submitted, listed));
            assertEquals(frame == 1 || frame == 2, completed.snapshot().contains(tile));
            submitted.set(10);
            boolean opaque = GroundNativeCoverage.surfaceReady(occupied, submitted, listed);
            assertFalse(opaque, "the water-only section has not drawn yet");
            assertFalse(completed.complete(false, opaque ? Set.of(tile) : Set.of()));
            assertEquals(frame == 1 || frame == 2, completed.snapshot().contains(tile));
            // AFTER_PARTICLES in two approach frames; then two frames after losing coverage.
            if (frame < 2) submitted.set(11);
            boolean translucent = GroundNativeCoverage.surfaceReady(occupied, submitted, listed);
            assertTrue(completed.complete(true, translucent ? Set.of(tile) : Set.of()));
            observed.add(completed.snapshot().contains(tile));
        }
        assertEquals(List.of(true, true, false, false), observed);
        int exits = 0;
        for (int frame = 1; frame < observed.size(); frame++)
            if (observed.get(frame - 1) && !observed.get(frame)) exits++;
        assertEquals(1, exits);
    }

    @Test
    void completedWindowIsImmutableAndResetCannotLeakPreviousContext() {
        var completed = new GroundNativeCoverage.FinalWindow();
        var current = new HashSet<>(Set.of(8256L));
        completed.complete(true, current);
        var retained = completed.snapshot();
        current.clear();
        assertEquals(Set.of(8256L), retained);
        assertThrows(UnsupportedOperationException.class, () -> retained.clear());
        completed.reset();
        assertTrue(completed.snapshot().isEmpty());
        assertEquals(Set.of(8256L), retained);
        assertFalse(completed.complete(false, Set.of(7L)));
        assertTrue(completed.snapshot().isEmpty());
        completed.complete(true, Set.of(7L));
        assertEquals(Set.of(7L), completed.snapshot());
    }

    @Test
    void compiledEmptyIsExcludedButUnknownAndRenderableSectionsStillBlock() {
        var required = bits(10);
        assertFalse(GroundNativeCoverage.requiresSubmission(false, true));
        for (boolean uncompiled : new boolean[] {false, true}) {
            boolean empty = uncompiled;
            assertTrue(GroundNativeCoverage.requiresSubmission(uncompiled, empty));
            if (GroundNativeCoverage.requiresSubmission(uncompiled, empty)) required.set(11);
            assertFalse(GroundNativeCoverage.surfaceReady(bits(10, 11), bits(10), required));
            required.clear(11);
        }
        // A compiled-empty buried section has no geometry to submit and is not required.
        if (GroundNativeCoverage.requiresSubmission(false, true)) required.set(11);
        assertTrue(GroundNativeCoverage.surfaceReady(bits(10, 11), bits(10), required));
    }

    @Test
    void mixedSeabedSectionNeedsANewCoverageDecisionWhenWaterActuallyDraws() {
        var submitted = bits();
        var water = bits();
        assertTrue(GroundNativeCoverage.recordSubmission(submitted, water, 40, false));
        assertTrue(submitted.get(40));
        assertFalse(water.get(40));
        assertFalse(GroundNativeCoverage.recordSubmission(submitted, water, 40, false));
        // Previously any-layer bit 40 was already set, so water could not dirty the column.
        assertTrue(GroundNativeCoverage.recordSubmission(submitted, water, 40, true));
        assertTrue(water.get(40));
        assertFalse(GroundNativeCoverage.recordSubmission(submitted, water, 40, true));
    }

    @org.junit.jupiter.api.BeforeAll
    static void registry() {
        dev.starfold.worldgen.PlanetTerrainTest.registry();
    }

    @Test
    void actualWaterSurfaceGateWaitsForTranslucentEvenAfterOpaqueSubmission() {
        var chunk = surfaceChunk();
        top(chunk, 0, 127, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        var any = bits();
        var water = bits();
        int section = chunk.getSectionIndex(127);
        GroundNativeCoverage.recordSubmission(any, water, section, false);
        assertFalse(GroundNativeCoverage.waterSurfaceReady(chunk, water));
        GroundNativeCoverage.recordSubmission(any, water, section, true);
        assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, water));
        assertFalse(
                GroundNativeCoverage.waterSurfaceReady(chunk, bits()),
                "new frame has no water submission");
    }

    @Test
    void opaqueCoverDoesNotWaitForWaterButLateCoverStillRequiresItsActualDraw() {
        var chunk = surfaceChunk();
        top(chunk, 0, 127, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        top(chunk, 1, 120, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        long column = chunk.getPos().toLong();
        int section = chunk.getSectionIndex(127);
        var occupied = bits(section);
        var water = bits();
        var opaque = GroundNativeCoverage.pass(false);
        var complete = GroundNativeCoverage.pass(true);
        try {
            GroundNativeCoverage.resetDraws();
            GroundNativeCoverage.setupRender();
            GroundNativeCoverage.recordListed(
                    column,
                    chunk.getSectionsCount(),
                    section,
                    compiled(RenderType.solid(), RenderType.translucent()));
            GroundNativeCoverage.recordDraw(
                    column, chunk.getSectionsCount(), section, RenderType.solid(), water);
            assertTrue(opaque.ready(column, chunk.getSectionsCount(), occupied::get));
            assertTrue(complete.ready(column, chunk.getSectionsCount(), occupied::get));
            assertFalse(
                    GroundNativeCoverage.waterSurfaceReady(chunk, water),
                    "AFTER_ENTITIES can hide opaque cover but must retain coarse water");
            GroundNativeCoverage.recordListed(
                    column + 1,
                    chunk.getSectionsCount(),
                    section,
                    compiled(RenderType.translucent()));
            GroundNativeCoverage.recordDraw(
                    column + 1,
                    chunk.getSectionsCount(),
                    section,
                    RenderType.translucent(),
                    bits());
            assertFalse(
                    opaque.ready(column + 1, chunk.getSectionsCount(), occupied::get),
                    "a column with no required opaque surface cannot claim opaque ownership");
            complete.dirty.clear(); // The early readiness evaluation could not cover water.
            GroundNativeCoverage.recordDraw(
                    column, chunk.getSectionsCount(), section, RenderType.translucent(), water);
            assertTrue(
                    complete.dirty.contains(column),
                    "the later water draw must trigger a new decision for the mixed section");
            assertTrue(complete.ready(column, chunk.getSectionsCount(), occupied::get));
            assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, water));
            GroundNativeCoverage.resetDraws();
            assertFalse(opaque.ready(column, chunk.getSectionsCount(), occupied::get));
            assertFalse(complete.ready(column, chunk.getSectionsCount(), occupied::get));
        } finally {
            GroundNativeCoverage.resetDraws();
            GroundNativeCoverage.setupRender();
        }
    }

    @Test
    void actualWaterSurfaceGateRequiresEachExposedSectionIncludingAquaticPlants() {
        var chunk = surfaceChunk();
        top(chunk, 0, 127, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        top(chunk, 1, 160, net.minecraft.world.level.block.Blocks.SEAGRASS.defaultBlockState());
        top(chunk, 2, 192, net.minecraft.world.level.block.Blocks.KELP.defaultBlockState());
        top(chunk, 3, 224, net.minecraft.world.level.block.Blocks.KELP_PLANT.defaultBlockState());
        var water = bits(chunk.getSectionIndex(127));
        for (int y : new int[] {160, 192, 224}) {
            assertFalse(
                    GroundNativeCoverage.waterSurfaceReady(chunk, water),
                    "missing water layer at " + y);
            water.set(chunk.getSectionIndex(y));
        }
        assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, water));
    }

    @Test
    void actualWaterSurfaceGateIgnoresDryTopsBuriedWaterAndLavaLayer() {
        var chunk = surfaceChunk();
        top(chunk, 0, 100, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        top(chunk, 0, 127, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        top(chunk, 1, 160, net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState());
        top(chunk, 2, 192, net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState());
        assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, bits()));
        // Replacing the dry top with waterlogged geometry must wait for the water pass too.
        top(
                chunk,
                2,
                192,
                net.minecraft.world.level.block.Blocks.OAK_SLAB
                        .defaultBlockState()
                        .setValue(
                                net.minecraft.world.level.block.state.properties
                                        .BlockStateProperties.WATERLOGGED,
                                true));
        assertFalse(GroundNativeCoverage.waterSurfaceReady(chunk, bits()));
        assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, bits(chunk.getSectionIndex(192))));
    }

    @Test
    void dryPalettesAndAlreadyDrawnWaterSkipEveryHeightLookup() {
        var chunk = surfaceChunk();
        top(chunk, 0, 127, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        top(chunk, 1, 160, net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState());
        chunk.heightLookups = 0;
        chunk.fluidLookups = 0;
        assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, bits()));
        assertEquals(0, chunk.heightLookups);

        top(chunk, 2, 192, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        chunk.heightLookups = 0;
        chunk.fluidLookups = 0;
        assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, bits(chunk.getSectionIndex(192))));
        assertEquals(0, chunk.heightLookups);
        assertFalse(GroundNativeCoverage.waterSurfaceReady(chunk, bits()));
        assertTrue(chunk.heightLookups > 0, "undrawn water must reach the exposed-top scan");
    }

    @Test
    void currentPaletteMutationsCannotReuseDryOrDrawnWaterReadiness() {
        var chunk = surfaceChunk();
        top(chunk, 0, 127, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, bits()));
        var section = chunk.getSection(chunk.getSectionIndex(127));
        top(
                chunk,
                0,
                127,
                net.minecraft.world.level.block.Blocks.WATER
                        .defaultBlockState()
                        .setValue(
                                net.minecraft.world.level.block.state.properties
                                        .BlockStateProperties.LEVEL,
                                3));
        assertSame(section, chunk.getSection(chunk.getSectionIndex(127)));
        assertFalse(GroundNativeCoverage.waterSurfaceReady(chunk, bits()));
        assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, bits(chunk.getSectionIndex(127))));
        // A newly wet section is undrawn even when another section's translucent layer drew.
        top(
                chunk,
                1,
                160,
                net.minecraft.world.level.block.Blocks.OAK_SLAB
                        .defaultBlockState()
                        .setValue(
                                net.minecraft.world.level.block.state.properties
                                        .BlockStateProperties.WATERLOGGED,
                                true));
        assertFalse(
                GroundNativeCoverage.waterSurfaceReady(chunk, bits(chunk.getSectionIndex(127))));
        top(chunk, 0, 127, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        top(chunk, 1, 160, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, bits()));
    }

    @Test
    void unknownPaletteDataFallsBackToTheExistingSurfaceScan() {
        var chunk = surfaceChunk();
        top(chunk, 0, 127, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        chunk.unknownLastSection = true;
        chunk.heightLookups = 0;
        chunk.fluidLookups = 0;
        assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, bits()));
        assertEquals(256, chunk.heightLookups, "unknown palettes cannot authorize the shortcut");
        chunk.unknownLastSection = false;
        top(chunk, 1, 160, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        chunk.unknownLastSection = true;
        assertFalse(GroundNativeCoverage.waterSurfaceReady(chunk, bits()));
    }

    private static long[] capture(Runnable work) {
        var frames = new ArrayList<long[]>();
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        try {
            RenderWorkDiagnostics.beginFrame();
            work.run();
            RenderWorkDiagnostics.endFrame();
            assertEquals(1, frames.size());
            return frames.getFirst();
        } finally {
            RenderWorkDiagnostics.register(null);
        }
    }

    private static long metric(long[] frame, Metric metric) {
        return frame[metric.ordinal()];
    }

    @Test
    void diagnosticsCountActualPaletteFastpathAndPartialFallbackWork() {
        var chunk = surfaceChunk();
        top(chunk, 0, 127, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        chunk.heightLookups = 0;
        chunk.fluidLookups = 0;
        var dry = capture(() -> assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, bits())));
        assertEquals(1, metric(dry, Metric.NATIVE_WATER_CALLS));
        assertEquals(chunk.getSectionsCount(), metric(dry, Metric.NATIVE_WATER_PALETTE_SECTIONS));
        assertEquals(1, metric(dry, Metric.NATIVE_WATER_FAST_ACCEPTS));
        assertEquals(0, metric(dry, Metric.NATIVE_WATER_FALLBACKS));
        assertTrue(metric(dry, Metric.NATIVE_WATER_PALETTE_NS) > 0);
        assertEquals(0, metric(dry, Metric.NATIVE_WATER_SCAN_NS));
        assertEquals(0, metric(dry, Metric.NATIVE_WATER_SURFACE_CELLS));
        assertEquals(0, metric(dry, Metric.NATIVE_WATER_FLUID_LOOKUPS));
        assertEquals(0, chunk.heightLookups);

        top(chunk, 2, 127, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        chunk.heightLookups = 0;
        chunk.fluidLookups = 0;
        var wet = capture(() -> assertFalse(GroundNativeCoverage.waterSurfaceReady(chunk, bits())));
        assertEquals(
                chunk.getSectionIndex(127) + 1, metric(wet, Metric.NATIVE_WATER_PALETTE_SECTIONS));
        assertEquals(0, metric(wet, Metric.NATIVE_WATER_FAST_ACCEPTS));
        assertEquals(1, metric(wet, Metric.NATIVE_WATER_FALLBACKS));
        assertTrue(metric(wet, Metric.NATIVE_WATER_PALETTE_NS) > 0);
        assertTrue(metric(wet, Metric.NATIVE_WATER_SCAN_NS) > 0);
        assertEquals(3, chunk.heightLookups);
        assertEquals(chunk.heightLookups, metric(wet, Metric.NATIVE_WATER_SURFACE_CELLS));
        assertEquals(chunk.fluidLookups, metric(wet, Metric.NATIVE_WATER_FLUID_LOOKUPS));
        assertTrue(chunk.fluidLookups > 0);

        chunk.heightLookups = 0;
        chunk.fluidLookups = 0;
        var drawn =
                capture(
                        () ->
                                assertTrue(
                                        GroundNativeCoverage.waterSurfaceReady(
                                                chunk, bits(chunk.getSectionIndex(127)))));
        assertEquals(
                chunk.getSectionsCount() - 1, metric(drawn, Metric.NATIVE_WATER_PALETTE_SECTIONS));
        assertEquals(1, metric(drawn, Metric.NATIVE_WATER_FAST_ACCEPTS));
        assertEquals(0, metric(drawn, Metric.NATIVE_WATER_FALLBACKS));
        assertEquals(0, metric(drawn, Metric.NATIVE_WATER_SCAN_NS));
        assertEquals(0, chunk.heightLookups);
    }

    @Test
    void diagnosticsCountAllFallbackCellsAndPreserveExceptionEvidence() {
        var chunk = surfaceChunk();
        top(chunk, 0, 100, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        top(chunk, 0, 127, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        chunk.heightLookups = 0;
        chunk.fluidLookups = 0;
        var buried =
                capture(() -> assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, bits())));
        assertEquals(256, chunk.heightLookups);
        assertEquals(256, metric(buried, Metric.NATIVE_WATER_SURFACE_CELLS));
        assertEquals(chunk.fluidLookups, metric(buried, Metric.NATIVE_WATER_FLUID_LOOKUPS));
        assertEquals(0, chunk.fluidLookups, "dry top section needs no fluid reads");
        assertEquals(0, metric(buried, Metric.NATIVE_WATER_FAST_ACCEPTS));
        assertEquals(1, metric(buried, Metric.NATIVE_WATER_FALLBACKS));

        chunk.failHeight = true;
        var failed =
                capture(
                        () ->
                                assertThrows(
                                        IllegalStateException.class,
                                        () ->
                                                GroundNativeCoverage.waterSurfaceReady(
                                                        chunk, bits())));
        assertEquals(1, metric(failed, Metric.NATIVE_WATER_CALLS));
        assertEquals(1, metric(failed, Metric.NATIVE_WATER_FALLBACKS));
        assertTrue(metric(failed, Metric.NATIVE_WATER_SCAN_NS) > 0);
        assertEquals(1, metric(failed, Metric.NATIVE_WATER_SURFACE_CELLS));
        assertEquals(0, metric(failed, Metric.NATIVE_WATER_FLUID_LOOKUPS));
        assertTrue(metric(failed, Metric.NATIVE_WATER_NS) > 0);
    }

    @Test
    void inactiveDiagnosticsEmitNothingAndOpaqueReadinessDoesNotScanWater() {
        var chunk = surfaceChunk();
        top(chunk, 0, 127, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        var frames = new ArrayList<long[]>();
        RenderWorkDiagnostics.register(values -> frames.add(values.clone()));
        try {
            assertFalse(GroundNativeCoverage.waterSurfaceReady(chunk, bits()));
            RenderWorkDiagnostics.endFrame();
            assertTrue(frames.isEmpty());
        } finally {
            RenderWorkDiagnostics.register(null);
        }
        var state = GroundNativeCoverage.pass(false);
        long column = chunk.getPos().toLong();
        int section = chunk.getSectionIndex(127);
        try {
            GroundNativeCoverage.resetDraws();
            GroundNativeCoverage.setupRender();
            GroundNativeCoverage.recordListed(
                    column,
                    chunk.getSectionsCount(),
                    section,
                    compiled(RenderType.solid(), RenderType.translucent()));
            chunk.heightLookups = 0;
            chunk.fluidLookups = 0;
            var frame =
                    capture(
                            () -> {
                                GroundNativeCoverage.recordDraw(
                                        column,
                                        chunk.getSectionsCount(),
                                        section,
                                        RenderType.solid(),
                                        bits());
                                assertTrue(
                                        state.ready(column, chunk.getSectionsCount(), i -> true));
                            });
            assertEquals(1, metric(frame, Metric.NATIVE_SUBMITTED_SECTIONS));
            assertEquals(1, metric(frame, Metric.NATIVE_READY_TESTS));
            assertEquals(0, metric(frame, Metric.NATIVE_WATER_CALLS));
            assertEquals(0, chunk.heightLookups);
        } finally {
            GroundNativeCoverage.resetDraws();
            GroundNativeCoverage.setupRender();
        }
    }

    @Test
    void diagnosticsReadinessStopsAtMissingDrawAndTimesPropagatedErrors() {
        long column = 8256L;
        var state = GroundNativeCoverage.pass(false);
        int[] tested = {0};
        try {
            GroundNativeCoverage.resetDraws();
            GroundNativeCoverage.setupRender();
            for (int index : new int[] {10, 11, 12})
                GroundNativeCoverage.recordListed(column, 80, index, compiled(RenderType.solid()));
            GroundNativeCoverage.recordDraw(column, 80, 10, RenderType.solid(), bits());
            var missing =
                    capture(
                            () ->
                                    assertFalse(
                                            state.ready(
                                                    column,
                                                    80,
                                                    index -> {
                                                        tested[0]++;
                                                        return true;
                                                    })));
            assertEquals(2, tested[0], "stop at missing section 11, before section 12");
            assertEquals(1, metric(missing, Metric.NATIVE_READY_TESTS));
            assertEquals(tested[0], metric(missing, Metric.NATIVE_READY_SECTIONS));
            assertEquals(0, metric(missing, Metric.NATIVE_WATER_CALLS));
            assertTrue(metric(missing, Metric.NATIVE_READY_NS) > 0);

            var failed =
                    capture(
                            () ->
                                    assertThrows(
                                            IllegalArgumentException.class,
                                            () ->
                                                    state.ready(
                                                            column,
                                                            80,
                                                            index -> {
                                                                throw new IllegalArgumentException(
                                                                        "occupied lookup failed");
                                                            })));
            assertEquals(1, metric(failed, Metric.NATIVE_READY_TESTS));
            assertEquals(1, metric(failed, Metric.NATIVE_READY_SECTIONS));
            assertTrue(metric(failed, Metric.NATIVE_READY_NS) > 0);
        } finally {
            GroundNativeCoverage.resetDraws();
            GroundNativeCoverage.setupRender();
        }
    }

    @Test
    void diagnosticsReadinessCountsOnlyReachedListedPredicatesIncludingAir() {
        long column = 8256L;
        var state = GroundNativeCoverage.pass(false);
        var actualChecks = new ArrayList<Integer>();
        try {
            GroundNativeCoverage.resetDraws();
            GroundNativeCoverage.setupRender();
            for (int index : new int[] {10, 11, 12, 70})
                GroundNativeCoverage.recordListed(column, 80, index, compiled(RenderType.solid()));
            GroundNativeCoverage.recordDraw(column, 80, 10, RenderType.solid(), bits());
            GroundNativeCoverage.recordDraw(column, 80, 12, RenderType.solid(), bits());
            var frame =
                    capture(
                            () ->
                                    assertTrue(
                                            state.ready(
                                                    column,
                                                    64,
                                                    index -> {
                                                        actualChecks.add(index);
                                                        return index != 11;
                                                    })));
            assertEquals(List.of(10, 11, 12), actualChecks);
            assertEquals(actualChecks.size(), metric(frame, Metric.NATIVE_READY_SECTIONS));
            assertEquals(1, metric(frame, Metric.NATIVE_READY_TESTS));
        } finally {
            GroundNativeCoverage.resetDraws();
            GroundNativeCoverage.setupRender();
        }
    }

    @Test
    void diagnosticsCountsPassInvocationsWhenNoLevelCanBeVisited()
            throws ReflectiveOperationException {
        var clientType = net.minecraft.client.Minecraft.class;
        var instance = clientType.getDeclaredField("instance");
        instance.setAccessible(true);
        var previousClient = instance.get(null);
        var unsafeType = Class.forName("sun.misc.Unsafe");
        var singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        // No client constructor, render loop, workers or GL context: only the no-level branch runs.
        var client =
                unsafeType
                        .getMethod("allocateInstance", Class.class)
                        .invoke(singleton.get(null), clientType);
        instance.set(null, client);
        try {
            var frame =
                    capture(
                            () -> {
                                assertTrue(GroundNativeCoverage.visible(false).isEmpty());
                                assertTrue(GroundNativeCoverage.visible(true).isEmpty());
                                assertTrue(GroundNativeCoverage.visible(true).isEmpty());
                            });
            assertEquals(3, metric(frame, Metric.NATIVE_VISIBLE_CALLS));
            assertEquals(1, metric(frame, Metric.NATIVE_OPAQUE_CALLS));
            assertEquals(2, metric(frame, Metric.NATIVE_COMPLETE_CALLS));
            for (var metric :
                    new Metric[] {
                        Metric.NATIVE_DIRTY_COLUMNS,
                        Metric.NATIVE_OPAQUE_DIRTY_COLUMNS,
                        Metric.NATIVE_COMPLETE_DIRTY_COLUMNS,
                        Metric.NATIVE_COVERED_COLUMNS,
                        Metric.NATIVE_OPAQUE_COVERED_COLUMNS,
                        Metric.NATIVE_COMPLETE_COVERED_COLUMNS,
                        Metric.NATIVE_MISSING_COLUMNS,
                        Metric.NATIVE_MAP_NS
                    }) assertEquals(0, metric(frame, metric), metric.name());
        } finally {
            instance.set(null, previousClient);
        }
    }

    @Test
    void diagnosticsPaletteFailureDoesNotClaimFallbackAndPreservesError() {
        var chunk = surfaceChunk();
        chunk.failSections = true;
        var frame =
                capture(
                        () ->
                                assertThrows(
                                        IllegalStateException.class,
                                        () ->
                                                GroundNativeCoverage.waterSurfaceReady(
                                                        chunk, bits())));
        assertEquals(1, metric(frame, Metric.NATIVE_WATER_CALLS));
        assertEquals(0, metric(frame, Metric.NATIVE_WATER_FALLBACKS));
        assertEquals(0, metric(frame, Metric.NATIVE_WATER_SURFACE_CELLS));
        assertEquals(0, metric(frame, Metric.NATIVE_WATER_SCAN_NS));
        assertTrue(metric(frame, Metric.NATIVE_WATER_PALETTE_NS) > 0);
        assertTrue(metric(frame, Metric.NATIVE_WATER_NS) > 0);
    }

    @Test
    void seededRandomSurfacesMatchExactOriginalAcrossMutationsAndSubmissions() {
        var random = new java.util.Random(0x5741544552L);
        var water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
        var states =
                new net.minecraft.world.level.block.state.BlockState[] {
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                    net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState(),
                    net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState(),
                    water,
                    water.setValue(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties
                                    .LEVEL,
                            5),
                    net.minecraft.world.level.block.Blocks.OAK_SLAB
                            .defaultBlockState()
                            .setValue(
                                    net.minecraft.world.level.block.state.properties
                                            .BlockStateProperties.WATERLOGGED,
                                    true),
                    net.minecraft.world.level.block.Blocks.SEAGRASS.defaultBlockState()
                };
        int comparisons = 0;
        for (int sample = 0; sample < 32; sample++) {
            var chunk = surfaceChunk();
            for (int cell = 0; cell < 256; cell++) {
                // Include empty columns, negative heights, section edges and buried fluids.
                if (random.nextInt(8) == 0) continue;
                int y = -64 + random.nextInt(384);
                var pos =
                        new BlockPos(
                                chunk.getPos().getMinBlockX() + cell % 16,
                                y,
                                chunk.getPos().getMinBlockZ() + cell / 16);
                chunk.setBlockState(pos, states[random.nextInt(states.length)], false);
                if (y > -64) chunk.setBlockState(pos.below(), water, false);
            }
            Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(Heightmap.Types.WORLD_SURFACE));
            for (int mutation = 0; mutation < 12; mutation++) {
                int y = new int[] {-64, -49, -48, -1, 0, 15, 16, 127, 319}[random.nextInt(9)];
                var pos =
                        new BlockPos(
                                chunk.getPos().getMinBlockX(), y, chunk.getPos().getMinBlockZ());
                var section = chunk.getSection(chunk.getSectionIndex(y));
                chunk.setBlockState(pos, states[random.nextInt(states.length)], false);
                assertSame(section, chunk.getSection(chunk.getSectionIndex(y)));
                Heightmap.primeHeightmaps(
                        chunk, java.util.EnumSet.of(Heightmap.Types.WORLD_SURFACE));
                var submitted = bits();
                for (int i = 0; i < chunk.getSectionsCount(); i++)
                    if (random.nextBoolean()) submitted.set(i);
                for (int mode = 0; mode < 4; mode++) {
                    chunk.unknownLastSection = mode == 1;
                    chunk.nullSections = mode == 2;
                    chunk.truncatedSections = mode == 3;
                    assertEquals(
                            originalWaterSurfaceReady(chunk, submitted),
                            GroundNativeCoverage.waterSurfaceReady(chunk, submitted),
                            "sample=" + sample + " mutation=" + mutation + " mode=" + mode);
                    comparisons++;
                }
                chunk.unknownLastSection = chunk.nullSections = chunk.truncatedSections = false;
                // A new frame has no previous submission evidence.
                assertEquals(
                        originalWaterSurfaceReady(chunk, bits()),
                        GroundNativeCoverage.waterSurfaceReady(chunk, bits()));
                comparisons++;
            }
        }
        assertEquals(1920, comparisons);
    }

    // Independent oracle: no palette examination or whole-column early acceptance.
    private static boolean scanOnlyWaterSurfaceReady(ChunkAccess chunk, BitSet submitted) {
        for (int cell = 0; cell < 256; cell++) {
            int x = cell % 16, z = cell / 16;
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            int section = chunk.getSectionIndex(y);
            if (section < 0 || section >= chunk.getSectionsCount() || submitted.get(section))
                continue;
            var pos =
                    new BlockPos(
                            chunk.getPos().getMinBlockX() + x,
                            y,
                            chunk.getPos().getMinBlockZ() + z);
            if (chunk.getFluidState(pos).getType().isSame(Fluids.WATER)) return false;
        }
        return true;
    }

    @Test
    void sectionMixturesMatchIndependentScanOnlyOracle() {
        var random = new java.util.Random(0x5343414eL);
        var water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        int comparisons = 0, accepted = 0, rejected = 0;
        for (int sample = 0; sample < 32; sample++) {
            var chunk = surfaceChunk();
            boolean[] wet = new boolean[chunk.getSectionsCount()];
            for (int section = 0; section < wet.length; section++)
                wet[section] = random.nextBoolean();
            wet[0] = true;
            wet[wet.length - 1] = false;
            for (int cell = 0; cell < 256; cell++) {
                int section = cell < wet.length ? cell : random.nextInt(wet.length);
                int y = -64 + section * 16 + 1 + random.nextInt(15);
                var pos =
                        new BlockPos(
                                chunk.getPos().getMinBlockX() + cell % 16,
                                y,
                                chunk.getPos().getMinBlockZ() + cell / 16);
                chunk.setBlockState(
                        pos, wet[section] && random.nextBoolean() ? water : stone, false);
                if (wet[section]) chunk.setBlockState(pos.below(), water, false);
            }
            Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(Heightmap.Types.WORLD_SURFACE));
            for (int round = 0; round < 16; round++) {
                var submitted = bits();
                for (int section = 0; section < wet.length; section++)
                    if ((round % 4 == 0 && wet[section])
                            || (round % 4 == 1 && random.nextBoolean())) submitted.set(section);
                for (int mode = 0; mode < 4; mode++) {
                    chunk.unknownLastSection = mode == 1;
                    chunk.nullSections = mode == 2;
                    chunk.truncatedSections = mode == 3;
                    boolean expected = scanOnlyWaterSurfaceReady(chunk, submitted);
                    assertEquals(
                            expected,
                            GroundNativeCoverage.waterSurfaceReady(chunk, submitted),
                            "sample=" + sample + " round=" + round + " mode=" + mode);
                    if (expected) accepted++;
                    else rejected++;
                    comparisons++;
                }
                chunk.unknownLastSection = chunk.nullSections = chunk.truncatedSections = false;
            }
        }
        assertEquals(2048, comparisons);
        assertTrue(accepted > 0 && rejected > 0);
        System.out.printf(
                "WATER_SCAN_ORACLE comparisons=%d accepted=%d rejected=%d%n",
                comparisons, accepted, rejected);
    }

    @Test
    void actualTallWetSectionsAndEveryWaterLevelMatchScanOnlyOracle() {
        var chunk =
                new SurfaceChunk(net.minecraft.world.level.LevelHeightAccessor.create(-64, 8448));
        var water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        int comparisons = 0;
        for (int section : new int[] {63, 64, 65, 511, 527}) {
            int y = -64 + section * 16 + 15;
            for (int level = 0; level < 16; level++) {
                var state =
                        water.setValue(
                                net.minecraft.world.level.block.state.properties
                                        .BlockStateProperties.LEVEL,
                                level);
                top(chunk, 0, y, state);
                var sameSection = chunk.getSection(section);
                assertTrue(
                        chunk.getFluidState(
                                        new BlockPos(
                                                chunk.getPos().getMinBlockX(),
                                                y,
                                                chunk.getPos().getMinBlockZ()))
                                .getType()
                                .isSame(Fluids.WATER));
                for (var submitted : List.of(bits(), bits(section), bits(section - 1))) {
                    assertEquals(
                            scanOnlyWaterSurfaceReady(chunk, submitted),
                            GroundNativeCoverage.waterSurfaceReady(chunk, submitted));
                    assertEquals(
                            submitted.get(section),
                            GroundNativeCoverage.waterSurfaceReady(chunk, submitted));
                    comparisons++;
                }
                top(chunk, 0, y, stone);
                assertSame(sameSection, chunk.getSection(section));
                assertTrue(scanOnlyWaterSurfaceReady(chunk, bits()));
                assertTrue(GroundNativeCoverage.waterSurfaceReady(chunk, bits()));
                comparisons++;
            }
        }
        assertEquals(320, comparisons);
        System.out.printf(
                "WATER_TALL_LEVEL_ORACLE comparisons=%d levels=16 sections=63,64,65,511,527%n",
                comparisons);
    }

    @Test
    void boundedSyntheticOperationEvidenceAgainstOriginal() {
        var chunk = surfaceChunk();
        var water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        for (int cell = 0; cell < 256; cell++) {
            int x = chunk.getPos().getMinBlockX() + cell % 16;
            int z = chunk.getPos().getMinBlockZ() + cell / 16;
            chunk.setBlockState(new BlockPos(x, 32, z), water, false);
            chunk.setBlockState(new BlockPos(x, 127, z), stone, false);
        }
        Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        operationEvidence("buried-water-dry-top", chunk, bits(), 0);
        operationEvidence("buried-water-top-drawn", chunk, bits(chunk.getSectionIndex(127)), 0);
        top(chunk, 15, 127, water);
        operationEvidence("mixed-top-water", chunk, bits(), 16);
        operationEvidence("water-top-drawn", chunk, bits(chunk.getSectionIndex(127)), 0);
        chunk.nullSections = true;
        operationEvidence("unknown-sections", chunk, bits(), 16);
        chunk.nullSections = false;
        // A coastline: most tops belong to a drawn ocean section; dry beach tops are undrawn.
        for (int cell = 0; cell < 256; cell++) {
            var pos =
                    new BlockPos(
                            chunk.getPos().getMinBlockX() + cell % 16,
                            143,
                            chunk.getPos().getMinBlockZ() + cell / 16);
            chunk.setBlockState(
                    pos,
                    cell < 44
                            ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                            : water,
                    false);
            chunk.setBlockState(pos.atY(127), stone, false);
        }
        Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        operationEvidence("coast-stale-wet-palette", chunk, bits(chunk.getSectionIndex(143)), 44);
        var coast = surfaceChunk();
        for (int cell = 0; cell < 256; cell++) {
            var pos =
                    new BlockPos(
                            coast.getPos().getMinBlockX() + cell % 16,
                            32,
                            coast.getPos().getMinBlockZ() + cell / 16);
            coast.setBlockState(pos, water, false);
            coast.setBlockState(pos.atY(127), stone, false);
            if (cell >= 44) coast.setBlockState(pos.atY(143), water, false);
        }
        Heightmap.primeHeightmaps(coast, java.util.EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        operationEvidence(
                "coast-drawn-ocean-dry-beach", coast, bits(coast.getSectionIndex(143)), 0);
        // Exposed negative-height water must still reject, including flowing water.
        var negative = surfaceChunk();
        top(
                negative,
                0,
                -49,
                water.setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL,
                        3));
        operationEvidence("negative-flowing-water", negative, bits(), 1);
    }

    private static void operationEvidence(
            String name, SurfaceChunk chunk, BitSet submitted, int expectedNewFluids) {
        boolean[] results = new boolean[2];
        var old = capture(() -> results[0] = originalWaterSurfaceReady(chunk, submitted));
        chunk.heightLookups = chunk.fluidLookups = 0;
        var current =
                capture(
                        () ->
                                results[1] =
                                        GroundNativeCoverage.waterSurfaceReady(chunk, submitted));
        assertEquals(results[0], results[1]);
        assertEquals(chunk.heightLookups, metric(current, Metric.NATIVE_WATER_SURFACE_CELLS));
        assertEquals(chunk.fluidLookups, metric(current, Metric.NATIVE_WATER_FLUID_LOOKUPS));
        assertEquals(expectedNewFluids, chunk.fluidLookups);
        assertTrue(
                metric(current, Metric.NATIVE_WATER_FLUID_LOOKUPS)
                        <= metric(old, Metric.NATIVE_WATER_FLUID_LOOKUPS));
        System.out.printf(
                "WATER_OPS %s accepted=%s heights=%d->%d fluids=%d->%d palettes=%d->%d%n",
                name,
                results[1],
                metric(old, Metric.NATIVE_WATER_SURFACE_CELLS),
                metric(current, Metric.NATIVE_WATER_SURFACE_CELLS),
                metric(old, Metric.NATIVE_WATER_FLUID_LOOKUPS),
                metric(current, Metric.NATIVE_WATER_FLUID_LOOKUPS),
                metric(old, Metric.NATIVE_WATER_PALETTE_SECTIONS),
                metric(current, Metric.NATIVE_WATER_PALETTE_SECTIONS));
    }

    // Exact pre-optimization method saved on 2026-11-13; retain its scan and palette behavior.
    private static boolean originalWaterSurfaceReady(ChunkAccess chunk, BitSet submitted) {
        boolean capture = RenderWorkDiagnostics.active();
        long started = capture ? RenderWorkDiagnostics.start() : 0;
        long palettes = 0, cells = 0, fluids = 0, fastAccepts = 0, fallbacks = 0;
        try {
            boolean needsScan;
            long paletteStarted = capture ? RenderWorkDiagnostics.start() : 0;
            try {
                var sections = chunk.getSections();
                needsScan = sections == null || sections.length != chunk.getSectionsCount();
                if (!needsScan) {
                    for (int index = 0; index < sections.length; index++) {
                        if (submitted.get(index)) continue;
                        var section = sections[index];
                        if (capture && section != null) palettes++;
                        // Palette false positives only cost a scan. Unknown data cannot prove
                        // readiness.
                        if (section == null
                                || section.maybeHas(
                                        state ->
                                                state.getFluidState()
                                                        .getType()
                                                        .isSame(Fluids.WATER))) {
                            needsScan = true;
                            break;
                        }
                    }
                }
            } finally {
                if (capture)
                    RenderWorkDiagnostics.finish(Metric.NATIVE_WATER_PALETTE_NS, paletteStarted);
            }
            // The scan can reject only water in an undrawn section. Read current palettes each
            // call:
            // client packets and block updates can mutate the same chunk and section objects.
            if (!needsScan) {
                if (capture) fastAccepts++;
                return true;
            }
            if (capture) fallbacks++;
            long scanStarted = capture ? RenderWorkDiagnostics.start() : 0;
            try {
                var pos = new BlockPos.MutableBlockPos();
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (capture) cells++;
                        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        int index = chunk.getSectionIndex(y);
                        // Most ocean tiles use one section; skip block lookup once its water layer
                        // drew.
                        if (index < 0 || index >= chunk.getSectionsCount() || submitted.get(index))
                            continue;
                        pos.set(
                                chunk.getPos().getMinBlockX() + x,
                                y,
                                chunk.getPos().getMinBlockZ() + z);
                        if (capture) fluids++;
                        if (chunk.getFluidState(pos).getType().isSame(Fluids.WATER)) return false;
                    }
                }
                return true;
            } finally {
                if (capture) RenderWorkDiagnostics.finish(Metric.NATIVE_WATER_SCAN_NS, scanStarted);
            }
        } finally {
            if (capture) {
                RenderWorkDiagnostics.add(Metric.NATIVE_WATER_CALLS, 1);
                RenderWorkDiagnostics.add(Metric.NATIVE_WATER_FALLBACKS, fallbacks);
                RenderWorkDiagnostics.add(Metric.NATIVE_WATER_PALETTE_SECTIONS, palettes);
                RenderWorkDiagnostics.add(Metric.NATIVE_WATER_FAST_ACCEPTS, fastAccepts);
                RenderWorkDiagnostics.add(Metric.NATIVE_WATER_SURFACE_CELLS, cells);
                RenderWorkDiagnostics.add(Metric.NATIVE_WATER_FLUID_LOOKUPS, fluids);
                RenderWorkDiagnostics.finish(Metric.NATIVE_WATER_NS, started);
            }
        }
    }

    private static SurfaceChunk surfaceChunk() {
        return new SurfaceChunk();
    }

    private static final class SurfaceChunk extends net.minecraft.world.level.chunk.ProtoChunk {
        int heightLookups, fluidLookups;
        boolean unknownLastSection, nullSections, truncatedSections, failHeight, failSections;

        SurfaceChunk() {
            this(dev.starfold.worldgen.PlanetTerrainTest.HEIGHT);
        }

        SurfaceChunk(net.minecraft.world.level.LevelHeightAccessor height) {
            super(
                    new net.minecraft.world.level.ChunkPos(7, 9),
                    net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                    height,
                    dev.starfold.worldgen.PlanetTerrainTest.biomes,
                    null);
        }

        @Override
        public int getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types type, int x, int z) {
            heightLookups++;
            if (failHeight) throw new IllegalStateException("height lookup failed");
            return super.getHeight(type, x, z);
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(
                net.minecraft.core.BlockPos pos) {
            fluidLookups++;
            // Hide palette metadata independently of the underlying loaded block storage.
            boolean unknown = unknownLastSection,
                    missing = nullSections,
                    truncated = truncatedSections;
            unknownLastSection = nullSections = truncatedSections = false;
            try {
                return super.getFluidState(pos);
            } finally {
                unknownLastSection = unknown;
                nullSections = missing;
                truncatedSections = truncated;
            }
        }

        @Override
        public net.minecraft.world.level.chunk.LevelChunkSection[] getSections() {
            if (failSections) throw new IllegalStateException("palette access failed");
            var sections = super.getSections();
            if (nullSections) return null;
            if (truncatedSections) return java.util.Arrays.copyOf(sections, sections.length - 1);
            if (!unknownLastSection) return sections;
            sections = sections.clone();
            sections[sections.length - 1] = null;
            return sections;
        }
    }

    private static void top(
            net.minecraft.world.level.chunk.ProtoChunk chunk,
            int x,
            int y,
            net.minecraft.world.level.block.state.BlockState state) {
        chunk.setBlockState(
                new net.minecraft.core.BlockPos(
                        chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ()),
                state,
                false);
        net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(
                chunk,
                java.util.EnumSet.of(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE));
        assertEquals(
                y,
                chunk.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, 0));
    }

    private static BitSet all() {
        var out = new BitSet();
        out.set(0, 64);
        return out;
    }
}
