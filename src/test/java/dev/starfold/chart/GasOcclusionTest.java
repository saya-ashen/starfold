package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class GasOcclusionTest {
    static GroundEphemeris.Model model() {
        var bodies = new ArrayList<>(GroundSolar.defaults().bodies());
        bodies.add(
                GroundEphemeris.Body.gas(
                        new GroundEphemeris.Fixed(new Point(100, 200, 300)),
                        4096,
                        new GasGiant(1, 42, "Amber", 0, 0xffffff, 256, 512)));
        return new GroundEphemeris.Model(bodies);
    }

    @Test
    void onlyGasCatalogBodiesAreOccludersAndExplicitFramesPreserveCoordinates() {
        assertFalse(GasOcclusion.present(GroundSolar.defaults()));
        assertTrue(GasOcclusion.spheres(GroundSolar.defaults(), 31).isEmpty());
        var model = model();
        assertTrue(GasOcclusion.present(model));
        assertEquals(
                List.of(new GasOcclusion.Sphere(new Point(100, 200, 300), 4096)),
                GasOcclusion.spheres(model, 31));
        var frame =
                new GroundEphemeris.Frame(
                        new Point(9, 8, 7),
                        new org.joml.Quaterniond().rotationY(.7),
                        GroundEphemeris.ZERO,
                        GroundEphemeris.ZERO);
        var local = GasOcclusion.relative(model, 31, frame, -1);
        assertEquals(frame.local(new Point(100, 200, 300)), local.getFirst().center());
        assertEquals(4096, local.getFirst().radius());
        assertEquals(1, GasOcclusion.relative(model, 31, frame, model.index(0)).size());
        assertTrue(GasOcclusion.relative(model, 31, frame, model.bodies().size() - 1).isEmpty());
    }

    @Test
    void finiteSegmentOpaqueInteriorAndSoftLimbMatchThePhysicalConvention() {
        var eye = new Point(-10000, 0, 0);
        var sun = new Point(30000, 0, 0);
        assertEquals(1, GasOcclusion.transmission(List.of(), eye, sun));
        assertEquals(
                0,
                GasOcclusion.transmission(
                        List.of(new GasOcclusion.Sphere(GroundEphemeris.ZERO, 4096)), eye, sun));
        assertEquals(
                1,
                GasOcclusion.transmission(
                        List.of(new GasOcclusion.Sphere(new Point(0, 6000, 0), 4096)), eye, sun));
        for (double x : new double[] {-30000, 50000})
            assertEquals(
                    1,
                    GasOcclusion.transmission(
                            List.of(new GasOcclusion.Sphere(new Point(x, 0, 0), 4096)), eye, sun));
        double light =
                GasOcclusion.transmission(
                        List.of(new GasOcclusion.Sphere(new Point(0, 4096, 0), 4096)), eye, sun);
        assertEquals(GroundGap.smooth(.95 * 4096, 1.1 * 4096, 4096), light);
        assertTrue(light > 0 && light < 1);
        assertEquals(
                0,
                GasOcclusion.transmission(List.of(new GasOcclusion.Sphere(eye, 4096)), eye, sun));
        assertEquals(1, GasOcclusion.transmission(List.of(), eye, eye));
    }

    @Test
    void regionLightAndAtmosphereAttenuationIncludeGasWithoutChangingNoGasValues() {
        var eye = new Point(-10000, 0, 0);
        var bodies =
                List.of(
                        new GroundEphemeris.Body(
                                new GroundEphemeris.Fixed(new Point(30000, 0, 0)),
                                512,
                                true,
                                -1,
                                GroundEphemeris.Appearance.LIGHT),
                        new GroundEphemeris.Body(
                                new GroundEphemeris.Fixed(new Point(-30000, 0, 0)),
                                0,
                                false,
                                0,
                                GroundEphemeris.Appearance.SURFACE));
        var old = new GroundEphemeris.Model(bodies);
        var added = new ArrayList<>(bodies);
        added.add(
                GroundEphemeris.Body.gas(
                        new GroundEphemeris.Fixed(GroundEphemeris.ZERO),
                        4096,
                        new GasGiant(1, 42, "Amber", 0, 0xffffff, 256, 512)));
        var gas = new GroundEphemeris.Model(added);
        assertEquals(1, dev.starfold.space.SpaceView.sunlight(old, eye, 0));
        assertEquals(0, dev.starfold.space.SpaceView.sunlight(gas, eye, 0));
        var atmosphere = dev.starfold.worldgen.PlanetAtmosphere.EARTH;
        var at = new Point(0, 1001, 0);
        var up = new Point(0, 1, 0);
        var sun = new Point(1, 1, 0);
        var full = dev.starfold.sky.AtmosphereScattering.sky(atmosphere, 1000, at, up, sun);
        assertEquals(
                full, dev.starfold.sky.AtmosphereScattering.sky(atmosphere, 1000, at, up, sun, 1));
        assertEquals(
                GroundEphemeris.ZERO,
                dev.starfold.sky.AtmosphereScattering.sky(atmosphere, 1000, at, up, sun, 0));
        var partial = dev.starfold.sky.AtmosphereScattering.sky(atmosphere, 1000, at, up, sun, .5);
        assertTrue(partial.x() > 0 && partial.x() < full.x());
        assertTrue(partial.y() > 0 && partial.y() < full.y());
        assertTrue(partial.z() > 0 && partial.z() < full.z());
        for (double value : new double[] {-1, 2, Double.NaN})
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            dev.starfold.sky.AtmosphereScattering.sky(
                                    atmosphere, 1000, at, up, sun, value));
    }

    @Test
    void managedShaderArrayAndResetPathsMatchTheScalarPackingContract() throws Exception {
        var path =
                java.nio.file.Path.of(
                        "src/main/resources/assets/starfold/shaders/core/native_ground_exterior.json");
        var json =
                com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path))
                        .getAsJsonObject();
        var uniforms = new java.util.HashMap<String, com.google.gson.JsonObject>();
        for (var entry : json.getAsJsonArray("uniforms")) {
            var uniform = entry.getAsJsonObject();
            uniforms.put(uniform.get("name").getAsString(), uniform);
        }
        assertEquals(256, uniforms.get("GasSpheres").get("count").getAsInt());
        assertEquals("float", uniforms.get("GasSpheres").get("type").getAsString());
        assertEquals("int", uniforms.get("GasCount").get("type").getAsString());
        var fragment =
                java.nio.file.Files.readString(path.resolveSibling("native_ground_exterior.fsh"));
        assertTrue(fragment.contains("uniform float GasSpheres[256]"));
        assertTrue(fragment.contains("if(i>=GasCount)break"));
        assertTrue(fragment.contains("radius*0.95,radius*1.1"));
        var managed = dev.starfold.Sources.text("GasLighting");
        assertTrue(
                managed.contains("getUniform(\"GasSpheres\").set(GasOcclusion.packed(spheres))"));
        assertTrue(managed.contains("getUniform(\"GasCount\").set(0)"));
        assertFalse(managed.contains("glUniform"));
        for (String renderer : List.of("GroundExterior", "PlanetRemoteRenderer"))
            assertTrue(dev.starfold.Sources.text(renderer).contains("GasLighting.clear(shader)"));
    }

    @Test
    void scalarUniformPackingIsPaddedBoundedAndNeverEmitsInfinity() {
        var sphere = new GasOcclusion.Sphere(new Point(100, 200, 300), 4096);
        var packed = GasOcclusion.packed(List.of(sphere));
        assertEquals(256, packed.length);
        assertArrayEquals(new float[] {100, 200, 300, 4096}, java.util.Arrays.copyOf(packed, 4));
        for (int i = 4; i < packed.length; i++) assertEquals(0, packed[i]);
        assertThrows(
                IllegalArgumentException.class,
                () -> GasOcclusion.packed(java.util.Collections.nCopies(65, sphere)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GasOcclusion.packed(
                                List.of(new GasOcclusion.Sphere(new Point(1e100, 0, 0), 4096))));
        for (double radius : new double[] {0, -1, Double.NaN, 16385})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GasOcclusion.Sphere(GroundEphemeris.ZERO, radius));
    }
}
