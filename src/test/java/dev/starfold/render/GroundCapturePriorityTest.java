package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class GroundCapturePriorityTest {
    @Test
    void publishingSkipsUnavailableEntriesButKeepsOnePayloadBudget() throws Exception {
        var source = dev.starfold.Sources.text("GroundViewServer");
        assertTrue(source.contains("v.surfaceStream.next(index,v.surfaces,shape,eye)"));
        assertEquals(16, GroundSurfaceStream.PROBES);
        assertEquals(1, source.split("newGroundViewPackets.Surface", -1).length - 1);
    }

    @Test
    void prominentSilhouetteEditsPrecedeChurningObserverChunksWithoutDroppingTheirPriority() {
        var dirty=new LinkedHashSet<Long>();
        for(long i=0;i<501;i++) dirty.add(i);
        var selected=GroundSurfaceIndex.candidates(Set.of(490L),dirty,dirty,List.of(0L));
        assertEquals(490L,selected.getFirst());
        assertEquals(0L,selected.get(1));
        assertEquals(16,selected.size());
        assertEquals(16,new HashSet<>(selected).size());
    }

    @Test
    void observerNeighbourhoodWinsEvenWhenReloadHasHundredsOfUrgentEdits() {
        var dirty = new LinkedHashSet<Long>();
        for (long i = 21000; i < 21501; i++) dirty.add(i);
        dirty.addAll(List.of(73663L, 73664L, 73791L, 73792L));
        var urgent = new LinkedHashSet<>(dirty);
        var selected = GroundSurfaceIndex.candidates(urgent, dirty, List.of(73792L));
        assertEquals(73792L, selected.getFirst());
        assertTrue(selected.subList(0, 4).containsAll(List.of(73663L, 73664L, 73791L, 73792L)));
        assertEquals(16, selected.size());
    }

    @Test
    void recentEditsPrecedeRestoredBacklogWithoutDuplicatingOrLosingKeys() {
        var dirty = new LinkedHashSet<Long>();
        for (long i = 0; i < 501; i++) dirty.add(i);
        var urgent = new LinkedHashSet<Long>(List.of(490L, 491L, 492L, 493L));
        var selected = GroundSurfaceIndex.candidates(urgent, dirty);
        assertEquals(List.of(490L, 491L, 492L, 493L), selected.subList(0, 4));
        assertEquals(16, selected.size());
        assertEquals(16, new HashSet<>(selected).size());
        assertEquals(501, dirty.size());
        // Unavailable candidates lose priority, but remain in the non-loading backlog.
        urgent.remove(490L);
        dirty.remove(490L);
        dirty.add(490L);
        assertEquals(491L, GroundSurfaceIndex.candidates(urgent, dirty).getFirst());
        assertTrue(dirty.contains(490L));
        urgent.clear();
        assertEquals(0L, GroundSurfaceIndex.candidates(urgent, dirty).getFirst());
    }
}
