package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.TestArchiveResources;
import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.ArrivalState;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.ImageStream;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.ground.PacketMap;
import dev.starfold.planet.GroundDynamics;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.ship.SableFrames;
import dev.starfold.ship.SpacePackets;
import dev.starfold.sky.Space;
import dev.starfold.sky.SpaceTest;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/** Region lifecycle contracts do not boot a server or claim live Sable/chunk acceptance. */
class SpacePlayerTest {
    private static ClassNode type(String name) throws Exception {
        var node = new ClassNode();
        try (var in = TestArchiveResources.open("/dev/starfold/" + name + ".class")) {
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private static List<String> calls(String name, String method) throws Exception {
        var out = new ArrayList<String>();
        for (var m : type(name).methods)
            if (m.name.equals(method))
                for (var ins : m.instructions) if (ins instanceof MethodInsnNode c) out.add(c.name);
        return out;
    }

    private static List<String> expandedCalls(String name, String method, Set<String> helpers)
            throws Exception {
        var out = new ArrayList<String>();
        for (var call : calls(name, method)) {
            out.add(call);
            if (helpers.contains(call)) out.addAll(expandedCalls(name, call, helpers));
        }
        return out;
    }

    private static void before(List<String> calls, String guard, String dangerous) {
        assertTrue(calls.contains(guard), guard);
        assertTrue(calls.contains(dangerous), dangerous);
        assertTrue(calls.indexOf(guard) < calls.indexOf(dangerous), guard + " before " + dangerous);
    }

    @Test
    void frameWireIncludesReferenceAndEpochIncludingGone() {
        var frame =
                new Space.Frame(
                        new Quaterniond().rotationXYZ(.2, -.7, 1.1),
                        new Point(-1234567, 45004, -2123456),
                        1);
        for (var f : Arrays.asList(frame, null)) {
            var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                var packet = new SpacePackets.Frame(f, f == null ? 0 : 1234);
                SpacePackets.Frame.CODEC.encode(b, packet);
                assertEquals(f == null ? 1 : 132, b.readableBytes());
                assertEquals(packet, SpacePackets.Frame.CODEC.decode(b));
                assertEquals(0, b.readableBytes());
                assertEquals("starfold:native_space_v3_frame", packet.type().id().toString());
            } finally {
                b.release();
            }
        }
    }

    @Test
    void arrivalPhaseWireRoundTrips() {
        for (var phase : ArrivalState.Phase.values()) {
            var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                var packet =
                        new SpacePackets.Frame(
                                Space.Frame.IDENTITY,
                                20,
                                phase == ArrivalState.Phase.TURN_IN
                                        || phase == ArrivalState.Phase.ARRIVAL,
                                phase,
                                .5f,
                                phase == ArrivalState.Phase.NOTICE ? 4 : 0);
                SpacePackets.Frame.CODEC.encode(b, packet);
                assertEquals(packet, SpacePackets.Frame.CODEC.decode(b));
                assertEquals(0, b.readableBytes());
            } finally {
                b.release();
            }
        }
    }

    @Test
    void bothWireDecodeAndConstructionRejectEveryNonfiniteFieldAndNonunitRotation() {
        for (int index = 0; index < 7; index++)
            for (double bad :
                    new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                double[] a = {0, 0, 0, 1, -2000000, 45000, -2000000};
                a[index] = bad;
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new SpacePackets.Frame(
                                        new Space.Frame(
                                                new Quaterniond(a[0], a[1], a[2], a[3]),
                                                new Point(a[4], a[5], a[6]),
                                                0),
                                        0));
                var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                try {
                    b.writeBoolean(true);
                    for (double n : a) b.writeDouble(n);
                    b.writeInt(0);
                    for (int i = 0; i < 6; i++) b.writeDouble(0);
                    b.writeLong(0);
                    b.writeLong(0);
                    b.writeBoolean(false);
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> SpacePackets.Frame.CODEC.decode(b));
                } finally {
                    b.release();
                }
            }
        for (double w : new double[] {0, 2, .99}) {
            var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                b.writeBoolean(true);
                for (double n : new double[] {0, 0, 0, w, 0, 0, 0}) b.writeDouble(n);
                b.writeInt(0);
                for (int i = 0; i < 6; i++) b.writeDouble(0);
                b.writeLong(0);
                b.writeLong(0);
                b.writeBoolean(false);
                assertThrows(
                        IllegalArgumentException.class, () -> SpacePackets.Frame.CODEC.decode(b));
            } finally {
                b.release();
            }
        }
    }

    @Test
    void invalidReferenceAndEpochAreRejectedOnWire() {
        for (int planet : new int[] {-1, PlanetStorage.MAX_PLANETS}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new Space.Frame(new Quaterniond(), Space.ORIGIN, planet));
            var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                b.writeBoolean(true);
                for (double n : new double[] {0, 0, 0, 1, 0, 0, 0}) b.writeDouble(n);
                b.writeInt(planet);
                for (int i = 0; i < 6; i++) b.writeDouble(0);
                b.writeLong(0);
                b.writeLong(0);
                b.writeBoolean(false);
                assertThrows(
                        IllegalArgumentException.class, () -> SpacePackets.Frame.CODEC.decode(b));
            } finally {
                b.release();
            }
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new SpacePackets.Frame(Space.Frame.IDENTITY, -1));
    }

    @Test
    void receivedRotationAndShiftInvertAnActualCameraPoint() {
        var q = new Quaterniond().rotationXYZ(.7, .1, -1.2);
        var frame = new Space.Frame(q, new Point(Space.X, Space.Y, Space.Z), 1);
        var packet = new SpacePackets.Frame(frame, 1234);
        q.identity();
        packet.frame().rotation().identity();
        var eye = new Point(42.25, -86.9, 71.5);
        assertEquals(
                0,
                eye.sub(
                                packet.frame()
                                        .universe(
                                                frame.level(eye, SpaceTest.tilted(), 1234),
                                                SpaceTest.tilted(),
                                                1234))
                        .length(),
                1e-8);
    }

    @Test
    void regionStepPrecedesArrivalAndKeepsAllBodiesAndRidersTogether() throws Exception {
        before(calls("SableFrames", "cross"), "advanceSurroundings", "arrive");
        var step =
                expandedCalls(
                        "SpaceServer",
                        "step",
                        Set.of(
                                "spaceBodies",
                                "clearEmptyRegion",
                                "advanceLead",
                                "lockedPlanet",
                                "absorbLeadImpulse",
                                "subtractEntityImpulse",
                                "dragFrame",
                                "shiftRegion"));
        for (String name :
                new String[] {
                    "getAllSubLevels",
                    "isRemoved",
                    "holds",
                    "players",
                    "aboard",
                    "universe",
                    "nearest",
                    "nearer",
                    "toward",
                    "gain",
                    "dragged",
                    "getUniversalDrag",
                    "setDirty",
                    "addLinearAndAngularVelocity",
                    "getDeltaMovement",
                    "setDeltaMovement",
                    "send",
                    "anchor",
                    "shifted",
                    "motion",
                    "move"
                }) assertTrue(step.contains(name), name);
        before(step, "gain", "addLinearAndAngularVelocity");
        before(step, "gain", "setDeltaMovement");
        before(step, "setDeltaMovement", "dragged");
        before(step, "dragged", "toward");
        assertFalse(step.contains("velocityShift"));
        before(step, "anchor", "shifted");
        assertTrue(step.lastIndexOf("shifted") < step.lastIndexOf("move"));
        var source = Sources.text("SpaceServer");
        assertTrue(source.contains("player.hurtMarked=true"));
        assertTrue(source.contains("-v.x()*20,-v.y()*20,-v.z()*20"));
        assertTrue(source.contains("motion.spin()),true"));
        assertTrue(Sources.text("SableFrames").contains("tracked||keepRiderVelocity"));
        assertTrue(
                source.contains(
                        "body!=null&&bodies.contains(body)&&SableFrames.tracked(entity,body)"));
    }

    @Test
    void loginRestoresOnlyControlledNavigationAndOtherwiseCapturesVanilla() throws Exception {
        assertFalse(GroundFlightServer.restores(null));
        for (double h : new double[] {0, GroundDynamics.HANDOFF, 1315, 6000})
            for (boolean flying : new boolean[] {false, true}) {
                var state =
                        GroundDynamics.local(
                                900,
                                new PlanetShape.Pose(
                                        CubeSurfaceTopology.Face.TOP, new Point(1024, h, 1024)),
                                new Point(.1, .2, .3),
                                new Quaterniond(),
                                flying,
                                GroundDynamics.OFF);
                assertEquals(
                        flying && h > GroundDynamics.HANDOFF, GroundFlightServer.restores(state));
                if (h == 6000)
                    assertTrue(
                            GroundFlightServer.restores(
                                    GroundDynamics.detach(SpaceTest.tilted(), state)));
            }
        var login = calls("GroundFlightServer", "login");
        before(login, "restores", "entry");
        before(login, "entry", "enter");
        assertTrue(login.contains("site"));
        assertFalse(login.contains("apply"));
        var site = calls("GroundFlightServer", "site");
        assertTrue(site.contains("capture"));
        assertFalse(site.contains("apply"));
        assertFalse(site.contains("teleport"));
        assertTrue(calls("GroundFlightServer", "tick").contains("login"));
        assertTrue(
                Sources.text("GroundFlightServer")
                        .contains(
                                "if(restores(saved))enter(player,clock,entry(player,player.serverLevel(),clock));else site(player)"));
    }

    @Test
    void regionIdentityLeavesPacketDirectionsAndSablePoseExactlyAlone() {
        var frame = ObserverFrame.identity(0, 0);
        var at = new Point(Space.X + 12.5, Space.Y + 3, Space.Z - 5.5);
        for (var access : new PlanetAccess[] {PlanetAccess.SHARED, PlanetAccess.LEGACY}) {
            int turns = PacketMap.turns(access, frame, at.x(), at.y(), at.z());
            assertEquals(0, turns);
            assertEquals(at, frame.toClient(at));
            assertEquals(at, frame.toStorage(at));
            var pose =
                    new dev.ryanhcode.sable.companion.math.Pose3d(
                            new Vector3d(at.x(), at.y(), at.z()),
                            new Quaterniond().rotationXYZ(.4, .3, .2),
                            new Vector3d(3, 4, 5),
                            new Vector3d(1, 1, 1));
            assertSame(pose, SableFrames.pose(at, turns, pose));
            assertEquals(37, frame.yawToClient(37));
        }
    }

    @Test
    void captureEntryAndControlPathsGuardBeforeChartWork() throws Exception {
        for (var pair :
                new String[][] {
                    {"capture", "body"},
                    {"entry", "find"},
                    {"enter", "capture"},
                    {"restart", "setup"},
                    {"tick", "isSpectator"},
                    {"checkpoint", "capture"},
                    {"site", "capture"},
                    {"orbit", "view"},
                    {"guidance", "guidance"},
                    {"target", "get"}
                }) {
            var c = calls("GroundFlightServer", pair[0]);
            assertTrue(c.contains("in"), pair[0]);
            before(c, "in", pair[1]);
        }
        for (String method : new String[] {"session", "aloft", "controlled", "position"})
            assertTrue(calls("GroundFlightServer", method).contains("in"));
    }

    @Test
    void regionEndingNeverMovesOrCapturesAndDisconnectSavesWithoutRecapture() throws Exception {
        var c = calls("GroundFlightServer", "region");
        assertTrue(c.containsAll(List.of("remove", "revoke")));
        for (String forbidden :
                new String[] {
                    "capture",
                    "apply",
                    "teleport",
                    "teleportTo",
                    "setPos",
                    "setDeltaMovement",
                    "put"
                }) assertFalse(c.contains(forbidden), forbidden);
        before(calls("GroundFlightServer", "detach"), "checkpoint", "save");
        assertTrue(
                Sources.text("PlanetPlayers").contains("List.copyOf(server.overworld().players())"),
                "automatic home arrival only scans overworld, never region residents");
        before(calls("GroundFlightServer", "checkpoint"), "in", "capture");
    }

    @Test
    void carriedLoginRespawnAndFrameReplacementAllAnnouncePassiveFrame() throws Exception {
        var s = Sources.text("SpaceServer");
        for (String event :
                new String[] {
                    "PlayerLoggedInEvent", "PlayerRespawnEvent", "PlayerChangedDimensionEvent"
                }) assertTrue(s.contains(event));
        before(calls("SpaceServer", "sync"), "region", "announce");
        assertTrue(calls("SpaceServer", "sync").contains("send"));
        before(calls("SpaceServer", "entering"), "keeping", "send");
        var carry = calls("GroundSeams", "carryBody");
        before(carry, "resetPosition", "regionSync");
        assertFalse(carry.contains("teleport"));
        before(carry, "regionSync", "resited");
        assertTrue(carry.contains("move"));
        before(calls("ObserverFrames", "fresh"), "in", "storagePlanet");
        before(calls("ObserverFrames", "get"), "in", "identity");
        assertTrue(
                Sources.text("ObserverFrames")
                        .contains(
                                "Space.holds(target.x(),target.z())?ObserverFrame.identity(0,0)"));
    }

    @Test
    void chartViewRemoteCommandsAndDaylightAreExcludedBeforePlanetLookup() throws Exception {
        before(calls("GroundViewServer", "belongs"), "in", "planet");
        assertTrue(calls("GroundViewServer", "tick").contains("tickViewer"));
        before(calls("GroundViewServer", "tickViewer"), "in", "planet");
        before(calls("PlanetRemoteServer", "tick"), "in", "planet");
        for (String method : new String[] {"face", "status", "universe"})
            before(calls("PlanetPlayers", method), "in", "planet");
        before(calls("GroundDaylight", "elevation"), "holds", "get");
        assertTrue(calls("GroundDaylight", "dawn").contains("holds"));
        before(calls("GroundSeams", "image"), "in", "shape");
        assertTrue(
                Sources.text("GroundClient")
                        .contains("!active()||Space.holds(mc.player.getX(),mc.player.getZ())"));
    }

    @Test
    void regionChunkWindowIsSmallContainsThePlayerAndNeverAnyChart() {
        for (int radius : new int[] {2, 8, 16, 32})
            for (int x : new int[] {Space.X, Space.X - Space.REACH, Space.X + Space.REACH - 1}) {
                var center = new ChunkPos(x >> 4, Space.Z >> 4);
                var window = ImageStream.regionPositions(center, radius);
                assertTrue(window.contains(center.toLong()));
                assertTrue(window.size() <= (2 * radius + 3) * (2 * radius + 3));
                for (long key : window) {
                    var pos = new ChunkPos(key);
                    assertTrue(Space.holds(pos.getMinBlockX(), pos.getMinBlockZ()));
                    assertTrue(Space.holds(pos.getMaxBlockX(), pos.getMaxBlockZ()));
                    assertEquals(-1, PlanetStorage.find(pos.getMinBlockX(), pos.getMinBlockZ()));
                }
            }
    }

    @Test
    void ticketsLoadRealRegionColumnsAndExistingIdentitySenderDeliversThem() throws Exception {
        var tickets = Sources.text("GroundTickets");
        assertTrue(
                tickets.contains("if(Space.in(player)){region.addAll(ImageStream.regionPositions"));
        assertTrue(tickets.contains("addRegionTicket(SPACE,new ChunkPos(key),0,key)"));
        assertTrue(tickets.contains("removeRegionTicket(SPACE,new ChunkPos(key),0,key)"));
        var stream = Sources.text("ImageStream");
        assertTrue(
                stream.contains(
                        "Space.in(player)?regionPositions(center,radius):positions(center,radius)"));
        assertTrue(stream.contains("new ClientboundForgetLevelChunkPacket"));
        assertTrue(stream.contains("getChunkNow"));
        assertTrue(stream.contains("if(identity(columns))"));
        assertTrue(stream.contains("new ClientboundLevelChunkWithLightPacket(columns[0].chunk"));
        assertTrue(Sources.text("GroundChunkSenderMixin").contains("ImageStream.active(player)"));
        assertTrue(
                Sources.text("GroundChunkMapMixin").contains("GroundFlightServer.aloft(player)"));
    }

    @Test
    void gravityUsesOnlyTransientMultiplicativeModifierOnLivingEntities() throws Exception {
        var s = Sources.text("SpaceServer");
        for (String text :
                new String[] {
                    "LevelTickEvent.Pre",
                    "instanceof net.minecraft.world.entity.LivingEntity living",
                    "Attributes.GRAVITY",
                    "in(entity)&&!weighted(entity)",
                    "getTrackingSubLevel(entity)!=null",
                    "SableFrames.inside(body,entity)",
                    "space_weightless",
                    "-1,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL",
                    "addTransientModifier",
                    "removeModifier"
                }) assertTrue(s.contains(text), text);
        assertFalse(s.contains("setBaseValue"));
        assertFalse(s.contains("addPermanentModifier"));
        assertFalse(s.contains("setNoGravity"));
    }

    @Test
    void transientGravityIsZeroIdempotentNotSavedAndRestoresOtherModifiers() {
        var gravity =
                new net.minecraft.world.entity.ai.attributes.AttributeInstance(
                        net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY, h -> {});
        gravity.setBaseValue(.12);
        var other =
                new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                        net.minecraft.resources.ResourceLocation.parse("test:other_gravity"),
                        .04,
                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation
                                .ADD_VALUE);
        gravity.addPermanentModifier(other);
        double before = gravity.getValue();
        var saved = gravity.save();
        SpaceServer.weight(gravity, true);
        SpaceServer.weight(gravity, true);
        assertEquals(0, gravity.getValue());
        assertEquals(.12, gravity.getBaseValue());
        assertEquals(saved, gravity.save());
        assertEquals(2, gravity.getModifiers().size());
        SpaceServer.weight(gravity, false);
        assertEquals(before, gravity.getValue());
        assertEquals(1, gravity.getModifiers().size());
        assertTrue(gravity.hasModifier(other.id()));
        SpaceServer.weight(null, true);
    }

    @Test
    void regionLeaveCannotSendAPlayerHome() throws Exception {
        before(calls("PlanetPlayers", "leave"), "in", "detach");
        before(calls("PlanetPlayers", "leave"), "regionMessage", "restore");
    }

    @Test
    void clientAndProbeArePassiveAndClearOnLogoutAndLevelChanges() throws Exception {
        var s = Sources.text("SpaceClient");
        for (String text :
                new String[] {
                    "LoggingOut",
                    "level!=Minecraft.getInstance().level",
                    "GroundFlightClient.region()",
                    "frame.universe(new Point(levelCameraPosition.x,levelCameraPosition.y,levelCameraPosition.z),SpaceClientHost.get().ephemeris(),time())"
                }) assertTrue(s.contains(text));
        for (String forbidden :
                new String[] {"setPos", "teleport", "camera.rotation", "GroundClient.ready"})
            assertFalse(s.contains(forbidden));
        assertEquals(List.of("get", "departing", "reset"), calls("GroundFlightClient", "region"));
        var probe = Sources.text("DevProbe");
        for (String text :
                new String[] {
                    "case \"region\"",
                    "case \"chart\"",
                    " | space %.6f %.6f %.6f ref %d w %.6f",
                    "SpaceClient.universeEye(position)",
                    "case \"chart!=\"",
                    "case \"planet!=\""
                }) assertTrue(probe.contains(text));
    }
}
