package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundOrbit;
import dev.starfold.planet.GroundCoordinates;

import org.junit.jupiter.api.Test;

class WeatherTest {
    @Test
    void airTopIsRelativeToTheChartFloor() {
        double top = GroundCoordinates.FLOOR + GroundOrbit.END;
        assertFalse(Weather.aboveAir(Math.nextDown(top)));
        assertTrue(Weather.aboveAir(top));
        assertTrue(Weather.aboveAir(Math.nextUp(top)));
        assertFalse(Weather.aboveAir(132));
    }
}
