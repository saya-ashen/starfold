package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;

import org.junit.jupiter.api.Test;

class HullLandingTest {
    @Test
    void fluidStopsDownwardMotionWithoutLifting() {
        for (double h : new double[] {-100, -.01, 0})
            for (double speed : new double[] {-100, -2, 0, 1, 2, 100}) {
                assertEquals(Math.min(0, speed), HullLanding.descent(h, speed, true));
                assertEquals(Math.min(4, speed), HullLanding.descent(h, speed, false));
            }
        for (double h : new double[] {.01, 20, 400, Double.NaN})
            assertEquals(HullLanding.descent(h, 100), HullLanding.descent(h, 100, true));
    }

    @Test
    void highestSurfaceCarriesItsMaterial() {
        assertEquals(
                new HullLanding.Ground(-1, true),
                HullLanding.ground(0, 19, 0, 10, 10, (x, z) -> new HullLanding.Surface(20, true)));
        assertEquals(
                new HullLanding.Ground(-1, false),
                HullLanding.ground(
                        0, 19, 0, 10, 10, (x, z) -> new HullLanding.Surface(20, x != 5 || z != 5)));
        assertEquals(
                new HullLanding.Ground(1, true),
                HullLanding.ground(
                        0,
                        21,
                        0,
                        10,
                        10,
                        (x, z) -> new HullLanding.Surface(x == 5 ? 20 : 18, x == 5)));
        assertTrue(
                Double.isNaN(
                        HullLanding.ground(
                                        0,
                                        20,
                                        0,
                                        10,
                                        10,
                                        (x, z) -> new HullLanding.Surface(Double.NaN, false))
                                .clearance()));
    }

    @Test
    void fluidSubstepCannotCreepOrLiftASunkenStart() {
        assertEquals(100, HullLanding.supportedY(100, 99.9999));
        assertEquals(101, HullLanding.supportedY(100, 101));
        double y = 100;
        for (int i = 0; i < 100000; i++) y = HullLanding.supportedY(y, y - .00002);
        assertEquals(100, y);
    }

    @Test
    void heldDescentRemainsAtFluidSurface() {
        double height = 5;
        for (int i = 0; i < 1200; i++) {
            double down = HullLanding.descent(height, 100, true);
            height -= down / 40;
        }
        assertTrue(height <= 0 && height > -.11);
    }

    @Test
    void exactLandingLimits() {
        assertEquals(4, HullLanding.descent(0, 100));
        assertEquals(5.5, HullLanding.descent(2, 100));
        assertEquals(19, HullLanding.descent(20, 100));
        assertEquals(49, HullLanding.descent(60, 100));
        assertEquals(154, HullLanding.descent(200, 1000));
        assertEquals(304, HullLanding.descent(400, 1000));
        assertEquals(4, HullLanding.descent(-10, 100));
    }

    @Test
    void neverAcceleratesOrTouchesRisingMotion() {
        for (double h : new double[] {-100, 0, 1, 20, 400, 2000, Double.NaN})
            for (double speed : new double[] {-100, -2, 0, 1, 2, 10, 53, 1000}) {
                double result = HullLanding.descent(h, speed);
                assertTrue(result <= speed);
                if (speed <= 2) assertEquals(speed, result);
            }
    }

    @Test
    void monotoneInClearance() {
        double previous = 0;
        for (int h = 0; h < 3000; h++) {
            double speed = HullLanding.descent(h, 800);
            assertTrue(speed >= previous);
            previous = speed;
        }
    }

    @Test
    void unknownGroundDoesNothing() {
        for (double h :
                new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertEquals(53, HullLanding.descent(h, 53));
    }

    @Test
    void ninePointsUseHighestKnownSurface() {
        var points = new java.util.HashSet<String>();
        double h =
                HullLanding.clearance(
                        0,
                        40,
                        0,
                        10,
                        10,
                        (x, z) -> {
                            points.add(x + "," + z);
                            return x == 9 && z == 4 ? 30 : x == 0 ? Double.NaN : 20;
                        });
        assertEquals(20, h);
        assertEquals(9, points.size());
        assertTrue(points.contains("9,5"));
        assertEquals(
                10, HullLanding.clearance(0, 40, 0, 10, 10, (x, z) -> x == 9 && z == 5 ? 30 : 20));
    }

    @Test
    void unknownFootprintAndNegativeCoordinates() {
        assertTrue(Double.isNaN(HullLanding.clearance(-10, 40, -10, 0, 0, (x, z) -> Double.NaN)));
        assertEquals(
                8,
                HullLanding.clearance(
                        -10,
                        40,
                        -10,
                        0,
                        0,
                        (x, z) -> {
                            assertTrue(x < 0 && z < 0);
                            return 32;
                        }));
    }

    @Test
    void runtimeGuardsExcludeRegionButNeverRequireAPilotForLandingProtection() throws Exception {
        var source = Sources.text("SableFrames");
        var land =
                source.substring(
                        source.indexOf("private static void land("),
                        source.indexOf("static Vector3d drift("));
        assertTrue(land.contains("Space.holds"));
        assertTrue(land.contains("Thrusters.steadied"));
        assertTrue(land.contains(".inChart("));
        assertFalse(land.substring(0, land.indexOf("var box=")).contains("Helm"));
        assertTrue(land.contains("if(!ground.fluid()||clearance>0)return;"));
        assertFalse(land.contains("descentSpeed"));
        assertTrue(land.contains("new Vector3d(0,-allowed-velocity.y,0)"));
        assertTrue(
                source.contains("land(level,container,body,false,mass>0?impulses.pre().y/mass:0)"));
        assertTrue(source.contains("damp(level,container,body);land(level,container,body,true)"));
    }

    @Test
    void clientDoesNotUseTheAirReadinessGateAndHidesWithF1() throws Exception {
        var source = Sources.text("HullLandingClient");
        assertTrue(source.contains("HelmClient.body()"));
        assertTrue(source.contains("ShipClientHost.get().movingSurroundings()"));
        assertTrue(source.contains(".inChart("));
        assertFalse(source.contains(".ready()"));
        assertTrue(source.contains("!ShipClientHost.get().chartActive()"));
        assertTrue(
                Sources.text("StarfoldClient")
                        .contains("boolean chartActive(){return GroundClient.active();}"));
        assertFalse(source.contains("drawString"));
        var client = Sources.text("SpaceNavigationClient");
        assertTrue(client.contains("mc.options.hideGui||mc.screen!=null"));
        assertTrue(client.contains("lift!=null?lift.limit():Double.NaN"));
    }

    @Test
    void translationsUseInstrumentCaptionsNotProvisionalLines() throws Exception {
        for (String lang : new String[] {"en_us", "zh_cn"}) {
            var json =
                    com.google.gson.JsonParser.parseString(
                                    java.nio.file.Files.readString(
                                            java.nio.file.Path.of(
                                                    "src/main/resources/assets/starfold/lang/"
                                                            + lang
                                                            + ".json")))
                            .getAsJsonObject();
            assertFalse(json.has("hud.starfold.chart_landing"));
            assertFalse(json.has("hud.starfold.marker_surface"));
            for (String key :
                    new String[] {
                        "vertical",
                        "clearance",
                        "entry_height",
                        "entry",
                        "alignment",
                        "radial",
                        "here",
                        "tilt",
                        "drift"
                    }) assertTrue(json.has("hud.starfold.instrument_" + key));
        }
    }

    @Test
    void continuousDownwardPushCannotAccumulatePastTheLimit() {
        double speed = 0, h = 600;
        for (int tick = 0; tick < 2000 && h > 0; tick++) {
            speed = HullLanding.descent(h, speed + 2);
            assertTrue(speed <= 4 + h * .75);
            h -= speed / 20;
        }
        assertTrue(h <= 0);
        assertTrue(speed < 4.2);
    }
}
