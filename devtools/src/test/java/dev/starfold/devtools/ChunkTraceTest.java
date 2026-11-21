package dev.starfold.devtools;

import dev.starfold.api.ChunkWorkDiagnostics.Stage;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkTraceTest {
    @Test
    void exportsOrderedEventsAndPreservesCapacityForEachStage() throws Exception {
        var player = new UUID(1, 2);
        var trace = new ChunkTrace(100, null, player, 1);
        long chunk = (4L << 32) | Integer.toUnsignedLong(-2);
        trace.record(Stage.FILL, chunk, 150, 35);
        trace.record(Stage.FILL, chunk, 155, 5);
        trace.record(Stage.SENT, chunk, 99, 0);
        trace.record(Stage.FULL_REQUEST, chunk, 110, 0);
        trace.imageSent(new UUID(3, 4), chunk, null, 125, 10);
        trace.imageSent(player, chunk, (5L << 32) | 3, 175, 20);
        trace.close();
        trace.record(Stage.SENT, chunk, 200, 0);

        var csv = new StringWriter();
        trace.csv(csv);
        assertEquals("stage,chunk_x,chunk_z,elapsed_ns,duration_ns,source_x,source_z,section_y\n"
                + "FULL_REQUEST,-2,4,10,0,,,\nFILL,-2,4,50,35,,,\nIMAGE_SENT,-2,4,75,20,3,5,\n", csv.toString());
        var summary = new StringWriter();
        trace.summary(summary);
        assertTrue(summary.toString().contains("FILL recorded=1 dropped=1"));
        assertTrue(summary.toString().contains("SENT recorded=0 dropped=0"));
    }

    @Test
    void imageRetriesRecordOneAttemptUntilSend() throws Exception {
        var player = new UUID(1, 2);
        var trace = new ChunkTrace(100, null, player, 10);
        trace.imageAttempt(new UUID(3, 4), 9, 110);
        trace.imageAttempt(player, 9, 120);
        trace.imageAttempt(player, 9, 130);
        trace.imageSent(player, 9, null, 140, 1);
        trace.imageAttempt(player, 9, 150);
        trace.close();
        var csv = new StringWriter();
        trace.csv(csv);
        assertEquals(java.util.List.of("IMAGE_FIRST_ATTEMPT,9,0,20,0,,,", "IMAGE_FIRST_ATTEMPT,9,0,50,0,,,"),
                csv.toString().lines().filter(line -> line.startsWith("IMAGE_FIRST_ATTEMPT,")).toList());
    }

    @Test
    void onlyDrawsAfterAnObservedClientLoadAreRecorded() throws Exception {
        long chunk = (5L << 32) | 3;
        long origin = System.nanoTime();
        var trace = new ChunkTrace(origin, null, null);
        trace.drawn(chunk, 17);
        trace.clientLoad(chunk, origin);
        trace.meshCompiled(chunk, 17, origin, 0);
        trace.drawn(chunk, 17);
        trace.drawn(chunk, 18);
        trace.close();

        var csv = new StringWriter();
        trace.csv(csv);
        var draws = csv.toString().lines().filter(line -> line.startsWith("FIRST_DRAW,3,5,")).toList();
        assertEquals(1, draws.size());
        assertTrue(draws.getFirst().endsWith(",17"));
        assertTrue(csv.toString().contains("CLIENT_MESH_COMPILED,3,5,0,0,,,17\n"));
    }
}
