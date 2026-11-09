package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import org.junit.jupiter.api.Test;

class DaylightLightSeparationTest {
    @Test
    void solarBrightnessChangesWithoutStructuralLightWork() throws Exception {
        assertEquals(0, GroundDaylight.darken(1));
        assertEquals(11, GroundDaylight.darken(0));
        assertEquals(6, GroundDaylight.darken(.5));
        // Architectural boundary: clock and daylight consumers must never enqueue structural light.
        for (var type : new String[] {"GroundDaylight", "GroundClock", "GroundLightMixin"}) {
            var source = Sources.text(type);
            for (var forbidden : new String[] {"ImageStream.", "RelightQueue", "checkBlock(",
                    "queueSectionData(", "runLightUpdates(", "onLightUpdate("})
                assertFalse(source.contains(forbidden), type + " must only modulate brightness: " + forbidden);
        }
        var stream = Sources.text("ImageStream");
        assertFalse(stream.contains("GroundDaylight"));
        assertFalse(stream.contains("GroundClock"));
        assertFalse(stream.contains("getDayTime("));
    }
}
