package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class ChunkPostProcessBudgetTest {
    @Test
    void readyPublicationBurstDrainsTwoChunksPerTickWithoutDuplicates() {
        var held = new LinkedHashMap<Long, Long>();
        for (long i = 0; i < 9; i++) held.put(i, i);
        var ran = new ArrayList<Long>();
        for (int tick = 0; tick < 5; tick++) {
            int before = ran.size();
            ChunkTickReadiness.release(
                    held,
                    (pos, chunk) -> true,
                    pos -> true,
                    chunk -> {
                        assertFalse(held.containsKey(chunk));
                        ran.add(chunk);
                    });
            assertTrue(ran.size() - before <= 2);
        }
        assertTrue(held.isEmpty());
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), ran);
    }

    @Test
    void coldFrontOfQueueCannotStarveReadyChunksAndChecksAreBounded() {
        var held = new LinkedHashMap<Long, Long>();
        for (long i = 0; i < 130; i++) held.put(i, i);
        var ran = new ArrayList<Long>();
        for (int tick = 0; tick < 3; tick++) {
            var checked = new int[1];
            ChunkTickReadiness.release(
                    held,
                    (pos, chunk) -> true,
                    pos -> {
                        checked[0]++;
                        return pos >= 128;
                    },
                    ran::add);
            assertTrue(checked[0] <= 64);
        }
        assertEquals(List.of(128L, 129L), ran);
        assertEquals(128, held.size());
    }

    @Test
    void unloadedChunksAreDroppedWithoutReadinessChecksOrPostProcessing() {
        var held = new LinkedHashMap<Long, String>();
        held.put(1L, "unloaded");
        held.put(2L, "waiting");
        held.put(3L, "ready");
        var ran = new ArrayList<String>();
        ChunkTickReadiness.release(
                held,
                (pos, chunk) -> pos != 1,
                pos -> {
                    assertNotEquals(1, pos);
                    return pos == 3;
                },
                ran::add);
        assertEquals(Map.of(2L, "waiting"), held);
        assertEquals(List.of("ready"), ran);
    }

    @Test
    void reentrantHoldsSurviveTheCurrentDrain() {
        var held = new LinkedHashMap<Long, String>();
        held.put(1L, "first");
        held.put(2L, "second");
        ChunkTickReadiness.release(
                held,
                (pos, chunk) -> true,
                pos -> true,
                chunk -> {
                    assertFalse(held.containsKey(1L));
                    assertFalse(held.containsKey(2L));
                    held.put(3L, "held again");
                });
        assertEquals(Map.of(3L, "held again"), held);
    }
}
