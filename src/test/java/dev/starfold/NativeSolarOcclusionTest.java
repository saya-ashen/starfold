package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** CPU reproduction of native_ground_sun.fsh's ray/sphere interval, not GPU acceptance. */
class NativeSolarOcclusionTest {
    private boolean blocked(Point eye, Point sun, double radius) {
        var delta = sun.sub(eye);
        double distance = delta.length();
        var d = delta.scale(1 / distance);
        double b = eye.dot(d), c = eye.dot(eye) - radius * radius;
        double disc = b * b - c;
        if (disc < 0) return false;
        double root = Math.sqrt(disc), near = -b - root, far = -b + root;
        return far > .0001 && near < distance - .0001;
    }

    @Test void oldSeaLevelSphereHidesOpenSkyUntilPlayerRisesAboutFiveBlocks() {
        double radius = GroundOrbit.RADIUS;
        var sun = new Point(0, radius + 10000, 0);
        assertTrue(blocked(new Point(0, radius - 4.5, 0), sun, radius - .01));
        assertFalse(blocked(new Point(0, radius + .5, 0), sun, radius - .01));
        for (double rise = 0; rise <= 6; rise += .25)
            assertFalse(blocked(new Point(0, radius - 4.5 + rise, 0), sun,
                NativeGroundTopology.SOLAR_CORE_RADIUS));
    }

    @Test void exposedNegativeHeightsRemainOutsideCoreOnAllFacesAndEdges() {
        assertEquals(NativePlanetTerrain.MIN_Y - NativeGroundCoordinates.FLOOR,
            NativeGroundTopology.MIN_HEIGHT);
        for (var face : Face.values())
            for (double u : new double[]{.5, 1024, 2047.5})
                for (double height : new double[]{-190, -24, -5, 0, 48, 160, 1536}) {
                    var n = GroundOrbit.direction(face, u, 1024);
                    var eye = n.scale(GroundOrbit.RADIUS + height);
                    var sun = eye.add(n.scale(10000));
                    assertFalse(blocked(eye, sun, NativeGroundTopology.SOLAR_CORE_RADIUS));
                }
    }

    @Test void CoreStillBlocksFarSideButNotSunInFrontOfPlanet() {
        double radius = GroundOrbit.RADIUS;
        for (var face : Face.values()) {
            var n = face.normal;
            var eye = n.scale(radius + 500);
            assertTrue(blocked(eye, n.scale(-10000), NativeGroundTopology.SOLAR_CORE_RADIUS));
            assertFalse(blocked(eye, n.scale(radius + 200), NativeGroundTopology.SOLAR_CORE_RADIUS));
            assertFalse(blocked(eye, eye.add(face.u.scale(10000)), NativeGroundTopology.SOLAR_CORE_RADIUS));
        }
    }

    @Test void rendererUsesCoreWithoutDisablingActualTerrainDepthOcclusion() throws Exception {
        var source = Files.readString(Path.of("src/main/java/dev/starfold/NativeGroundSunRenderer.java"));
        assertTrue(source.contains("shader.getUniform(\"PlanetRadius\").set((float)NativePlanetShape.coreRadius(NativeGroundClient.planet()))"));
        assertFalse(source.contains("GroundOrbit.RADIUS-.01"));
        assertTrue(source.contains("RenderSystem.enableDepthTest()"));
        var shader = Files.readString(Path.of("src/main/resources/assets/starfold/shaders/core/native_ground_sun.fsh"));
        assertTrue(shader.contains("if(far>0.0001&&near<distance-0.0001) discard"));
    }
    @Test void aPlanetShadowsAVesselOnlyFromBetweenItAndTheSun() {
        var sun=new Point(0,0,0);double radius=1000;
        assertEquals(0,NativeGroundDaylight.sunlight(new Point(30000,0,0),new Point(20000,0,0),sun,radius));
        assertEquals(1,NativeGroundDaylight.sunlight(new Point(30000,0,0),new Point(20000,5000,0),sun,radius));
        assertEquals(1,NativeGroundDaylight.sunlight(new Point(30000,0,0),new Point(40000,0,0),sun,radius));
        // Flown past the sun, the planet left lies straight behind it and shadows nothing.
        assertEquals(1,NativeGroundDaylight.sunlight(new Point(30000,0,0),new Point(-20000,0,0),sun,radius));
    }
}
