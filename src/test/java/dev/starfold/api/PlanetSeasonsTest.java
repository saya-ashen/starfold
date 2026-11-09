package dev.starfold.api;

import dev.starfold.chart.GroundEphemeris.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.worldgen.PlanetAtmosphere;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanetSeasonsTest {
    static dev.starfold.worldgen.plan.terrain.TerrainPlan terrain;
    @org.junit.jupiter.api.BeforeAll static void boot(){dev.starfold.worldgen.PlanetTerrainTest.registry();terrain=new dev.starfold.worldgen.PlanetTerrain(42).terrainPlan();}

    static Model model(PlanetAtmosphere atmosphere) {
        var spin = new Spin(true, new Point(0,1,0), new Quaterniond().rotationZ(Math.toRadians(23.5)), 0, 48000, 0);
        return new Model(java.util.List.of(
                new Body(new Fixed(new Point(0,0,0)), 100, true, -1, Appearance.LIGHT),
                new Body(new Orbit(0, new Point(0,1,0), new Point(1,0,0), 17800, 24000, 0, 0, spin),
                        1304, false, 0, 2048, Appearance.SURFACE, null, atmosphere)));
    }
    @Test void hemispheresHaveOppositeSeasonsAndTemperatures() {
        var m = model(PlanetAtmosphere.EARTH);
        var n = new Point(.6,.8,0);
        var a = PlanetSeasons.sample(m,0,n,0,3000,terrain);
        var b = PlanetSeasons.sample(m,0,n.scale(-1),0,3000,terrain);
        assertEquals(.5, Math.abs(a.phase()-b.phase()), 1e-9);
        assertNotEquals(a.temperature(),b.temperature());
        assertEquals(a.phase(),PlanetSeasons.sample(m,0,n.scale(-1),0,15000,terrain).phase(), .03);
    }
    @Test void polesAreColderAndAltitudeCools() {
        var f = new PlanetSeasons.Forcing(new Point(0,1,0),0,0,1,0,0);
        var equator = PlanetSeasons.sample(f,new Point(1,0,0),0,terrain);
        assertTrue(PlanetSeasons.sample(f,new Point(0,1,0),0,terrain).temperature()<equator.temperature());
        assertEquals(equator.temperature()-6.5,PlanetSeasons.sample(f,new Point(1,0,0),1000,terrain).temperature(),1e-9);
    }
    @Test void temperatureOffsetWarmsReadingsAndEliminatesPolarSnowAllYear() {
        var cold = model(PlanetAtmosphere.EARTH);
        var bodies = new java.util.ArrayList<>(cold.bodies());
        bodies.set(1, bodies.get(1).withEnvironment(
                new dev.starfold.worldgen.PlanetEnvironment(PlanetAtmosphere.EARTH, 1, 70)));
        var warm = new Model(bodies);
        var warmTerrain=new dev.starfold.worldgen.plan.terrain.TerrainPlanner(42,terrain.size(),terrain.recipe(),70).generate();
        for (int t=0;t<24000;t+=600) for (int pole:new int[]{-1,1}) for (int altitude:new int[]{-200,200}) {
            var up = new Point(0,pole,0);
            var before = PlanetSeasons.sample(cold,0,up,altitude,t,terrain);
            var after = PlanetSeasons.sample(warm,0,up,altitude,t,warmTerrain);
            assertEquals(before.temperature()+70,after.temperature(),1e-5);
            assertTrue(before.snow()>0);
            assertEquals(0,after.snow());
            var eye = warm.at(0,t).world(up.scale(warm.body(0).radius()+altitude));
            assertEquals(after.temperature(),PlanetSeasons.reading(warm,0,eye,t,warmTerrain).temperature(),1e-9);
        }
        // Even maximum tilt, winter forcing, altitude and edge noise cannot produce snow.
        var worst = new PlanetSeasons.Forcing(new Point(0,1,0),0,-40,1,1,70);
        assertTrue(PlanetSeasons.sample(worst,new Point(0,1,0),200,warmTerrain).temperature()>=5);
    }
    @Test void airlessNeverSnowsAndResultsAreDeterministic() {
        var m = model(PlanetAtmosphere.NONE);
        for (int t=0;t<24000;t+=1000) {
            var a=PlanetSeasons.sample(m,0,new Point(0,1,0),2000,t,terrain);
            assertEquals(0,a.snow());
            assertEquals(a,PlanetSeasons.sample(m,0,new Point(0,1,0),2000,t,terrain));
        }
    }
    @Test void yearRepeatsAndSnowIsBounded() {
        var m=model(PlanetAtmosphere.EARTH);
        for(int t=0;t<24000;t+=600) {
            var a=PlanetSeasons.sample(m,0,new Point(.6,.8,0),100,t,terrain);
            var b=PlanetSeasons.sample(m,0,new Point(.6,.8,0),100,t+24000,terrain);
            assertEquals(a.temperature(),b.temperature(),1e-8);
            assertTrue(a.snow()>=0&&a.snow()<=1);
        }
    }
    @Test void validClockEndpointsAreSupported() {
        var m=model(PlanetAtmosphere.EARTH);
        for(double tick:new double[]{0,dev.starfold.chart.GroundEphemeris.MAX_TICK})
            assertTrue(Double.isFinite(PlanetSeasons.sample(m,0,new Point(0,1,0),0,tick,terrain).temperature()));
    }
    @Test void absentAppearanceDoesNotChangeShaders() {
        assertEquals(PlanetSurfaceRender.Patch.NONE,PlanetSurfaceRender.near("rendertype_solid"));
        String source="void main(){surfaceColor.rgb*light*AtmosphereGrade;}";
        assertEquals(source,PlanetSurfaceRender.exterior("starfold:native_ground_exterior",false,source));
    }
    @Test void readingClockRunsForwardThroughOneLocalDay() {
        var spin = new Spin(true, new Point(0,1,0), new Quaterniond().rotationZ(Math.toRadians(23.5)), 0, 24000, 0);
        var m = new Model(java.util.List.of(
                new Body(new Fixed(new Point(0,0,0)), 100, true, -1, Appearance.LIGHT),
                new Body(new Orbit(0, new Point(0,1,0), new Point(1,0,0), 17800, 192000, 0, 0, spin),
                        1304, false, 0, 2048, Appearance.SURFACE, null, PlanetAtmosphere.EARTH)));
        var up = new Point(.6,0,.8);
        double wraps = 0, last = -1;
        for (int t=0;t<=48000;t+=500) {
            var eye = m.at(0,t).world(up.scale(m.body(0).radius() + 64));
            var r = PlanetSeasons.reading(m,0,eye,t,terrain);
            assertTrue(r.days()>=6&&r.days()<=9, "solar days per year " + r.days());
            assertTrue(r.day()>=1&&r.day()<=r.days()&&r.season()>=0&&r.season()<4);
            if (last >= 0) {
                double d = r.hour()-last;
                if (d < 0) { wraps++; d += 24; }
                assertTrue(d > 0 && d < 1, "hour step " + d);
            }
            last = r.hour();
        }
        assertTrue(wraps >= 1 && wraps <= 2);
    }
}
