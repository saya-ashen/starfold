package dev.starfold.atlas;

import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanetMapTest {
    @Test void fogRoundTripSeparatesOrbitalRevealFromDetailedExplorationAndRejectsBadFiles() throws IOException {
        var fog = new SurveyRecord(256);
        assertFalse(fog.revealed()); assertFalse(fog.explored(2, 12, 31));
        fog.explore(2, 12, 31); fog.explore(5, 63, 63); fog.reveal();
        int revision = fog.revision(); fog.explore(2, 12, 31); fog.reveal(); assertEquals(revision, fog.revision());
        var bytes = new ByteArrayOutputStream(); fog.write(new DataOutputStream(bytes));
        var copy = SurveyRecord.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), 256);
        assertTrue(copy.revealed()); assertTrue(copy.explored(2, 12, 31)); assertTrue(copy.explored(5, 63, 63));
        assertFalse(copy.explored(2, 12, 30)); assertFalse(copy.explored(0, 12, 31));
        assertThrows(IOException.class, () -> SurveyRecord.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), 512));
        assertThrows(IOException.class, () -> SurveyRecord.read(new DataInputStream(new ByteArrayInputStream(new byte[20])), 256));
        var bad = new ByteArrayOutputStream(); var out = new DataOutputStream(bad);
        out.writeInt(1); out.writeInt(256); out.writeBoolean(false); out.writeInt(Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> SurveyRecord.read(new DataInputStream(new ByteArrayInputStream(bad.toByteArray())), 256));
    }
}
