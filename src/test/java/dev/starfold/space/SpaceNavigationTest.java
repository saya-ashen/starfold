package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetShape;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.sky.GroundEphemeris;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import java.nio.file.*;

class SpaceNavigationTest {
    private static SpaceNavigation.Marker project(double x, double y, double z) {
        return SpaceNavigation.project(new Point(x, y, z), Math.PI / 2, 2, .05, .1);
    }

    @Test
    void frontCentreAndPerspective() {
        var a = project(0, 0, -10);
        assertFalse(a.edge());
        assertEquals(.5, a.x());
        assertEquals(.5, a.y());
        var b = project(10, 4, -10);
        assertFalse(b.edge());
        assertEquals(.75, b.x(), 1e-12);
        assertEquals(.3, b.y(), 1e-12);
    }

    @Test
    void allEdgesAndCornersStayInside() {
        for (double x : new double[] {-100, 0, 100})
            for (double y : new double[] {-100, 0, 100})
                for (double z : new double[] {-1, 0, 1}) {
                    var a = project(x, y, z);
                    assertTrue(Double.isFinite(a.x()) && Double.isFinite(a.y()));
                    assertTrue(a.x() >= .05 - 1e-12 && a.x() <= .95 + 1e-12);
                    assertTrue(a.y() >= .1 - 1e-12 && a.y() <= .9 + 1e-12);
                    if (a.edge())
                        assertTrue(
                                Math.abs(a.x() - .05) < 1e-12
                                        || Math.abs(a.x() - .95) < 1e-12
                                        || Math.abs(a.y() - .1) < 1e-12
                                        || Math.abs(a.y() - .9) < 1e-12);
                }
    }

    @Test
    void behindNeverReversesShortestTurn() {
        assertTrue(project(1, 0, 10).x() > .5);
        assertTrue(project(-1, 0, 10).x() < .5);
        assertTrue(project(0, 1, 10).y() < .5);
        assertTrue(project(0, -1, 10).y() > .5);
        assertEquals(.95, project(0, 0, 10).x(), 1e-12);
        assertTrue(project(0, 0, 10).edge());
    }

    @Test
    void cameraPlaneIsContinuousAndArrowUsesPixelAspect() {
        var front = project(1, 1, -1e-9);
        var back = project(1, 1, 1e-9);
        assertEquals(front, back);
        assertEquals(-Math.PI / 4, front.angle(), 1e-12);
    }

    @Test
    void fovAndAspectDetermineProjection() {
        var p = SpaceNavigation.project(new Point(1, 0, -10), Math.toRadians(60), 1, .05, .05);
        assertEquals(.5 + 1 / (20 * Math.tan(Math.PI / 6)), p.x(), 1e-12);
        assertTrue(p.x() > project(1, 0, -10).x());
    }

    @Test
    void closingHasCorrectSignUnitsAndIgnoresTangentialMotion() {
        var toward = new Point(10, 0, 0);
        assertEquals(40, SpaceNavigation.closing(toward, new Point(3, 999, 0), new Point(1, 0, 0)));
        assertEquals(-40, SpaceNavigation.closing(toward, new Point(-1, 0, 0), new Point(1, 0, 0)));
        assertEquals(0, SpaceNavigation.closing(toward, new Point(1, 5, 0), new Point(1, 0, 0)));
        assertEquals(
                0,
                SpaceNavigation.closing(
                        GroundEphemeris.ZERO, new Point(1, 0, 0), GroundEphemeris.ZERO));
    }

    @Test
    void referenceSpeedUsesUniverseDriftNotLevelMotion() {
        var frame =
                new Space.Frame(
                        new Quaterniond().rotationXYZ(.3, .7, 1),
                        Space.ORIGIN,
                        0,
                        GroundEphemeris.ZERO,
                        new Point(3, 4, 0),
                        900);
        assertEquals(100, SpaceNavigation.speed(frame));
    }

    @Test
    void angleIsLevelDownNotCameraAim() {
        assertEquals(0, SpaceNavigation.angle(new Point(0, -20, 0)));
        assertEquals(90, SpaceNavigation.angle(new Point(3, 0, 4)));
        assertEquals(180, SpaceNavigation.angle(new Point(0, 1, 0)));
        assertEquals(
                .9,
                SpaceNavigation.angle(
                        new Point(Math.sin(Math.toRadians(.9)), -Math.cos(Math.toRadians(.9)), 0)),
                1e-12);
        assertTrue(
                SpaceNavigation.angle(
                                new Point(
                                        Math.sin(Math.toRadians(1.1)),
                                        -Math.cos(Math.toRadians(1.1)),
                                        0))
                        > 1);
        assertEquals(0, SpaceNavigation.angle(GroundEphemeris.ZERO));
    }

    @Test
    void telemetryIncludesReferenceOrbitAndTargetCentreVelocity() {
        var model = PlanetSystem.model();
        double tick = 900;
        var frame =
                new Space.Frame(
                        new Quaterniond().rotationY(.7),
                        Space.ORIGIN,
                        0,
                        GroundEphemeris.ZERO,
                        new Point(2, 3, 4),
                        900);
        var eye = model.at(1, tick).center().add(new Point(PlanetShape.of(1).radius() + 500, 0, 0));
        var r = SpaceNavigation.read(model, frame, eye, 1, tick);
        assertEquals(500, r.distance(), 1e-8);
        assertEquals(
                -20
                        * (frame.drift().x()
                                + model.at(0, tick).velocity().x()
                                - model.at(1, tick).velocity().x()),
                r.closing(),
                1e-8);
        var changed = frame.toward(1, model, tick);
        assertEquals(
                r.closing(), SpaceNavigation.read(model, changed, eye, 1, tick).closing(), 1e-8);
    }

    @Test
    void interpolatedFrameAndLargeCoordinatesAgreeWithRenderer() {
        var model = PlanetSystem.model();
        var eye = new Point(1e10, 2e10, -3e10);
        var frame = new Space.Frame(new Quaterniond().rotationXYZ(.3, .8, .2), Space.ORIGIN, 0);
        var target = eye.add(new Point(10, 20, -30));
        assertEquals(
                frame.levelVector(new Point(10, 20, -30)), SpaceView.relative(frame, eye, target));
        var after = frame.turned(new Quaterniond().rotationX(.4), Space.ORIGIN);
        var blend = Space.Frame.interpolate(frame, after, Space.ORIGIN, .5, model, 900);
        assertEquals(
                0,
                SpaceView.relative(blend, eye, target)
                        .sub(blend.levelVector(target.sub(eye)))
                        .length(),
                1e-10);
    }

    @Test
    void sourceContractRestrictsLayerAndProbeToDisplayedRegionReadings() throws Exception {
        var s = Sources.text("SpaceNavigationClient");
        assertTrue(
                s.contains(
                        "SpaceClient.active()&&!GroundFlightClient.controlled()&&!mc.options.hideGui&&mc.screen==null"));
        assertTrue(s.contains("if(!chart&&!visible())return"));
        assertTrue(
                s.contains(
                        "if(mc.options.hideGui||mc.screen!=null||HelmClient.body()==null)return"));
        assertTrue(
                s.contains("FlightWidgets.view(g,HelmClient.viewLabel(),HelmClient.viewFlash())"));
        assertTrue(s.contains("if(snapshot==null||level!=mc.level)return"));
        assertTrue(s.contains("RegisterGuiLayersEvent"));
        assertTrue(s.contains("AFTER_LEVEL"));
        assertTrue(s.contains("context.projectionMatrix()"));
        assertTrue(s.contains("context.positionMatrix()"));
        assertTrue(s.contains("SpaceClient.frame()"));
        assertTrue(s.contains("SpaceClient.arriving()"));
        assertTrue(s.contains("SpaceView.nearest(model,eye,tick,4)"));
        assertTrue(s.contains("helm.logicalPose().position()"));
        assertFalse(s.contains("SpaceApproach.APPROACH"));
        assertTrue(
                s.indexOf("receipt=\" | instruments tier \"")
                        > s.indexOf("FlightWidgets.render(g,snapshot)"));
        assertTrue(s.contains("mc.level.getGameTime()-drawnTick<=2?receipt"));
        assertTrue(
                Sources.text("DevProbe")
                        .contains("space(q.position())+SpaceNavigationClient.probeReading()"));
        assertTrue(
                Sources.text("GroundClient").contains("SpaceNavigationClient.initialize(modBus)"));
    }

    @Test
    void bothLanguagesContainEveryHudKey() throws Exception {
        var s = Sources.text("SpaceNavigationClient");
        for (String language : new String[] {"en_us", "zh_cn"}) {
            var json =
                    com.google.gson.JsonParser.parseString(
                                    Files.readString(
                                            Path.of(
                                                    "src/main/resources/assets/starfold/lang/"
                                                            + language
                                                            + ".json")))
                            .getAsJsonObject();
            var matcher =
                    java.util.regex.Pattern.compile("\"(hud\\.starfold\\.space_[a-z_]+)\"")
                            .matcher(s);
            int count = 0;
            while (matcher.find()) {
                assertTrue(json.has(matcher.group(1)));
                count++;
            }
            assertEquals(1, count);
            for (String key :
                    new String[] {
                        "scope_required",
                        "free",
                        "arrival",
                        "chart",
                        "caption",
                        "boost",
                        "relative",
                        "closing",
                        "bearing",
                        "clearance"
                    }) assertTrue(json.has("hud.starfold.instrument_" + key));
        }
    }
}
