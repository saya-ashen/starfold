package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DaylightLightSeparationTest {
    @Test
    void solarBrightnessChangesWithoutStructuralLightWork() throws Exception {
        assertEquals(0, GroundDaylight.darken(1));
        assertEquals(11, GroundDaylight.darken(0));
        assertEquals(6, GroundDaylight.darken(.5));
    }
}
