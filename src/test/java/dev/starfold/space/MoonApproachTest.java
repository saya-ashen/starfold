package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.MoonSystemTest;
import dev.starfold.sky.Space;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

class MoonApproachTest {
    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        MoonSystemTest.boot();
    }

    @Test
    void sixHundredTickLinearForecastCanMissSeveralChunkColumns() throws Exception {
        double radius = 5000, period = 24000, angle = 2 * Math.PI * 600 / period;
        double error = radius * Math.hypot(Math.cos(angle) - 1, Math.sin(angle) - angle);
        assertEquals(61.6427608451, error, 1e-7);
        var model = MoonSystemTest.model();
        double largest = 0;
        for (int tick = 0; tick < 120000; tick += 100) {
            var start = model.at(2, tick);
            var linear = start.center().add(start.velocity().scale(600));
            largest = Math.max(largest, model.at(2, tick + 600).center().sub(linear).length());
        }
        System.out.println(
                "C4 moon 600-tick linear target error: own=" + error + " family max=" + largest);
        assertTrue(largest > 76 && largest < 77);
    }

    @Test
    void moonForecastMatchesInertialIntegrationAndGovernorArrives() throws Exception {
        var model = MoonSystemTest.model();
        int planet = 2;
        var shape = new PlanetShape(512);
        for (double tick : new double[] {0, 1234, 15000}) {
            var frame = model.at(planet, tick);
            var offset = new Point(0, shape.radius() + 6000, 0);
            var motion =
                    new Space.Motion(
                            frame.center().add(offset),
                            new Quaterniond(),
                            frame.velocity().add(new Point(0, -40, 0)),
                            new org.joml.Vector3d());
            var predicted = SpaceApproach.predict(model, planet, shape, motion, tick);
            var world = motion.position();
            var velocity = motion.velocity();
            int steps = 0;
            for (; steps < 600; steps++) {
                var target = model.at(planet, tick + steps);
                var relativePosition = world.sub(target.center());
                var relativeVelocity = velocity.sub(target.velocity());
                double height = relativePosition.length() - shape.radius();
                if (height <= model.body(planet).hullIn()
                        || relativeVelocity.dot(relativePosition) >= 0) break;
                var toward = relativePosition.scale(-1);
                var radial =
                        SpaceServer.arrival(
                                model.body(planet),
                                relativeVelocity,
                                toward,
                                relativeVelocity,
                                height);
                velocity =
                        SpaceServer.near(model.body(planet), radial, toward, radial, height)
                                .add(target.velocity());
                world = world.add(velocity);
            }
            assertTrue(steps > 0 && steps < 600, "arrival within the forecast horizon");
            var expected =
                    Space.in(
                            model,
                            planet,
                            shape,
                            new Space.Motion(world, motion.orientation(), velocity, motion.spin()),
                            tick + steps);
            assertEquals(expected.pose().face(), predicted.pose().face());
            assertEquals(0, expected.pose().point().sub(predicted.pose().point()).length(), 1e-7);
            double height =
                    world.sub(model.at(planet, tick + steps).center()).length() - shape.radius();
            assertTrue(height <= model.body(planet).hullIn());
            System.out.println(
                    "C4 moon governor tick=" + tick + " steps=" + steps + " height=" + height);
        }
    }
}
