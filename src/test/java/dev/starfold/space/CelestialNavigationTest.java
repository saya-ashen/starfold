package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class CelestialNavigationTest {
    static final GasGiant GAS = new GasGiant(1, 42, "Amber", 0x906040, 0xe0c090, 256, 512);

    static GroundEphemeris.Model mixed() {
        var bodies = new ArrayList<>(GroundSolar.defaults().bodies());
        bodies.add(
                GroundEphemeris.Body.gas(
                        new GroundEphemeris.Orbit(
                                0,
                                GroundEphemeris.Y,
                                new Point(0, 0, 1),
                                40000,
                                240000,
                                0,
                                0,
                                new GroundEphemeris.Spin(
                                        false,
                                        GroundEphemeris.Y,
                                        new org.joml.Quaterniond(),
                                        0,
                                        96000,
                                        0)),
                        4096,
                        GAS));
        return new GroundEphemeris.Model(bodies);
    }

    @Test
    void cyclesPreserveLegacySurfaceOrderButExposeGasInCatalogDomain() {
        var old = GroundSolar.defaults();
        var ship = new Point(4000, 8000, -1000);
        for (int reference = -1; reference < old.count(); reference++) {
            var expected = SpaceNavigation.cycle(old, ship, 12, reference);
            assertEquals(
                    expected,
                    CelestialNavigation.cycle(old, ship, 12, reference).stream()
                            .map(t -> t.requireSurfaceSlot(old))
                            .toList());
        }
        var model = mixed();
        var targets = CelestialNavigation.targets(model);
        assertEquals(model.count() + 1, targets.size());
        assertFalse(targets.stream().anyMatch(t -> t.body(model).light()));
        var gas = targets.getLast();
        assertEquals(2, gas.bodyIndex());
        assertTrue(gas.surfaceSlot(model).isEmpty());
        var cycle = CelestialNavigation.cycle(model, ship, 12, 0);
        assertEquals(model.index(0), cycle.getLast().bodyIndex());
        assertTrue(cycle.contains(gas));
        CelestialTarget selected = null;
        for (var expected : cycle) {
            selected = CelestialNavigation.next(model, ship, 12, selected, 0).orElseThrow();
            assertEquals(expected, selected);
        }
        assertTrue(CelestialNavigation.next(model, ship, 12, selected, 0).isEmpty());
    }

    @Test
    void choosingTargetsUsesTheExistingNoseConeAndStablePixelTies() {
        var model = mixed();
        var surface = CelestialTarget.resolve(model, model.index(0));
        var gas = CelestialTarget.resolve(model, model.bodies().size() - 1);
        var center = new SpaceNavigation.Marker(.5, .5, false, 0);
        var side = new SpaceNavigation.Marker(.6, .5, false, 0);
        assertEquals(
                gas,
                CelestialNavigation.choose(
                                List.of(
                                        new CelestialNavigation.Candidate(surface, side, 0),
                                        new CelestialNavigation.Candidate(gas, center, 20)),
                                1280,
                                720)
                        .orElseThrow());
        assertEquals(
                surface,
                CelestialNavigation.choose(
                                List.of(
                                        new CelestialNavigation.Candidate(surface, center, 0),
                                        new CelestialNavigation.Candidate(gas, center, 0)),
                                1280,
                                720)
                        .orElseThrow());
        for (double angle : new double[] {-1, Math.nextUp(20.0), Double.NaN})
            assertTrue(
                    CelestialNavigation.choose(
                                    List.of(new CelestialNavigation.Candidate(gas, center, angle)),
                                    1280,
                                    720)
                            .isEmpty());
        assertTrue(CelestialNavigation.choose(List.of(), 1280, 720).isEmpty());
    }

    @Test
    void gasReadingsUseActualOrbitalVelocityAndConservativeHullDepthNotLandingHeight() {
        var model = mixed();
        var target = CelestialTarget.resolve(model, model.bodies().size() - 1);
        double tick = 1234;
        var frame =
                new Space.Frame(
                        new org.joml.Quaterniond().rotationY(.7),
                        Space.ORIGIN,
                        0,
                        GroundEphemeris.ZERO,
                        new Point(2, 3, 4),
                        0);
        var gas = target.frame(model, tick);
        var position = gas.center().add(new Point(4096 - 128, 0, 0));
        var point = CelestialNavigation.read(model, frame, position, target, tick, 0);
        var hull = CelestialNavigation.read(model, frame, position, target, tick, 64);
        assertEquals(-128, point.distance(), 1e-9);
        assertEquals(128, point.gas().orElseThrow().depth(), 1e-9);
        assertEquals(.5, point.gas().orElseThrow().pressure(), 1e-9);
        assertEquals(384, point.gas().orElseThrow().remaining(), 1e-9);
        assertEquals(192, hull.gas().orElseThrow().depth(), 1e-9);
        assertTrue(hull.gas().orElseThrow().pressure() > point.gas().orElseThrow().pressure());
        var velocity =
                frame.universeVelocity(GroundEphemeris.ZERO, model, tick).sub(gas.velocity());
        assertEquals(velocity, CelestialNavigation.velocity(model, frame, target, tick));
        assertEquals(
                SpaceNavigation.closing(
                        gas.center().sub(position),
                        frame.universeVelocity(GroundEphemeris.ZERO, model, tick),
                        gas.velocity()),
                point.closing());
        assertEquals(
                SpaceNavigation.velocity(model, frame, -1, tick),
                CelestialNavigation.velocity(model, frame, null, tick));
        for (double padding : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CelestialNavigation.read(model, frame, position, target, tick, padding));
    }

    @Test
    void oldSurfaceReadingsAndRelativeVelocityRemainExactlyEqual() {
        var model = mixed();
        var frame = Space.Frame.IDENTITY;
        var position = new Point(10000, 4000, -3000);
        for (int slot = 0; slot < model.count(); slot++) {
            var target = CelestialTarget.resolve(model, model.index(slot));
            var old = SpaceNavigation.read(model, frame, position, slot, 73);
            var reading = CelestialNavigation.read(model, frame, position, target, 73, 20);
            assertEquals(old.distance(), reading.distance());
            assertEquals(old.closing(), reading.closing());
            assertTrue(reading.gas().isEmpty());
            assertEquals(
                    SpaceNavigation.velocity(model, frame, slot, 73),
                    CelestialNavigation.velocity(model, frame, target, 73));
        }
    }

    @Test
    void adaptersUseTypedTargetsAndKeepGasOutOfLandingAndSurfaceCaptions() throws Exception {
        var server = dev.starfold.Sources.text("SpaceServer");
        assertTrue(server.contains("packet.bodyIndex()"));
        assertTrue(server.contains("CelestialTarget.resolve(model,bodyIndex)"));
        assertTrue(server.contains("celestial.surfaceSlot(model).orElse(-1)"));
        assertTrue(server.contains("target.celestial().frame(model,tick).velocity()"));
        var client = dev.starfold.Sources.text("SpaceNavigationClient");
        assertTrue(client.contains("private static CelestialTarget selection"));
        assertTrue(client.contains("CelestialNavigation.next"));
        assertTrue(client.contains("CelestialNavigation.read"));
        assertTrue(client.contains("selection.surfaceSlot"));
        assertFalse(client.contains("new SpacePackets.Target(planet)"));
        var widgets = dev.starfold.Sources.text("FlightWidgets");
        assertTrue(widgets.contains("tr(\"gas_nonlandable\")"));
        assertTrue(widgets.contains("tr(\"gas_pressure\""));
        var fields =
                java.util.Arrays.stream(FlightSnapshot.GasTarget.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList();
        assertFalse(fields.contains("planet"));
        assertFalse(fields.contains("arrival"));
        assertFalse(
                java.util.Arrays.stream(FlightSnapshot.GasTarget.class.getMethods())
                        .anyMatch(m -> m.getName().equals("eta")));
        var marker = new SpaceNavigation.Marker(.5, .5, false, 0);
        assertTrue(new FlightSnapshot.Target(0, marker, 10, 1, 0, 1, 2, -1, "Amber").blocked());
        assertFalse(new FlightSnapshot.Target(0, marker, 10, 1, 0).blocked());
        for (String locale : List.of("en_us", "zh_cn")) {
            var json =
                    com.google.gson.JsonParser.parseString(
                                    java.nio.file.Files.readString(
                                            java.nio.file.Path.of(
                                                    "src/main/resources/assets/starfold/lang/"
                                                            + locale
                                                            + ".json")))
                            .getAsJsonObject();
            for (String suffix : List.of("nonlandable", "pressure", "depth", "remaining"))
                assertTrue(json.has("hud.starfold.instrument_gas_" + suffix));
        }
    }

    @Test
    void gasOcclusionReturnsTypedIdentityAndNeverCallsItsSurfaceShape() {
        var model =
                new GroundEphemeris.Model(
                        List.of(
                                new GroundEphemeris.Body(
                                        new GroundEphemeris.Fixed(new Point(0, 30000, 0)),
                                        512,
                                        true,
                                        -1,
                                        GroundEphemeris.Appearance.LIGHT),
                                new GroundEphemeris.Body(
                                        new GroundEphemeris.Fixed(new Point(0, 0, 30000)),
                                        0,
                                        false,
                                        0,
                                        GroundEphemeris.Appearance.SURFACE),
                                GroundEphemeris.Body.gas(
                                        new GroundEphemeris.Fixed(new Point(0, 0, 10000)),
                                        4096,
                                        GAS)));
        var surface = CelestialTarget.resolve(model, 1);
        var gas = CelestialTarget.resolve(model, 2);
        assertEquals(
                gas,
                CelestialNavigation.blocker(model, GroundEphemeris.ZERO, surface, 0).orElseThrow());
        assertTrue(CelestialNavigation.blocker(model, GroundEphemeris.ZERO, gas, 0).isEmpty());
        assertTrue(
                CelestialNavigation.blocker(model, new Point(20000, 0, 0), surface, 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> gas.requireSurfaceSlot(model));
        assertEquals(30000 - 512, CelestialNavigation.read(model, Space.Frame.IDENTITY,
                GroundEphemeris.ZERO, new CelestialTarget(0), 0, 0).distance(), 1e-9);
    }
}
