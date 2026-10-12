package dev.starfold.space;

import static dev.starfold.sky.SpaceTest.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ship.SpacePackets;
import dev.starfold.sky.Space;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class SpaceAttitudeTest {
    @Test
    void fortyTicksPitchUpBringsOldAboveAheadAtEveryYaw() {
        assertEquals(45, SpaceServer.PILOT_TURN);
        var model = tilted();
        var pivot = Space.ORIGIN.add(new Point(12, 34, 56));
        for (double yaw : new double[] {0, 90, 180, -90, 37}) {
            var old =
                    new Space.Frame(
                            new Quaterniond().rotationXYZ(.2, .3, .4),
                            Space.ORIGIN,
                            1,
                            new Point(7, 8, 9),
                            new Point(2, -3, 4),
                            1234);
            var frame = old;
            var lead = chart(pivot);
            var pose = lead.orientation();
            for (int i = 0; i < 40; i++) {
                var next =
                        frame.turned(
                                SpaceServer.attitude(
                                        frame, model, 1234, pivot, 2048, -100, true, yaw, 1, 0),
                                pivot);
                assertEquals(frame.drift(), next.drift());
                point(frame.universe(pivot, model, 1234), next.universe(pivot, model, 1234));
                frame = next;
            }
            var a = Math.toRadians(yaw);
            var forward = new Vector3d(-Math.sin(a), 0, Math.cos(a));
            var delta = frame.rotation().mul(old.rotation().conjugate());
            vector(forward, delta.transform(new Vector3d(0, 1, 0)));
            assertEquals(Math.PI / 2, delta.angle(), 1e-10);
            point(old.universe(pivot, model, 1234), frame.universe(pivot, model, 1234));
            assertEquals(pose, lead.orientation());
            assertEquals(pivot, lead.position());
            point(
                    old.universeVector(new Point(0, 1, 0)),
                    frame.universeVector(new Point(forward.x, forward.y, forward.z)));
        }
    }

    @Test
    void rollRightBringsOldRightUpAndLeavesTheNoseFixed() {
        var f = Space.Frame.IDENTITY;
        for (double yaw : new double[] {0, 90, -43}) {
            var q = new Quaterniond();
            for (int i = 0; i < 40; i++)
                q.premul(
                        SpaceServer.attitude(
                                f, tilted(), 0, Space.ORIGIN, 2500, 0, true, yaw, 0, 1));
            double a = Math.toRadians(yaw);
            var right = new Vector3d(-Math.cos(a), 0, -Math.sin(a));
            var forward = new Vector3d(-Math.sin(a), 0, Math.cos(a));
            vector(new Vector3d(0, 1, 0), q.transform(right));
            vector(forward, q.transform(new Vector3d(forward)));
        }
    }

    @Test
    void cruiseDoesNotAlignAndNearIgnoresPilotAndUnpoweredDoesNotTurn() {
        var f =
                new Space.Frame(
                        new Quaterniond().rotationZ(.5),
                        Space.ORIGIN.sub(new Point(0, 6000, 0)),
                        0);
        var model = tilted();
        var pivot = Space.ORIGIN;
        for (double height : new double[] {2048, 3000, 4096, 5000})
            assertEquals(
                    new Quaterniond(),
                    SpaceServer.attitude(f, model, 0, pivot, height, 0, true, 90, 0, 0));
        for (double height : new double[] {1536, 1835.999, 6000}) {
            var expected = Space.Frame.turn(f, model, 0, pivot, Math.toRadians(30) / 20);
            aim(expected, SpaceServer.attitude(f, model, 0, pivot, height, 400, true, 90, 1, -1));
            assertEquals(
                    new Quaterniond(),
                    SpaceServer.attitude(f, model, 0, pivot, height, 400, false, 90, 1, -1));
        }
        assertEquals(
                new Quaterniond(),
                SpaceServer.attitude(f, model, 0, pivot, 5000, 0, false, 90, 1, -1));
    }

    @Test
    void alignmentUsesStrictTimeAndHeightBoundaries() {
        assertEquals(12, SpaceServer.ALIGN_TIME);
        for (double closing : new double[] {-400, 0, 25}) {
            assertTrue(SpaceServer.align(Math.nextDown(Space.IN + 300), closing));
            assertFalse(SpaceServer.align(Space.IN + 300, closing));
            assertFalse(SpaceServer.align(Math.nextUp(Space.IN + 300), closing));
        }
        for (double closing : new double[] {50, 100, 400}) {
            double boundary = Space.IN + closing * 12;
            assertTrue(SpaceServer.align(Math.nextDown(boundary), closing));
            assertFalse(SpaceServer.align(boundary, closing));
            assertFalse(SpaceServer.align(Math.nextUp(boundary), closing));
        }
        assertFalse(SpaceServer.align(Space.OUT, -400));
        assertFalse(SpaceServer.align(3000, 0));
        assertFalse(SpaceServer.align(3000, -400));
        assertTrue(SpaceServer.align(6000, 400));
        assertTrue(SpaceServer.align(Space.IN, -400));
        assertFalse(SpaceServer.align(Space.IN + 300, 0));
        assertTrue(SpaceServer.align(Space.IN + 300, Math.nextUp(25.0)));
    }

    @Test
    void ratesAreBoundedAndExpiredWishesCannotRevive() {
        var f = Space.Frame.IDENTITY;
        var m = tilted();
        for (double p : new double[] {-20, -1, 0, 1, 20})
            for (double r : new double[] {-20, -1, 0, 1, 20})
                assertTrue(
                        SpaceServer.attitude(f, m, 0, Space.ORIGIN, 5000, 0, true, 90, p, r).angle()
                                <= Math.toRadians(45) / 20 + 1e-10);
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY})
            assertEquals(
                    new Quaterniond(),
                    SpaceServer.attitude(f, m, 0, Space.ORIGIN, 5000, 0, true, 0, invalid, 1));
        assertTrue(SpaceServer.fresh(100, 105));
        assertFalse(SpaceServer.fresh(100, 106));
        assertFalse(SpaceServer.fresh(100, 99));
    }

    @Test
    void inputIsHelmScopedValidatedAndNeverSaved() throws Exception {
        var server = Sources.text("SpaceServer");
        var client = Sources.text("SpaceAttitudeClient");
        for (String guard :
                new String[] {
                    "!Float.isFinite(packet.pitch())",
                    "!Float.isFinite(packet.roll())",
                    "!Space.in(player)",
                    "lead==null||!helm(player,lead)",
                    "HelmServer.pilot(player,body)",
                    "wish.body()!=lead",
                    "!fresh(wish.tick(),level.getGameTime())",
                    "Math.clamp(packet.pitch(),-1,1)",
                    "Math.clamp(packet.roll(),-1,1)"
                }) assertTrue(server.contains(guard), guard);
        assertTrue(client.contains("!SpaceClient.active()||HelmClient.body()==null"));
        assertTrue(client.contains("pitch!=0||roll!=0||sent"));
        assertFalse(
                server.substring(server.indexOf("@Override public CompoundTag save"))
                        .contains("wish"));
        for (String code :
                new String[] {"GLFW_KEY_UP", "GLFW_KEY_DOWN", "GLFW_KEY_LEFT", "GLFW_KEY_RIGHT"})
            assertTrue(client.contains(code));
        for (String language : new String[] {"en_us", "zh_cn"}) {
            var json =
                    com.google.gson.JsonParser.parseString(
                                    java.nio.file.Files.readString(
                                            java.nio.file.Path.of(
                                                    "src/main/resources/assets/starfold/lang/"
                                                            + language
                                                            + ".json")))
                            .getAsJsonObject();
            for (String key : new String[] {"pitch_up", "pitch_down", "roll_left", "roll_right"})
                assertTrue(json.has("key.starfold." + key));
        }
    }

    @Test
    void mouseAxesAndSignsPreserveTheLeadAtSeveralPilotYaws() {
        assertEquals(180, SpaceServer.MOUSE_TURN);
        var model = tilted();
        var pivot = Space.ORIGIN.add(new Point(12, 34, 56));
        for (double yaw : new double[] {0, 90, 180, -90, 37})
            for (boolean pitch : new boolean[] {false, true}) {
                var old =
                        new Space.Frame(
                                new Quaterniond().rotationXYZ(.2, .3, .4),
                                Space.ORIGIN,
                                1,
                                new Point(7, 8, 9),
                                new Point(2, -3, 4),
                                1234);
                var frame = old;
                var lead = chart(pivot);
                var pose = lead.orientation();
                double a = Math.toRadians(yaw);
                var forward = new Vector3d(-Math.sin(a), 0, Math.cos(a));
                var right = new Vector3d(-Math.cos(a), 0, -Math.sin(a));
                for (int i = 0; i < 20; i++) {
                    var next =
                            frame.turned(
                                    SpaceServer.attitude(
                                            frame,
                                            model,
                                            1234,
                                            pivot,
                                            2500,
                                            0,
                                            true,
                                            yaw,
                                            0,
                                            0,
                                            pitch ? 0 : 4.5,
                                            pitch ? -4.5 : 0),
                                    pivot);
                    assertEquals(frame.drift(), next.drift());
                    point(frame.universe(pivot, model, 1234), next.universe(pivot, model, 1234));
                    frame = next;
                }
                var delta = frame.rotation().mul(old.rotation().conjugate());
                assertEquals(Math.PI / 2, delta.angle(), 1e-10);
                vector(
                        pitch ? new Vector3d(0, 1, 0) : right,
                        delta.conjugate(new Quaterniond()).transform(new Vector3d(forward)));
                assertEquals(pivot, lead.position());
                assertEquals(pose, lead.orientation());
            }
    }

    @Test
    void mouseClampCombinedWishInvalidAndAlign() {
        var f =
                new Space.Frame(
                        new Quaterniond().rotationZ(.5),
                        Space.ORIGIN.sub(new Point(0, 6000, 0)),
                        0);
        var m = tilted();
        var pivot = Space.ORIGIN;
        for (double x : new double[] {-1000, 0, 1000})
            for (double y : new double[] {-1000, 0, 1000})
                assertTrue(
                        SpaceServer.attitude(f, m, 0, pivot, 2500, 0, true, 37, 0, 0, x, y).angle()
                                <= Math.toRadians(9) + 1e-10);
        assertEquals(
                Math.toRadians(6.75),
                SpaceServer.attitude(f, m, 0, pivot, 2500, 0, true, 37, 1, 0, 0, -4.5).angle(),
                1e-10);
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY})
            assertEquals(
                    new Quaterniond(),
                    SpaceServer.attitude(f, m, 0, pivot, 2500, 0, true, 0, 0, 0, invalid, 1));
        aim(
                SpaceServer.attitude(f, m, 0, pivot, 1700, 0, true, 90, 0, 0),
                SpaceServer.attitude(f, m, 0, pivot, 1700, 0, true, 90, 1, 1, 90, 90));
        assertEquals(
                new Quaterniond(),
                SpaceServer.attitude(f, m, 0, pivot, 2500, 0, false, 90, 1, 1, 90, 90));
    }

    @Test
    void mouseIsOneShotHelmValidatedAndUsesVanillaHook() throws Exception {
        var s = Sources.text("SpaceServer");
        for (String guard :
                new String[] {
                    "!Float.isFinite(packet.mouseYaw())",
                    "!Float.isFinite(packet.mousePitch())",
                    "lead==null||!helm(player,lead)",
                    "new Wish(lead,pitch,roll,0,0,wish.tick())"
                }) assertTrue(s.contains(guard), guard);
        var hook = Sources.text("HelmMouseMixin");
        assertTrue(hook.contains("@WrapOperation(method=\"turnPlayer\""));
        assertTrue(hook.contains("original.call(player,flight?0d:x,flight?0d:y)"));
        var client = Sources.text("SpaceAttitudeClient");
        for (String guard :
                new String[] {
                    "mc.options.getCameraType()==net.minecraft.client.CameraType.THIRD_PERSON_FRONT",
                    "OrbitFlightControls.freeLook()",
                    "!SpaceClient.arriving()",
                    "pointer.move(x*.15,-y*.15)",
                    "if(!mouseFlight()){recenter();return false;}",
                    "mc.player.setXRot(0)",
                    "mc.player.setYRot(lookYaw)",
                    "pointer=pointer.remaining(turn)"
                }) assertTrue(client.contains(guard), guard);
    }

    @Test
    void mouseAnglesRoundTrip() {
        var b =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            var p = new SpacePackets.Attitude(.5f, -1, 4.5f, -2.25f);
            SpacePackets.Attitude.CODEC.encode(b, p);
            assertEquals(p, SpacePackets.Attitude.CODEC.decode(b));
            assertEquals(0, b.readableBytes());
        } finally {
            b.release();
        }
    }

    @Test
    void ratesRoundTripWithoutAddingSavedState() {
        var b =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            var p = new SpacePackets.Attitude(.5f, -1);
            SpacePackets.Attitude.CODEC.encode(b, p);
            assertEquals(p, SpacePackets.Attitude.CODEC.decode(b));
            assertEquals(0, b.readableBytes());
        } finally {
            b.release();
        }
    }
}
