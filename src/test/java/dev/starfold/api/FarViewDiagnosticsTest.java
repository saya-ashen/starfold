package dev.starfold.api;

import static dev.starfold.api.FarViewDiagnostics.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Isolated("FarViewDiagnostics has process-wide static state")
@Execution(ExecutionMode.SAME_THREAD)
class FarViewDiagnosticsTest {
    private static final int PLANET = 7, FACE = 2, X = 64, Z = 96;

    @AfterEach
    void stopCapture() {
        stop();
    }

    @Test
    void disabledCaptureRetainsNothingAndRejectsCallbacks() {
        stop();
        observeContext(PLANET, 1, true, "disabled");
        assertFalse(active());
        assertEquals(0, epoch(PLANET));
        assertNull(currentWatch(0));
        assertFalse(matches(FACE, X, Z));
        assertFalse(sampleDue(0));
        publish(123, PLANET);
        var snapshot = snapshot();
        assertNull(snapshot.watch());
        assertEquals(0, snapshot.epoch());
        assertEmpty(snapshot);
        assertTrue(eventsSince(0).isEmpty());
        assertTrue(geometry().isEmpty());
    }

    @Test
    void watchNormalizesOriginsAndClipsAtBothChartEdges() {
        observeContext(PLANET, 1, false, "context");
        var low = watch(PLANET, 0, 15, 1, 2);
        assertEquals(0, low.x());
        assertEquals(0, low.z());
        assertEquals(2, low.radius());
        int count = 0;
        for (int z = 0; z < 2048; z += 16)
            for (int x = 0; x < 2048; x += 16) if (matches(0, x, z)) count++;
        assertEquals(9, count);
        assertTrue(matches(0, 47, 47));
        assertFalse(matches(0, -1, 0));
        assertFalse(matches(0, 48, 0));
        assertFalse(matches(1, 0, 0));

        var high = watch(PLANET, 5, 2047, 2047, 2);
        assertEquals(2032, high.x());
        assertEquals(2032, high.z());
        assertTrue(high.epoch() > low.epoch());
        assertTrue(matches(5, 2000, 2000));
        assertTrue(matches(5, 2047, 2047));
        assertFalse(matches(5, 1999, 2032));
        assertFalse(matches(5, 2048, 2032));
        long key = 5L * 16384 + 127L * 128 + 127;
        assertTrue(matches(5, key));
        assertFalse(matches(4, key));
        assertFalse(matches(5, -1L));
        assertFalse(matches(5, 98304L));

        watch(PLANET, FACE, X + 15, Z + 15, 0);
        assertTrue(matches(FACE, X, Z));
        assertTrue(matches(FACE, X + 15, Z + 15));
        assertFalse(matches(FACE, X + 16, Z));
    }

    @Test
    void invalidWatchArgumentsLeaveTheAcceptedWatchUntouched() {
        var accepted = start(2);
        int[][] invalid = {
            {-1, FACE, X, Z, 0},
            {PLANET, -1, X, Z, 0},
            {PLANET, 6, X, Z, 0},
            {PLANET, FACE, -1, Z, 0},
            {PLANET, FACE, 2048, Z, 0},
            {PLANET, FACE, X, -1, 0},
            {PLANET, FACE, X, 2048, 0},
            {PLANET, FACE, X, Z, -1},
            {PLANET, FACE, X, Z, 3},
            {PLANET, FACE, Integer.MAX_VALUE, Z, 0}
        };
        for (var args : invalid) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> watch(args[0], args[1], args[2], args[3], args[4]));
            assertEquals(accepted, snapshot().watch());
        }
    }

    @Test
    void explicitResetRejectsOldCallbacksAndClearsEveryRetainedChannel() {
        var old = start(1);
        publish(old.epoch(), PLANET);
        assertNotNull(snapshot().region());
        observeContext(PLANET, 1, false, "unchanged");
        assertEquals(old, snapshot().watch());
        assertNotNull(snapshot().region());

        observeContext(PLANET, 1, true, "explicit reset");
        assertResetAndRejects(old, PLANET, "explicit reset");
        long current = epoch(PLANET);
        assertTrue(sampleDue(current), "reset clears the region sampling gate");
        publish(current, PLANET);
        assertEquals(current, snapshot().region().epoch());
    }

    @Test
    void planetAndSessionChangesInvalidateEpochsEvenWhenReturningToTheSamePlanet() {
        var first = start(0);
        publish(first.epoch(), PLANET);
        observeContext(PLANET + 1, 1, false, "other planet");
        assertResetAndRejects(first, PLANET + 1, "other planet");
        assertEquals(0, epoch(PLANET));
        assertEquals(0, epoch(PLANET + 1));
        assertNull(currentWatch(snapshot().epoch()));
        assertFalse(matches(FACE, X, Z));

        var away = snapshot().watch();
        observeContext(PLANET, 1, false, "return");
        assertResetAndRejects(away, PLANET, "return");
        publish(first.epoch(), PLANET);
        assertNull(snapshot().region());
        var returned = snapshot().watch();
        publish(returned.epoch(), PLANET);
        observeContext(PLANET, 2, false, "new session");
        assertResetAndRejects(returned, PLANET, "new session");
        assertEquals(2, snapshot().rendererSession());
        assertEquals(snapshot().watch(), currentWatch(epoch(PLANET)));
    }

    @Test
    void replacingOrStoppingAWatchCannotReviveOldJobs() {
        var first = start(0);
        publish(first.epoch(), PLANET);
        var replacement = watch(PLANET, FACE, X, Z, 0);
        assertTrue(replacement.epoch() > first.epoch());
        publish(first.epoch(), PLANET);
        assertEmpty(snapshot());
        publish(replacement.epoch(), PLANET);
        var frozen = snapshot();
        stop();
        publish(replacement.epoch(), PLANET);
        assertFalse(active());
        assertEmpty(snapshot());
        var restarted = watch(PLANET, FACE, X, Z, 0);
        assertTrue(restarted.epoch() > replacement.epoch());
        publish(first.epoch(), PLANET);
        publish(replacement.epoch(), PLANET);
        assertEmpty(snapshot());
        publish(restarted.epoch(), PLANET);
        assertEquals(restarted.epoch(), snapshot().region().epoch());
        assertEquals(replacement.epoch(), frozen.region().epoch());
        assertEquals(1, frozen.events().size());
    }

    @Test
    void eventRingKeepsTheNewest512WithIndependentExclusiveMonotonicCursors() {
        long epoch = start(0).epoch();
        event(epoch, X, Z, Source.RESIDENT, Kind.REQUEST, "first");
        long first = snapshot().events().getFirst().sequence();
        for (int i = 1; i < 549; i++)
            event(epoch, X, Z, Source.RESIDENT, Kind.REQUEST, "event-" + i);
        var full = snapshot();
        assertEquals(512, full.events().size());
        assertEquals(37, full.droppedEvents());
        for (int i = 0; i < 512; i++) {
            var event = full.events().get(i);
            assertEquals(first + 37 + i, event.sequence());
            assertEquals("event-" + (37 + i), event.reason());
        }
        long cursor = full.events().get(500).sequence();
        var readerA = eventsSince(cursor);
        assertEquals(11, readerA.size());
        assertEquals(full.events().subList(501, 512), readerA);
        assertEquals(readerA, eventsSince(cursor));
        assertEquals(full.events(), eventsSince(0));
        assertTrue(eventsSince(full.events().getLast().sequence()).isEmpty());
        assertTrue(eventsSince(Long.MAX_VALUE).isEmpty());
        assertThrows(UnsupportedOperationException.class, readerA::clear);
        assertEquals(full, snapshot());

        observeContext(PLANET, 1, true, "reset");
        var reset = snapshot().events().getFirst();
        assertTrue(reset.sequence() > full.events().getLast().sequence());
        assertEquals(List.of(reset), eventsSince(full.events().getLast().sequence()));
        assertEquals(0, snapshot().droppedEvents());
        assertEquals(512, full.events().size());
    }

    @Test
    void eventsNormalizeAndRejectWrongEpochFaceAndUnwatchedTiles() {
        long epoch = start(0).epoch();
        event(0, X, Z, Source.RESIDENT, Kind.REQUEST, "zero");
        event(epoch - 1, X, Z, Source.RESIDENT, Kind.REQUEST, "old");
        observeEvent(epoch, FACE + 1, X, Z, Source.RESIDENT, Kind.REQUEST, "face", 1, null);
        event(epoch, X + 16, Z, Source.RESIDENT, Kind.REQUEST, "outside");
        assertTrue(snapshot().events().isEmpty());
        event(epoch, X + 15, Z + 15, Source.PARENT, Kind.UPLOAD, "accepted");
        var accepted = snapshot().events().getFirst();
        assertEquals(X, accepted.x());
        assertEquals(Z, accepted.z());
        assertEquals(Source.PARENT, accepted.source());
        assertEquals(versions(epoch), accepted.versions());
    }

    @Test
    void drawAndCullSamplingIsIndependentForEachTileSourceAndKind() {
        long epoch = start(1).epoch();
        long before = System.nanoTime();
        for (int repeat = 0; repeat < 2; repeat++)
            for (int x : new int[] {X, X + 16})
                for (var source : new Source[] {Source.RESIDENT, Source.CHART})
                    for (var kind : new Kind[] {Kind.DRAW, Kind.CULL})
                        event(epoch, x + repeat, Z, source, kind, "sample");
        long elapsed = System.nanoTime() - before;
        var groups = new HashMap<String, List<Event>>();
        for (var value : snapshot().events())
            groups.computeIfAbsent(
                            value.x() + ":" + value.source() + ":" + value.kind(),
                            ignored -> new ArrayList<>())
                    .add(value);
        assertEquals(
                8, groups.size(), "a different tile, source or kind must get its first sample");
        for (var group : groups.values()) {
            assertTrue(group.size() <= 2);
            if (elapsed < SAMPLE_NANOS)
                assertEquals(1, group.size(), "same normalized tile shares a sampling gate");
            for (int i = 1; i < group.size(); i++)
                assertTrue(group.get(i).nanoTime() - group.get(i - 1).nanoTime() >= SAMPLE_NANOS);
        }
        int sampledCount = snapshot().events().size();
        for (int i = 0; i < 3; i++) event(epoch, X, Z, Source.RESIDENT, Kind.REQUEST, "unsampled");
        assertEquals(sampledCount + 3, snapshot().events().size());
        observeContext(PLANET, 1, true, "sampling reset");
        event(epoch(PLANET), X, Z, Source.RESIDENT, Kind.DRAW, "fresh");
        assertEquals(
                List.of(Kind.RESET, Kind.DRAW),
                snapshot().events().stream().map(Event::kind).toList());
    }

    @Test
    void geometryEnforcesQuadAndPointCapsAndCopiesNestedLists() {
        long epoch = start(0).epoch();
        var points = new ArrayList<>(java.util.Collections.nCopies(8, new Point(1, 2, 3)));
        var quad = new QuadSample(0, "stone", 1, false, 0xabcdef, -1, points);
        var quads = new ArrayList<>(java.util.Collections.nCopies(64, quad));
        var accepted = geometrySample(epoch, PLANET, X, Z, Source.RESIDENT, "mesh", quads);
        points.clear();
        quads.clear();
        observeGeometry(accepted);
        assertEquals(List.of(accepted), geometry());
        assertEquals(64, accepted.sampledQuads().size());
        assertEquals(8, quad.points().size());
        assertThrows(UnsupportedOperationException.class, quad.points()::clear);
        assertThrows(UnsupportedOperationException.class, accepted.sampledQuads()::clear);
        assertThrows(UnsupportedOperationException.class, geometry()::clear);

        observeGeometry(
                geometrySample(
                        epoch,
                        PLANET,
                        X,
                        Z,
                        Source.RESIDENT,
                        "too many quads",
                        java.util.Collections.nCopies(65, quad)));
        var oversized =
                new QuadSample(
                        1,
                        "water",
                        0,
                        true,
                        0,
                        0,
                        java.util.Collections.nCopies(9, new Point(0, 0, 0)));
        observeGeometry(
                geometrySample(
                        epoch,
                        PLANET,
                        X,
                        Z,
                        Source.RESIDENT,
                        "too many points",
                        List.of(oversized)));
        observeGeometry(
                geometrySample(epoch, PLANET + 1, X, Z, Source.RESIDENT, "planet", List.of()));
        observeGeometry(
                geometrySample(epoch, PLANET, X + 16, Z, Source.RESIDENT, "outside", List.of()));
        observeGeometry(geometrySample(epoch - 1, PLANET, X, Z, Source.RESIDENT, "old", List.of()));
        assertEquals(List.of(accepted), geometry());
    }

    @Test
    void geometrySlotsAreBoundedAndReplacingASlotRefreshesItsEvictionOrder() {
        long epoch = start(2).epoch();
        var inputs = new ArrayList<Geometry>();
        for (int z = Z - 32; z <= Z + 32; z += 16)
            for (int x = X - 32; x <= X + 32; x += 16)
                for (var source : Source.values())
                    inputs.add(geometrySample(epoch, PLANET, x, z, source, "mesh", List.of()));
        inputs.subList(0, 75).forEach(FarViewDiagnostics::observeGeometry);
        assertEquals(inputs.subList(0, 75), geometry());
        var first = inputs.getFirst();
        var refreshed =
                geometrySample(
                        epoch,
                        PLANET,
                        first.x(),
                        first.z(),
                        first.source(),
                        "new stage",
                        List.of());
        observeGeometry(refreshed);
        assertEquals(75, geometry().size());
        assertEquals(refreshed, geometry().getLast());
        observeGeometry(inputs.get(75));
        assertEquals(75, geometry().size());
        assertFalse(geometry().contains(inputs.get(1)));
        assertTrue(geometry().contains(refreshed));
        inputs.subList(76, inputs.size()).forEach(FarViewDiagnostics::observeGeometry);
        assertEquals(inputs.subList(inputs.size() - 75, inputs.size()), geometry());
    }

    @Test
    void regionsAndSnapshotsCopyListsAndRejectOversizedOrWrongPlanetRegions() {
        long epoch = start(0).epoch();
        var tiles = new ArrayList<TileState>();
        var region = region(epoch, PLANET, tiles);
        tiles.add(null);
        assertTrue(region.tiles().isEmpty());
        observeRegion(region);
        observeRegion(region(epoch, PLANET + 1, List.of()));
        observeRegion(region(epoch - 1, PLANET, List.of()));
        // A non-null tile is needed to distinguish the 25-tile cap from list validation.
        var tile =
                new TileState(
                        FACE,
                        X,
                        Z,
                        false,
                        1,
                        1,
                        true,
                        true,
                        false,
                        false,
                        false,
                        false,
                        0,
                        1,
                        1,
                        true,
                        false,
                        false,
                        versions(epoch),
                        1,
                        1);
        observeRegion(region(epoch, PLANET, java.util.Collections.nCopies(26, tile)));
        assertEquals(region, snapshot().region());
        var atCap = region(epoch, PLANET, java.util.Collections.nCopies(25, tile));
        observeRegion(atCap);
        assertEquals(atCap, snapshot().region());
        assertThrows(UnsupportedOperationException.class, atCap.tiles()::clear);

        var events = new ArrayList<Event>();
        var geometry = new ArrayList<Geometry>();
        var records = new ArrayList<CpuRecord>();
        var copied =
                new Snapshot(
                        snapshot().watch(),
                        PLANET,
                        1,
                        epoch,
                        atCap,
                        events,
                        geometry,
                        0,
                        records,
                        0);
        events.add(null);
        geometry.add(null);
        records.add(null);
        assertTrue(copied.events().isEmpty());
        assertTrue(copied.geometry().isEmpty());
        assertTrue(copied.records().isEmpty());
        assertThrows(UnsupportedOperationException.class, copied.events()::clear);
        assertThrows(UnsupportedOperationException.class, copied.geometry()::clear);
        assertThrows(UnsupportedOperationException.class, copied.records()::clear);
    }

    @Test
    void cpuRecordsDeepCopyJsonValuesAndRetainOnlyTheNewest128() {
        long epoch = start(0).epoch();
        var nested = new LinkedHashMap<String, Object>();
        nested.put("light", 123);
        nested.put("unknown", null);
        var list = new ArrayList<Object>(List.of(nested));
        var fields = new LinkedHashMap<String, Object>();
        fields.put("samples", list);
        record(epoch, PLANET, FACE, X + 3, Z + 5, "encoded", fields);
        var frozen = snapshot();
        nested.put("light", 999);
        list.clear();
        fields.clear();
        var value = frozen.records().getFirst();
        var copiedList = (List<?>) value.fields().get("samples");
        var copiedMap = (Map<?, ?>) copiedList.getFirst();
        assertEquals(123, copiedMap.get("light"));
        assertTrue(copiedMap.containsKey("unknown"));
        assertNull(copiedMap.get("unknown"));
        assertEquals(X, value.x());
        assertEquals(Z, value.z());
        assertThrows(UnsupportedOperationException.class, value.fields()::clear);
        assertThrows(UnsupportedOperationException.class, copiedList::clear);
        assertThrows(UnsupportedOperationException.class, copiedMap::clear);
        for (int i = 1; i <= 130; i++)
            record(epoch, PLANET, FACE, X, Z, "record", Map.of("index", i));
        var bounded = snapshot();
        assertEquals(128, bounded.records().size());
        assertEquals(3, bounded.droppedRecords());
        for (int i = 0; i < 128; i++)
            assertEquals(i + 3, bounded.records().get(i).fields().get("index"));
        assertEquals(1, frozen.records().size());
    }

    @Test
    void cpuPayloadBoundsRejectInvalidEvidenceWithoutEvictingAcceptedRecords() {
        long epoch = start(0).epoch();
        record(epoch, PLANET, FACE, X, Z, "valid", Map.of("value", true));
        var accepted = snapshot();
        var invalid = new ArrayList<Map<String, Object>>();
        invalid.add(Map.of("value", new Object()));
        invalid.add(Map.of("value", Double.NaN));
        invalid.add(Map.of("value", Float.POSITIVE_INFINITY));
        invalid.add(Map.of("value", "x".repeat(2049)));
        invalid.add(Map.of("k".repeat(129), 1));
        invalid.add(Map.of("value", java.util.Collections.nCopies(257, 1)));
        var wide = new HashMap<String, Object>();
        for (int i = 0; i < 129; i++) wide.put("k" + i, i);
        invalid.add(wide);
        Object deep = 0;
        for (int i = 0; i < 11; i++) deep = List.of(deep);
        invalid.add(Map.of("value", deep));
        invalid.add(
                Map.of(
                        "value",
                        java.util.Collections.nCopies(32, java.util.Collections.nCopies(256, 0))));
        var cyclic = new HashMap<String, Object>();
        cyclic.put("self", cyclic);
        invalid.add(cyclic);
        for (var fields : invalid)
            assertThrows(
                    IllegalArgumentException.class,
                    () -> record(epoch, PLANET, FACE, X, Z, "invalid", fields));
        assertThrows(
                IllegalArgumentException.class,
                () -> record(epoch, PLANET, FACE, X, Z, null, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> record(epoch, PLANET, FACE, X, Z, "s".repeat(65), Map.of()));
        record(epoch, PLANET + 1, FACE, X, Z, "planet", Map.of());
        record(epoch, PLANET, FACE + 1, X, Z, "face", Map.of());
        record(epoch, PLANET, FACE, X + 16, Z, "outside", Map.of());
        assertEquals(accepted, snapshot());
    }

    @Test
    void concurrentWritersReadersAndRestartsNeverExposeMixedAcceptedEpochs() throws Exception {
        start(0);
        var phases = new CyclicBarrier(3);
        var executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> restarter =
                    executor.submit(
                            () -> {
                                for (int round = 0; round < 32; round++) {
                                    await(phases);
                                    stop();
                                    int planet = PLANET + round % 2;
                                    watch(planet, FACE, X, Z, 0);
                                    observeContext(planet, round + 10, true, "restart");
                                    await(phases);
                                    await(phases);
                                }
                            });
            Future<?> writer =
                    executor.submit(
                            () -> {
                                for (int round = 0; round < 32; round++) {
                                    var old = snapshot().watch();
                                    await(phases);
                                    for (int i = 0; i < 8; i++) publish(old.epoch(), old.planet());
                                    await(phases);
                                    publish(old.epoch(), old.planet());
                                    var current = snapshot().watch();
                                    for (int i = 0; i < 8; i++)
                                        publish(current.epoch(), current.planet());
                                    await(phases);
                                }
                            });
            Future<?> reader =
                    executor.submit(
                            () -> {
                                for (int round = 0; round < 32; round++) {
                                    await(phases);
                                    for (int i = 0; i < 32; i++) assertCoherent(snapshot());
                                    await(phases);
                                    for (int i = 0; i < 32; i++) assertCoherent(snapshot());
                                    await(phases);
                                    var settled = snapshot();
                                    assertCoherent(settled);
                                    assertNotNull(settled.region());
                                    assertFalse(settled.geometry().isEmpty());
                                    assertFalse(settled.records().isEmpty());
                                }
                            });
            for (var task : List.of(restarter, writer, reader)) task.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Watch start(int radius) {
        observeContext(PLANET, 1, false, "test context");
        return watch(PLANET, FACE, X, Z, radius);
    }

    private static Versions versions(long epoch) {
        return new Versions(epoch, 2, 3, 4, 5, 6);
    }

    private static void event(long epoch, int x, int z, Source source, Kind kind, String reason) {
        observeEvent(epoch, FACE, x, z, source, kind, reason, 2, versions(epoch));
    }

    private static Geometry geometrySample(
            long epoch,
            int planet,
            int x,
            int z,
            Source source,
            String stage,
            List<QuadSample> quads) {
        return new Geometry(
                epoch,
                planet,
                FACE,
                x,
                z,
                source,
                stage,
                2,
                123,
                versions(epoch),
                quads.size(),
                quads,
                false);
    }

    private static RegionState region(long epoch, int planet, List<TileState> tiles) {
        return new RegionState(123, epoch, planet, FACE, true, true, true, 0, 0, tiles);
    }

    private static void publish(long epoch, int planet) {
        observeRegion(region(epoch, planet, List.of()));
        event(epoch, X, Z, Source.RESIDENT, Kind.PUBLICATION, "epoch-" + epoch);
        observeGeometry(geometrySample(epoch, planet, X, Z, Source.RESIDENT, "mesh", List.of()));
        record(epoch, planet, FACE, X, Z, "encoded", Map.of("epoch", epoch));
    }

    private static void assertEmpty(Snapshot snapshot) {
        assertNull(snapshot.region());
        assertTrue(snapshot.events().isEmpty());
        assertTrue(snapshot.geometry().isEmpty());
        assertTrue(snapshot.records().isEmpty());
        assertEquals(0, snapshot.droppedEvents());
        assertEquals(0, snapshot.droppedRecords());
    }

    private static void assertResetAndRejects(Watch old, int rendererPlanet, String reason) {
        var reset = snapshot();
        assertTrue(reset.epoch() > old.epoch());
        assertNull(currentWatch(old.epoch()));
        assertFalse(sampleDue(old.epoch()));
        assertNull(reset.region());
        assertTrue(reset.geometry().isEmpty());
        assertTrue(reset.records().isEmpty());
        assertEquals(1, reset.events().size());
        var event = reset.events().getFirst();
        assertEquals(Kind.RESET, event.kind());
        assertEquals(reason, event.reason());
        assertEquals(rendererPlanet, event.planet());
        assertEquals(reset.epoch(), event.epoch());
        publish(old.epoch(), old.planet());
        assertEquals(reset, snapshot());
    }

    private static void assertCoherent(Snapshot snapshot) {
        if (snapshot.watch() == null) {
            assertEquals(0, snapshot.epoch());
            assertEmpty(snapshot);
            return;
        }
        long epoch = snapshot.watch().epoch();
        assertEquals(epoch, snapshot.epoch());
        if (snapshot.region() != null) {
            assertEquals(epoch, snapshot.region().epoch());
            assertEquals(snapshot.watch().planet(), snapshot.region().planet());
            assertEquals(snapshot.rendererPlanet(), snapshot.region().planet());
        }
        long previous = 0;
        for (var event : snapshot.events()) {
            assertEquals(epoch, event.epoch());
            assertEquals(snapshot.rendererPlanet(), event.planet());
            assertTrue(event.sequence() > previous);
            previous = event.sequence();
            if (event.kind() != Kind.RESET) {
                assertEquals(snapshot.watch().planet(), event.planet());
                assertEquals(epoch, event.versions().surfaceRevision());
            }
        }
        for (var geometry : snapshot.geometry()) {
            assertEquals(epoch, geometry.epoch());
            assertEquals(epoch, geometry.versions().surfaceRevision());
            assertEquals(snapshot.watch().planet(), geometry.planet());
            assertEquals(snapshot.rendererPlanet(), geometry.planet());
        }
        for (var record : snapshot.records()) {
            assertEquals(epoch, record.epoch());
            assertEquals(epoch, record.fields().get("epoch"));
            assertEquals(snapshot.watch().planet(), record.planet());
            assertEquals(snapshot.rendererPlanet(), record.planet());
        }
        assertTrue(snapshot.events().size() <= 512);
        assertTrue(snapshot.geometry().size() <= 75);
        assertTrue(snapshot.records().size() <= 128);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("capture workers failed to rendezvous", failure);
        }
    }
}
