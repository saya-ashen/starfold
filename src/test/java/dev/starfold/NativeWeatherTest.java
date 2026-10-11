package dev.starfold;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeWeatherTest {
    @Test void airTopIsRelativeToTheChartFloor(){double top=NativeGroundCoordinates.FLOOR+GroundOrbit.END;assertFalse(NativeWeather.aboveAir(Math.nextDown(top)));assertTrue(NativeWeather.aboveAir(top));assertTrue(NativeWeather.aboveAir(Math.nextUp(top)));assertFalse(NativeWeather.aboveAir(132));}
    @Test void lightningHookIsOnlyTheWeatherPlacementGate()throws Exception{var text=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/mixin/NativeWeatherThunderMixin.java"));assertTrue(text.contains("method=\"tickChunk\""));assertTrue(text.contains("!NativeWeather.absent(level,pos)&&original.call(level,pos)"));assertFalse(text.contains("ci.cancel"));}
}
