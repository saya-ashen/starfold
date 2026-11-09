package dev.starfold.api;

import dev.starfold.chart.GasGiant;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.worldgen.PlanetAtmosphere;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SystemMapApiTest {
    @Test void orreryPaletteKeepsCatalogGasBandsAndAtmosphereWithoutRequestingTerrain() {
        var motion = new GroundEphemeris.Fixed(GroundEphemeris.ZERO);
        var rocky = new GroundEphemeris.Body(motion, 1000, false, 0, GroundEphemeris.Appearance.SURFACE);
        var star = new GroundEphemeris.Body(motion, 2000, true, -1, GroundEphemeris.Appearance.LIGHT);
        var gas = GroundEphemeris.Body.gas(motion, 2000,
                new GasGiant(1, 42, "gas", 0x835734, 0xf1c688, 256, 512));
        assertEquals(new SystemMapApi.Colors(0xffa2a8af, 0xffa2a8af), SystemMapApi.colors(rocky));
        assertEquals(new SystemMapApi.Colors(0xff78a7ff, 0xff78a7ff), SystemMapApi.colors(rocky.withAtmosphere(PlanetAtmosphere.EARTH)));
        var red = new PlanetAtmosphere(.3, 0xaa3210, false);
        assertEquals(new SystemMapApi.Colors(0xffaa3210, 0xffaa3210), SystemMapApi.colors(rocky.withAtmosphere(red)));
        assertEquals(new SystemMapApi.Colors(0xffffd584, 0xffffd584), SystemMapApi.colors(star));
        assertEquals(new SystemMapApi.Colors(0xff835734, 0xfff1c688), SystemMapApi.colors(gas));
    }
}
