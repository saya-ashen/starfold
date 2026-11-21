package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class GroundPagedArenaTest {
    @Test
    void fullOrFragmentedPagesRejectWithoutMovingPublishedRanges() {
        var ranges = new GroundLodArena.Ranges();
        ranges.grow(32);
        assertEquals(0, ranges.allocate(8));
        assertEquals(8, ranges.allocate(8));
        assertEquals(16, ranges.allocate(8));
        assertEquals(24, ranges.allocate(8));
        ranges.free(0, 8);
        ranges.free(16, 8);
        var plan = ranges.planPageAllocation(12, 32, 4096, 12);
        assertEquals(GroundLodArena.AllocationStatus.REJECTED, plan.status());
        assertEquals(32, ranges.capacity);
        assertEquals(0, ranges.allocate(8), "holes remain usable after larger requests select another page");
        assertEquals(16, ranges.allocate(8));
    }

    @Test
    void newPagesRespectPeakHeadroomAndSupportOneOversizedRange() {
        var empty = new GroundLodArena.Ranges();
        var normal = empty.planPageAllocation(4, 32, 384, 12);
        assertEquals(GroundLodArena.AllocationStatus.GROW, normal.status());
        assertEquals(384, normal.additionalBytes());
        assertEquals(384, normal.retainedBytesDelta(), "there is no old VBO to retire");
        var limited = empty.planPageAllocation(4, 32, 191, 12);
        assertEquals(12, limited.capacityVertices());
        assertEquals(144, limited.additionalBytes());
        assertEquals(GroundLodArena.AllocationStatus.REJECTED,
                empty.planPageAllocation(16, 32, 191, 12).status());
        assertEquals(48, empty.planPageAllocation(48, 32, 576, 12).capacityVertices());
        assertEquals(GroundLodArena.AllocationStatus.INVALID,
                empty.planPageAllocation(0, 32, 384, 12).status());
    }

    @Test
    void relocationInvalidatesCommandsEvenWithoutNewSelection() throws Exception {
        try (var page = new GroundLodArena(32); var commands = new GroundFaceArenas.PageCommands()) {
            var batch = new ArrayList<>(List.of(draw(page, 0, 1)));
            assertTrue(commands.prepare(batch, 1, true));
            assertFalse(commands.prepare(batch, 1, true));
            var revision = GroundLodArena.class.getDeclaredField("commandRevision");
            revision.setAccessible(true);
            revision.setLong(page, 1);
            batch.getFirst().slot().range.first = 12;
            assertTrue(commands.prepare(batch, 1, true));
            assertEquals(12, commands.runs.getFirst().commands().bases.get(0));
            assertFalse(commands.prepare(batch, 1, true));
        }
    }

    @Test
    void unchangedRunsReuseCommandsAcrossSelectionsButOrderIdentityAndOffsetsInvalidate() {
        try (var page = new GroundLodArena(32); var commands = new GroundFaceArenas.PageCommands()) {
            var a = draw(page,0,3); var b = draw(page,12,1);
            var batch = new ArrayList<>(List.of(a,b));
            commands.prepare(batch,1,true);
            var original=commands.runs.getFirst();
            original.commands().prepare();
            commands.prepare(new ArrayList<>(List.of(a,b)),2,true);
            assertSame(original,commands.runs.getFirst(),"new selection epoch alone must not rewrite identical commands");
            assertEquals(2,original.commands().counts.remaining());
            commands.prepare(new ArrayList<>(List.of(new GroundFaceArenas.Draw(a.slot(),0),b)),3,true);
            assertNotSame(original,commands.runs.getFirst(),"changed transparent order must rebuild");
            assertEquals(12,commands.runs.getFirst().commands().bases.get(0));
            var ordered=commands.runs.getFirst();
            b.slot().range.first=20;
            commands.prepare(new ArrayList<>(List.of(new GroundFaceArenas.Draw(a.slot(),0),b)),4,true);
            assertNotSame(ordered,commands.runs.getFirst());
            assertEquals(20,commands.runs.getFirst().commands().bases.get(0));
            var moved=commands.runs.getFirst();
            commands.prepare(new ArrayList<>(List.of(new GroundFaceArenas.Draw(a.slot(),0),draw(page,20,1))),5,true);
            assertNotSame(moved,commands.runs.getFirst(),"replacement identity must invalidate even at the same offset");
            commands.prepare(new ArrayList<>(List.of(a)),6,true);
            assertEquals(4,commands.runs.getFirst().commands().vertices);
        }
    }

    private static GroundFaceArenas.Draw draw(GroundLodArena page, int first, double distance) {
        var range = new GroundLodArena.Range(page, first, 4);
        return new GroundFaceArenas.Draw(new GroundFaceArenas.Slot(null, 0, 0, range, 0), distance);
    }

    @Test
    void translucentRunsKeepGlobalDistanceOrderEvenWhenAPageRepeats() {
        try (var a = new GroundLodArena(32, true); var b = new GroundLodArena(32, true);
                var commands = new GroundFaceArenas.PageCommands()) {
            var batch = new ArrayList<>(List.of(draw(a, 0, 1), draw(a, 4, 3), draw(b, 0, 2)));
            assertTrue(commands.prepare(batch, 1, true));
            assertEquals(List.of(a, b, a), commands.runs.stream().map(GroundFaceArenas.PageCommands.Run::arena).toList());
            assertEquals(4, commands.runs.get(0).commands().bases.get(0));
            assertEquals(0, commands.runs.get(2).commands().bases.get(0));
            assertFalse(commands.prepare(batch, 1, true));
            commands.runs.forEach(r -> r.commands().prepare());
            assertFalse(commands.prepare(batch, 1, true));
            assertEquals(1, commands.runs.get(0).commands().counts.remaining());
            batch.removeIf(d -> d.slot().range.arena == b);
            assertTrue(commands.prepare(batch, 2, true));
            assertEquals(1, commands.runs.size(), "retired runs must release their native command buffers");
            assertEquals(8, commands.runs.getFirst().commands().vertices);
        }
    }

    @Test
    void opaquePagesRegroupWithoutMixingTheirBaseVerticesAndPassCachesStayIndependent() {
        try (var a = new GroundLodArena(32, true); var b = new GroundLodArena(32, true);
                var opaque = new GroundFaceArenas.PageCommands(); var water = new GroundFaceArenas.PageCommands()) {
            var batch = new ArrayList<>(List.of(draw(a, 0, 1), draw(b, 12, 2), draw(a, 4, 3)));
            assertTrue(opaque.prepare(batch, 1, false));
            assertEquals(List.of(a, b), opaque.runs.stream().map(GroundFaceArenas.PageCommands.Run::arena).toList());
            assertEquals(8, opaque.runs.getFirst().commands().vertices);
            assertEquals(1, opaque.runs.getFirst().commands().draws);
            assertEquals(12, opaque.runs.get(1).commands().bases.get(0));
            assertTrue(water.prepare(batch, 1, true));
            assertEquals(3, water.runs.size());
            assertFalse(opaque.prepare(batch, 1, false));
            assertFalse(water.prepare(batch, 1, true));
        }
    }
}
