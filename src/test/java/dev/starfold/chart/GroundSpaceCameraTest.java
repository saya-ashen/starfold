package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.PlanetMotion;

import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

/** Offline camera ownership and lens regressions, not Minecraft/GPU acceptance. */
class GroundSpaceCameraTest {
    final GroundEphemeris.Model model =
            GroundEphemeris.spinning(dev.starfold.planet.PlanetSystem.model(), false, 0);

    void same(Quaterniond a, Quaterniond b) {
        assertEquals(1, Math.abs(a.dot(b)), 1e-10);
    }

    // Preserve the failed dev.2 policy as an explicit counterexample, never production code.
    Quaterniond dev2Assist(Quaterniond local, double weight) {
        var f = PlanetMotion.direction(local, 0, 0, 1);
        double safe =
                weight
                        * GroundGap.smooth(
                                Math.sin(Math.toRadians(5)),
                                Math.sin(Math.toRadians(15)),
                                Math.hypot(f.x(), f.z()));
        return safe <= 0
                ? new Quaterniond(local)
                : new Quaterniond(local).slerp(GroundView.upright(local), safe).normalize();
    }

    @Test
    void poleDeadZoneAloneDoesNotProtectTheRestOfTheMouseSweep() {
        for (double sign : new double[] {-1, 1}) {
            var a = new Quaterniond().rotateX(Math.toRadians(sign * 89.99));
            var b = new Quaterniond().rotateX(Math.toRadians(sign * 90.01));
            var oldA = new Quaterniond(a).slerp(GroundView.upright(a), .5).normalize();
            var oldB = new Quaterniond(b).slerp(GroundView.upright(b), .5).normalize();
            assertTrue(
                    Math.abs(oldA.dot(oldB)) < .8,
                    "Old pole roll discontinuity must be reproduced");
            same(a, dev2Assist(a, .5));
            same(b, dev2Assist(b, .5));
            assertTrue(Math.abs(dev2Assist(a, .5).dot(dev2Assist(b, .5))) > .999999);
        }
    }

    @Test
    void lensKeepsVerticalFovAndPhysicalPixelAspectAcrossWindowShapes() {
        for (double fov : new double[] {30, 70, 110})
            for (double aspect : new double[] {.75, 1, 16. / 9, 3.5}) {
                var projection =
                        new Matrix4f()
                                .perspective(
                                        (float) Math.toRadians(fov), (float) aspect, .05f, 100000);
                var lens = GroundSpaceOptics.lens(projection, 5000);
                assertEquals(1, lens.weight());
                assertEquals(lens.y(), lens.x() * aspect, 1e-6);
                double angle = Math.atan(1. / projection.m11());
                var ray = new Point(0, Math.sin(angle), -Math.cos(angle));
                assertEquals(1, GroundSpaceOptics.stereo(ray, lens.y()).y(), 1e-9);
                assertEquals(0, GroundSpaceOptics.lens(projection, GroundOrbit.START).weight());
                double last = 0;
                for (double h = GroundOrbit.START; h <= GroundOrbit.END; h += 16) {
                    double weight = GroundSpaceOptics.lens(projection, h).weight();
                    assertTrue(weight >= last);
                    last = weight;
                }
            }
    }

    @Test
    void sphereSilhouetteStaysCircularOffAxisAndHasNoCentreSingularity() {
        // A sphere silhouette is an angular circle on the ray sphere. Test its entire
        // tangent cone, not a billboard or an approximate small-object aspect formula.
        for (double off : new double[] {0, .000001, 15, 30, 45, 60, 80})
            for (double angular : new double[] {2, 8, 20}) {
                double theta = Math.toRadians(off), alpha = Math.toRadians(angular);
                var n = new Vector3d(Math.sin(theta), 0, -Math.cos(theta));
                var u = new Vector3d(Math.cos(theta), 0, Math.sin(theta));
                var v = new Vector3d(0, 1, 0);
                var pts = new ArrayList<Point>();
                for (int i = 0; i < 360; i++) {
                    double a = i * Math.PI / 180;
                    var ray =
                            new Vector3d(n)
                                    .mul(Math.cos(alpha))
                                    .fma(Math.sin(alpha) * Math.cos(a), u)
                                    .fma(Math.sin(alpha) * Math.sin(a), v);
                    pts.add(GroundSpaceOptics.stereo(new Point(ray.x, ray.y, ray.z), 1));
                }
                var left = pts.get(180);
                var right = pts.get(0);
                double cx = (left.x() + right.x()) * .5,
                        radius = Math.abs(right.x() - left.x()) * .5;
                for (var p : pts)
                    assertEquals(
                            radius,
                            Math.hypot(p.x() - cx, p.y()),
                            1e-9,
                            "off=" + off + " angular=" + angular);
            }
        var center = GroundSpaceOptics.stereo(new Point(0, 0, -100), 1);
        assertEquals(0, center.x());
        assertEquals(0, center.y());
    }
}
