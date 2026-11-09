package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
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

    @Test
    void atmosphereChecksDoNotConvertTheClientPositionTwice() throws Exception {
        var client = Sources.text("WeatherClient");
        assertTrue(client.contains("PlanetConditions.airless(level,mc.player.blockPosition())"));
        assertTrue(
                client.indexOf("PlanetConditions.airless(")
                        < client.indexOf("SkyHost.client().storage("));
        var storage = Sources.text("Weather");
        assertTrue(
                storage.contains(
                        "!level.isClientSide&&dev.starfold.planet.PlanetConditions.airless(level,pos)"));
        var wiring = Sources.text("StarfoldClient");
        assertTrue(
                wiring.contains(
                        "frame.toStorage(pos),frame.planet(),profile.generation().environment()"));
        assertTrue(
                wiring.contains(
                        "profile.session()!=dev.starfold.flight.GroundFlightClient.session()"));
    }

    @Test
    void lightningHookIsOnlyTheWeatherPlacementGate() throws Exception {
        var text = Sources.text("WeatherThunderMixin");
        assertTrue(text.contains("method=\"tickChunk\""));
        assertTrue(text.contains("!Weather.absent(level,pos)&&original.call(level,pos)"));
        assertFalse(text.contains("ci.cancel"));
    }
}
