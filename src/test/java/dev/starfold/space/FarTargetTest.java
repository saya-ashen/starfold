package dev.starfold.space;

import static dev.starfold.sky.SpaceTest.point;
import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetSystem;

import org.junit.jupiter.api.Test;

class FarTargetTest {
    private static final Point OFFSET = new Point(86.0 / 20, 0, 0), NOSE = new Point(0, 0, 1);

    @Test
    void brakeOpposesRelativeDriftAndStopsInTargetFrame() {
        var drift = new Point(2, 0, 0);
        var wanted = SpaceFlightMath.brake(drift, drift, new Point(1, 0, 0), OFFSET);
        point(OFFSET, wanted);
        point(new Point(2.1, 0, 0), SpaceSpeed.maneuver(drift, wanted, .1, dv -> dv));
        point(drift, SpaceSpeed.maneuver(drift, wanted, .1, dv -> 0));
    }

    @Test
    void referenceSwitchPreservesBrake() {
        var switchVelocity = new Point(-3, 1, 2);
        for (var push : new Point[] {NOSE, NOSE.scale(-1)}) {
            var before = new Point(2, 3, 41);
            var drift = before.add(push.scale(.12));
            var a = SpaceFlightMath.brake(before, drift, push, OFFSET).sub(OFFSET);
            var offset = OFFSET.sub(switchVelocity);
            var b = SpaceFlightMath.brake(before.sub(switchVelocity), drift.sub(switchVelocity),
                    push, offset).sub(offset);
            point(a, b);
        }
    }

    @Test
    void arrivalStillUsesNearestAfterTargetBrake() throws Exception {
        var drift = new Point(30, 0, 40);
        var nearestOffset = new Point(2, 3, 4);
        var relative = drift.add(nearestOffset);
        var body = dev.starfold.chart.GroundSolar.defaults().body(0);
        var arrived = SpaceFlightMath.arrival(body, drift, NOSE, relative, 1536);
        var result = SpaceFlightMath.near(body, arrived, NOSE, relative.add(arrived.sub(drift)), 1536);
        point(NOSE.scale(7.5), result.add(nearestOffset));
        assertTrue(Sources.text("SpaceServer").contains(
                "var relative=model.at(f.planet(),tick).velocity().add(drift).sub(model.at(nearest,tick).velocity())"));
    }

    @Test
    void cycleIncludesEveryKnownPlanetAndThenNone() {
        var home = PlanetSystem.model();
        var bodies = new java.util.ArrayList<>(home.bodies());
        for (int i = 0; i < 6; i++) {
            int parent = bodies.size();
            bodies.add(new GroundEphemeris.Body(new GroundEphemeris.Fixed(
                    home.origin().add(new Point(100000 + i * 10000, 0, 0))),
                    512, true, -1, GroundEphemeris.Appearance.LIGHT));
            bodies.add(new GroundEphemeris.Body(new GroundEphemeris.Orbit(parent,
                    home.orbit(0).axis(), home.orbit(0).radial(), home.orbit(0).radius(),
                    home.orbit(0).period(), 0, home.orbit(0).epoch(), home.spin(1)),
                    0, false, i + 2, GroundEphemeris.Appearance.SURFACE));
        }
        var model = new GroundEphemeris.Model(bodies);
        var ship = model.at(0, 0).center();
        var order = SpaceView.nearest(model, ship, 0, model.count());
        int locked = -1;
        for (int id : order) {
            locked = SpaceNavigation.next(model, ship, 0, locked);
            assertEquals(id, locked);
        }
        assertEquals(-1, SpaceNavigation.next(model, ship, 0, locked));
        assertEquals(order.getFirst().intValue(), SpaceNavigation.next(model, ship, 0, 9999));
        assertTrue(order.size() > 4);
    }

    @Test
    void farCandidateAndClientWiring() throws Exception {
        var candidates = new java.util.ArrayList<SpaceNavigation.Candidate>();
        for (int i = 0; i < 8; i++)
            candidates.add(new SpaceNavigation.Candidate(i,
                    new SpaceNavigation.Marker(.5, .5, false, 0), i == 7 ? 0 : 90));
        assertEquals(7, SpaceNavigation.choose(candidates, 800, 400));
        var source = Sources.text("SpaceNavigationClient");
        for (var text : new String[] {"GLFW_KEY_G", "event.register(nextKey)",
                "for(int id=0;id<model.count();id++)", "if(nearest.contains(id)||id==locked)",
                "SpaceView.nearest(model,eye,tick,4)", "velocity.length()*20,relativeTo,flight.deltaV()"})
            assertTrue(source.contains(text), text);
    }
}
