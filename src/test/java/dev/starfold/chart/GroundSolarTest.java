package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.sky.GroundEphemerisSave;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class GroundSolarTest {
    final GroundEphemeris.Model model = GroundSolar.defaults();
    static final String GAP_VERTEX =
            "vec3 n=normalize(LightNormal*1024.0+LightU*(Position.x-1024.0)+LightV*(Position.z-1024.0));\n    solarPosition=n*(1303.7972938088067+Position.y);\n    vec3 axis=ids.g==2?-LightV:ids.g==3?LightV:ids.g==4?-LightU:LightU;\n    solarNormal=ids.g==1?n:ids.g==0?-n:normalize(axis-n*dot(axis,n));\n    ";
    static final String GAP_LIGHT =
            "if(SunActive>0.5){vec3 sun=normalize(SunPosition-solarPosition);float day=smoothstep(-0.12,0.12,dot(normalize(solarPosition),sun));light=0.035+0.10*day+0.865*day*max(0.0,dot(normalize(solarNormal),sun));}\n    ";
    static final String ORBIT_LIGHT = GAP_LIGHT.replace("solarNormal", "surfaceNormal");

    Point surface(Face face, double t) {
        return model.at(0, t)
                .world(GroundOrbit.direction(face, 1024, 1024).scale(GroundOrbit.RADIUS + .94));
    }

    @Test
    void oneActivePlanetOrbitsOneFixedSunWithoutAnyGravity() {
        assertEquals(1, model.count());
        assertTrue(model.sun().enabled());
        assertEquals(model.center(), model.sun().position());
        assertEquals(512, model.sun().radius());
        for (double t : new double[] {0, 6000, 12000, 24000, 99999999})
            assertEquals(
                    model.radius(),
                    model.at(0, t).center().sub(model.sun().position()).length(),
                    1e-7);
        assertEquals(48000, model.a().period());
    }

    @Test
    void newSavesAreSolarAndRoundTripSunAxisPhaseClock() {
        var s = GroundEphemerisSave.fresh(false);
        assertTrue(s.model.sun().enabled());
        for (int i = 0; i < 300; i++) s.advance();
        var tag = s.save(new CompoundTag(), null);
        assertEquals(3, tag.getInt("format"));
        var copy = GroundEphemerisSave.load(tag, null);
        assertEquals(s.model, copy.model);
        assertEquals(s.tick(), copy.tick());
        assertEquals(tag, copy.save(new CompoundTag(), null));
        assertThrows(IllegalStateException.class, () -> GroundEphemerisSave.fresh(true));
    }

    @Test
    void sunDataRejectsNonfiniteMissingAndOverlappingParameters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundSolar.Sun(true, new Point(Double.NaN, 0, 0), 512));
        assertThrows(
                IllegalArgumentException.class, () -> new GroundSolar.Sun(true, model.center(), 0));
        var t = GroundEphemerisSave.fresh(false).save(new CompoundTag(), null);
        t.getCompound("motion").remove("sun");
        assertThrows(IllegalArgumentException.class, () -> GroundEphemerisSave.load(t, null));
    }

    @Test
    void defaultTopSiteHasBothDayAndNightRatherThanPermanentPolarTwilight() {
        double min = 1, max = -1;
        for (int t = 0; t <= 48000; t += 60) {
            double e = GroundSolar.elevation(model, 0, surface(Face.TOP, t), t);
            min = Math.min(min, e);
            max = Math.max(max, e);
        }
        assertTrue(min < -.12, "min=" + min);
        assertTrue(max > .12, "max=" + max);
    }

    @Test
    void opposingSurfaceSitesCannotBeGloballyDayOrNightTogether() {
        for (int t : new int[] {0, 6000, 12000, 18000}) {
            var centre = model.at(0, t).center();
            var towards = GroundSolar.direction(model, centre);
            var day = centre.add(towards.scale(GroundOrbit.RADIUS + .94));
            var night = centre.sub(towards.scale(GroundOrbit.RADIUS + .94));
            assertEquals(1, GroundSolar.daylight(GroundSolar.elevation(model, 0, day, t)), 1e-9);
            assertEquals(0, GroundSolar.daylight(GroundSolar.elevation(model, 0, night, t)), 1e-9);
        }
    }

    @Test
    void sunriseAndSkyStarsAreContinuousAndAltitudeBlendsToSpace() {
        double last = 0;
        for (double e = -1; e <= 1; e += .002) {
            double value = GroundSolar.daylight(e);
            assertTrue(value >= last - 1e-9);
            last = value;
            assertTrue(GroundSolar.sky(e, 0).finite());
            assertEquals(1 - value, GroundSolar.stars(e, 0), 1e-9);
            assertEquals(new Point(0, 0, 0), GroundSolar.sky(e, 1));
            assertEquals(1, GroundSolar.stars(e, 1));
        }
        assertEquals(.5, GroundSolar.daylight(0), 1e-9);
    }

    @Test
    void solarLightDependsOnPhysicalSurfaceAndSunNotCamera() {
        var p = new Point(0, GroundOrbit.RADIUS, 0);
        assertEquals(1, GroundSolar.light(p, GroundEphemeris.Y, new Point(0, 8192, 0)), 1e-9);
        assertEquals(.035, GroundSolar.light(p, GroundEphemeris.Y, new Point(0, -8192, 0)), 1e-9);
        assertEquals(.135, GroundSolar.light(p, new Point(1, 0, 0), new Point(0, 8192, 0)), 1e-9);
    }

    @Test
    void planetOccludesSunOnlyWhenBetweenObserverAndSun() {
        var c = new Point(0, 0, 0);
        assertTrue(
                GroundSolar.occulted(
                        new Point(0, 0, 2000), new Point(0, 0, -8192), c, GroundOrbit.RADIUS));
        assertFalse(
                GroundSolar.occulted(
                        new Point(0, 0, -2000), new Point(0, 0, -8192), c, GroundOrbit.RADIUS));
        assertFalse(
                GroundSolar.occulted(
                        new Point(0, 3000, 0), new Point(0, 3000, -8192), c, GroundOrbit.RADIUS));
        assertFalse(
                GroundSolar.occulted(
                        new Point(0, 0, 2000), new Point(0, 0, 1900), c, GroundOrbit.RADIUS));
    }
}
