package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class GroundLodBuildTraceTest {
    @TempDir Path temp;

    @Test
    void disabledTraceDoesNotAllocateAnEventAndEnabledTracePreservesStageIdentity() throws Exception {
        assertNull(GroundLodBuildTrace.start(null, "disabled"));
        var file = temp.resolve("trace.jfr");
        try (var recording = new Recording()) {
            recording.enable(GroundLodBuildTrace.class).withoutStackTrace();
            recording.start();
            var ticket = new GroundQuadtreeScheduler.Ticket(321, 8, 7, 9);
            var event = GroundLodBuildTrace.start(ticket, "staging");
            assertNotNull(event);
            event.packingNanos = 123;
            event.uploadNanos = 456;
            GroundLodBuildTrace.finish(event, "published", 789);
            recording.stop();
            recording.dump(file);
        }
        var events = RecordingFile.readAllEvents(file).stream()
                .filter(e -> e.getEventType().getName().equals("starfold.LodBuildPhase"))
                .toList();
        assertEquals(1, events.size());
        var e = events.getFirst();
        assertEquals(321, e.getInt("node"));
        assertEquals(7, e.getLong("version"));
        assertEquals(9, e.getLong("session"));
        assertEquals("staging", e.getString("phase"));
        assertEquals("published", e.getString("outcome"));
        assertEquals(123, e.getLong("packingNanos"));
        assertEquals(456, e.getLong("uploadNanos"));
        assertEquals(789, e.getLong("bytes"));
        assertTrue(e.getLong("elapsedNanos") >= 0);
        assertFalse(e.getDuration().isNegative());
        assertNull(GroundLodBuildTrace.start(null, "disabled-again"));
    }
}
