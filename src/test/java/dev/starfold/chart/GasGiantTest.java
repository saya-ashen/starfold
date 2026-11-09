package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;

import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;

class GasGiantTest {
    static GasGiant policy() {
        return new GasGiant(1, 31, "Amber", 0x98653d, 0xead1a2, 256, 512);
    }

    static GroundEphemeris.Model mixed() {
        var base = GroundSolar.defaults();
        var bodies = new ArrayList<>(base.bodies());
        var path =
                new GroundEphemeris.Orbit(
                        0,
                        GroundEphemeris.Y,
                        new Point(0, 0, 1),
                        40000,
                        240000,
                        Math.PI / 2,
                        0,
                        new GroundEphemeris.Spin(
                                true, GroundEphemeris.Y, new org.joml.Quaterniond(), 0, 96000, 0));
        bodies.add(GroundEphemeris.Body.gas(path, 4096, policy()));
        return new GroundEphemeris.Model(bodies);
    }

    @Test
    void policyIsVersionedBoundedAndRoundTripsWithoutDuplicatingRadius() {
        var p = policy();
        var json = GasGiant.CODEC.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        assertEquals(p, GasGiant.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        assertFalse(json.getAsJsonObject().has("radius"));
        assertThrows(IllegalArgumentException.class, () -> new GasGiant(2, 1, "A", 0, 1, 256, 512));
        for (String name : new String[] {"", "\nA", "x".repeat(65)})
            assertThrows(
                    IllegalArgumentException.class, () -> new GasGiant(1, 1, name, 0, 1, 256, 512));
        for (int color : new int[] {-1, 0x1000000})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GasGiant(1, 1, "A", color, 1, 256, 512));
        for (double v : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 15, 1025})
            assertThrows(
                    IllegalArgumentException.class, () -> new GasGiant(1, 1, "A", 0, 1, v, 512));
        for (double v : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, 15, 2049})
            assertThrows(
                    IllegalArgumentException.class, () -> new GasGiant(1, 1, "A", 0, 1, 256, v));
        for (double radius : new double[] {Double.NaN, 1023, 16385})
            assertThrows(IllegalArgumentException.class, () -> p.validateRadius(radius));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GasGiant(1, 1, "A", 0, 1, 256, 1024).validateRadius(1024));
    }

    @Test
    void gasCapabilityCannotAcquireLightSurfaceStorageOrSolidAtmosphere() {
        var m = mixed();
        var gas = m.catalogBody(m.bodies().size() - 1);
        assertEquals(-1, gas.slot());
        assertEquals(0, gas.reach());
        assertFalse(gas.light());
        assertEquals(GroundSolar.defaults().everyStar(), m.everyStar());
        assertEquals(GroundSolar.defaults().visibleStars(), m.visibleStars());
        for (var appearance : GroundEphemeris.Appearance.values()) {
            if (appearance == GroundEphemeris.Appearance.GAS) continue;
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new GroundEphemeris.Body(
                                    gas.motion(),
                                    gas.radius(),
                                    false,
                                    0,
                                    2048,
                                    appearance,
                                    null,
                                    gas.atmosphere(),
                                    0,
                                    gas.gas()));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundEphemeris.Body(
                                gas.motion(),
                                gas.radius(),
                                true,
                                -1,
                                0,
                                gas.appearance(),
                                null,
                                gas.atmosphere(),
                                0,
                                gas.gas()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundEphemeris.Body(
                                gas.motion(),
                                gas.radius(),
                                false,
                                0,
                                2048,
                                gas.appearance(),
                                null,
                                gas.atmosphere(),
                                0,
                                gas.gas()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundEphemeris.Body(
                                gas.motion(),
                                gas.radius(),
                                false,
                                -1,
                                0,
                                gas.appearance(),
                                null,
                                gas.atmosphere(),
                                0,
                                Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> gas.withAtmosphere(dev.starfold.worldgen.PlanetAtmosphere.EARTH));
    }

    @Test
    void mixedCatalogTargetsAreExplicitAndNeverImplicitSurfaceSlots() {
        var m = mixed();
        int gasIndex = m.bodies().size() - 1;
        assertEquals(GroundSolar.defaults().count(), m.count());
        assertEquals(m.bodies().size(), m.catalogIndices().size());
        assertEquals(m.bodies(), m.catalogIndices().stream().map(m::catalogBody).toList());
        var gas = CelestialTarget.resolve(m, gasIndex);
        assertEquals(m.frame(gasIndex, 50), gas.frame(m, 50));
        assertTrue(gas.surfaceSlot(m).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> gas.requireSurfaceSlot(m));
        assertThrows(IllegalArgumentException.class, () -> m.slot(gas.body(m).id()));
        assertThrows(IllegalArgumentException.class, () -> m.at(gasIndex, 50));
        assertTrue(CelestialTarget.resolve(m, 0).surfaceSlot(m).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CelestialTarget.resolve(m, 0).requireSurfaceSlot(m));
        assertThrows(IllegalArgumentException.class, () -> CelestialTarget.resolve(m, -1));
        assertThrows(
                IllegalArgumentException.class, () -> CelestialTarget.resolve(m, gasIndex + 1));
        for (int slot = 0; slot < m.count(); slot++) {
            var target = CelestialTarget.resolve(m, m.index(slot));
            assertEquals(slot, target.requireSurfaceSlot(m));
            assertEquals(m.at(slot, 50), target.frame(m, 50));
        }
    }

    @Test
    void copySpinAndLargeShellExtentRetainGasWithoutChangingOldSurfaces() {
        var m = mixed();
        int index = m.bodies().size() - 1;
        var body = m.catalogBody(index);
        assertEquals(policy(), body.identified(new BodyId(4, 9)).gas().orElseThrow());
        assertEquals(policy(), body.withAtmosphere(body.atmosphere()).gas().orElseThrow());
        var paused = GroundEphemeris.spinning(m, false, 123);
        var resumed = GroundEphemeris.spinning(paused, true, 123);
        assertEquals(body.gas(), paused.catalogBody(index).gas());
        assertEquals(body.id(), resumed.catalogBody(index).id());
        assertEquals(m.frame(index, 123).rotation(), paused.frame(index, 124).rotation());
        assertEquals(m.frame(index, 123).rotation(), resumed.frame(index, 123).rotation());
        assertEquals(
                96000,
                ((GroundEphemeris.Orbit) resumed.catalogBody(index).motion()).spin().period());
        var base = GroundSolar.defaults();
        for (int slot = 0; slot < base.count(); slot++)
            assertEquals(GroundEphemeris.spinning(base, false, 123).body(slot), paused.body(slot));
        var bodies = new ArrayList<>(base.bodies());
        var position = new Point(500000, 0, 0);
        bodies.add(GroundEphemeris.Body.gas(new GroundEphemeris.Fixed(position), 16384, policy()));
        var huge = new GroundEphemeris.Model(bodies);
        assertTrue(huge.extent() >= position.sub(huge.origin()).length() + 16384 + 256);
        assertEquals(base.extent(), new GroundEphemeris.Model(base.bodies()).extent());
        var invalid = new ArrayList<>(base.bodies());
        invalid.add(
                GroundEphemeris.Body.gas(
                        new GroundEphemeris.Fixed(new Point(1e100, 0, 0)), 4096, policy()));
        assertThrows(IllegalArgumentException.class, () -> new GroundEphemeris.Model(invalid));
    }

    @Test
    void pressureAndDamageCadenceAreMonotoneAndFinite() {
        var gas = policy();
        assertEquals(0, gas.pressure(-1000));
        assertEquals(0, gas.pressure(-256));
        assertEquals(1.0 / 3, gas.pressure(0), 1e-15);
        assertEquals(.5, gas.pressure(128));
        assertEquals(1, gas.pressure(512));
        assertEquals(1, gas.pressure(1024));
        assertEquals(0, GasGiant.damageInterval(Math.nextDown(.5)));
        assertEquals(100, GasGiant.damageInterval(.5));
        assertEquals(60, GasGiant.damageInterval(.75));
        assertEquals(20, GasGiant.damageInterval(1));
        double previous = 0;
        int interval = 100;
        for (int depth = -512; depth <= 1024; depth++) {
            double p = gas.pressure(depth);
            assertTrue(p >= previous);
            previous = p;
            if (p >= .5) {
                assertTrue(GasGiant.damageInterval(p) <= interval);
                interval = GasGiant.damageInterval(p);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> gas.pressure(Double.NaN));
        for (double p : new double[] {-1, 2, Double.NaN})
            assertThrows(IllegalArgumentException.class, () -> GasGiant.damageInterval(p));
    }
}
