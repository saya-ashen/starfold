package dev.starfold.flight;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundSolar;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.GroundTransit;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.geometry.UniverseMotion;
import dev.starfold.ground.GroundFlightPackets;
import dev.starfold.planet.GroundDynamics;
import dev.starfold.sky.GroundClock;

import net.minecraft.network.RegistryFriendlyByteBuf;

import org.joml.Quaterniond;
import org.junit.jupiter.api.*;

import java.nio.file.*;

/** Wire and live-binding contracts. Does not launch the Minecraft server or renderer. */
class GroundFlightRuntimeTest {
    @BeforeAll
    static void registry() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    GroundDynamics.State local(double h) {
        return GroundDynamics.local(
                900,
                new PlanetShape.Pose(Face.TOP, new Point(1024, h, 1024)),
                new Point(.1, .3, .05),
                new Quaterniond(),
                true,
                GroundDynamics.OFF);
    }

    @Test
    void terrainAndSavedColumnsSetFlightHandoffIndependentlyOfOrbitDisplay() throws Exception {
        assertEquals(208.9, GroundDynamics.HANDOFF, 1e-9);
        assertEquals(160, dev.starfold.chart.GroundOrbit.START);
        assertTrue(Sources.text("GroundFlightClient").contains(
                "if(!state.space()&&state.local().point().y()<=GroundDynamics.handoff(mask))return;"));
        var model = GroundSolar.defaults();
        try {
            for (int size : new int[] {416, 1024, 2048}) {
                dev.starfold.chart.PlanetShape.assign(0, new PlanetShape(size));
                for (int column : new int[] {128, 320}) {
                    var mask = new GroundSpace.Config(16, column);
                    double boundary = GroundDynamics.handoff(mask);
                    assertEquals(column == 128 ? 208.9 : 336.9, boundary, 1e-9);
                    var terrain = new GroundDynamics.Terrain() {
                        public boolean clear(PlanetShape.Pose pose) { return true; }
                        public double clearance(PlanetShape.Pose pose) { return pose.point().y(); }
                        public double handoff() { return GroundDynamics.handoff(mask); }
                    };
                    for (double h : new double[] {192.9, boundary, boundary + .01}) {
                        var state = GroundDynamics.local(900,
                                new PlanetShape.Pose(Face.TOP, new Point(size / 2.0, h, size / 2.0)),
                                new Point(0, 0, 0), new Quaterniond(), true, GroundDynamics.OFF);
                        boolean flight = h > boundary;
                        assertEquals(flight, GroundFlightServer.restores(state, mask));
                        var input = PlanetMotion.Input.idle(1,
                                GroundDynamics.orientation(model, state, state.tick()));
                        if (flight) {
                            assertDoesNotThrow(() -> GroundDynamics.step(model, terrain, state, input, false));
                            assertDoesNotThrow(() -> new GroundFlightPackets.Setup(42, model, mask, state, true));
                        } else {
                            assertThrows(IllegalStateException.class,
                                    () -> GroundDynamics.step(model, terrain, state, input, false));
                            assertThrows(IllegalArgumentException.class,
                                    () -> new GroundFlightPackets.Setup(42, model, mask, state, true));
                            assertDoesNotThrow(() -> new GroundFlightPackets.Setup(42, model, mask, state, false));
                        }
                    }
                }
            }
        } finally {
            PlanetShape.reset();
        }
    }

    @Test
    void farFromEveryPlanetAHeldVesselCruisesAndCanStillStopBeforeTheNext() {
        assertEquals(UniverseMotion.speed(500, true), GroundDynamics.cruise(500, true));
        assertEquals(UniverseMotion.speed(1024, true), GroundDynamics.cruise(1024, true));
        assertEquals(80, GroundDynamics.cruise(1600, true), 1e-9);
        assertEquals(GroundDynamics.CRUISE, GroundDynamics.cruise(60000, true));
        for (double h : new double[] {0, 300, 5000, 60000})
            assertEquals(UniverseMotion.speed(h, false), GroundDynamics.cruise(h, false));
        double v = GroundDynamics.CRUISE, h = 60000;
        while (h > 1100) {
            double want = GroundDynamics.cruise(h, true);
            v = v > want ? Math.max(want, v - GroundTransit.ACCEL) : want;
            h -= v;
            assertTrue(v <= GroundDynamics.cruise(h + v, true) + 1e-9);
        }
        assertTrue(v <= 70 && h > 1000, v + " " + h);
    }

    @Test
    void setupAndAuthoritativeStateCarryEpochMaskGuidanceAndOwnership() {
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var b = new RegistryFriendlyByteBuf(raw, net.minecraft.core.RegistryAccess.EMPTY);
            var model = GroundSolar.defaults();
            var s = GroundDynamics.detach(model, local(6000));
            var setup =
                    new GroundFlightPackets.Setup(
                            42, model, new GroundSpace.Config(16, 128), s, true);
            GroundFlightPackets.Setup.CODEC.encode(b, setup);
            assertEquals(setup, GroundFlightPackets.Setup.CODEC.decode(b));
            var state = new GroundFlightPackets.State(42, 9, s, true);
            GroundFlightPackets.State.CODEC.encode(b, state);
            assertEquals(state, GroundFlightPackets.State.CODEC.decode(b));
            assertEquals(0, b.readableBytes());
        } finally {
            raw.release();
        }
    }

    @Test
    void inputAndResyncWireCannotPlaceBlocksOrAddressInventory() {
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var b = new RegistryFriendlyByteBuf(raw, net.minecraft.core.RegistryAccess.EMPTY);
            var motion = new PlanetMotion.Input(1, 0, 0, 0, 1, 1, -1, .5f, true, true);
            var p = new GroundFlightPackets.Input(42, motion, true);
            GroundFlightPackets.Input.CODEC.encode(b, p);
            assertEquals(p, GroundFlightPackets.Input.CODEC.decode(b));
            var sync = new GroundFlightPackets.Sync(42);
            GroundFlightPackets.Sync.CODEC.encode(b, sync);
            assertEquals(sync, GroundFlightPackets.Sync.CODEC.decode(b));
            assertEquals(0, b.readableBytes());
            assertEquals(3, GroundFlightPackets.Input.class.getRecordComponents().length);
        } finally {
            raw.release();
        }
    }

    @Test
    void invalidOwnershipAndEmptySessionsAreRejected() {
        var model = GroundSolar.defaults();
        var space = GroundDynamics.detach(model, local(6000));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundFlightPackets.Setup(
                                0, model, GroundSpace.DEFAULT_MASK, space, true));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundFlightPackets.Setup(
                                42, model, GroundSpace.DEFAULT_MASK, space, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundFlightPackets.State(42, 0, local(100), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundFlightPackets.State(42, -1, space, true));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundFlightPackets.Input(
                                42, PlanetMotion.Input.idle(0, new Quaterniond()), false));
    }

    GroundFlightPackets.Input input(long n, boolean toggle) {
        return new GroundFlightPackets.Input(
                42, PlanetMotion.Input.idle(n, new Quaterniond()), toggle);
    }

    @Test
    void boundedOrderedQueueAcceptsStalledTickBurstsWithoutReplayOrSkippedSequence() {
        var q = new GroundFlightPackets.Queue();
        assertFalse(q.offer(input(2, false), 0));
        for (int i = 1; i <= GroundFlightPackets.Queue.CAPACITY; i++)
            assertTrue(q.offer(input(i, i == 1 || i == 12), 0));
        assertFalse(q.offer(input(1, true), 0));
        assertFalse(q.offer(input(25, false), 0));
        assertEquals(1, q.poll().motion().sequence());
        assertTrue(q.offer(input(25, false), 1));
        assertFalse(q.offer(input(26, false), 0));
        int toggles = 1;
        for (int i = 2; i <= 25; i++) {
            var next = q.poll();
            assertEquals(i, next.motion().sequence());
            if (next.toggle()) toggles++;
        }
        assertEquals(2, toggles);
        assertNull(q.poll());
        assertEquals(25, q.ack());
        assertFalse(q.offer(input(27, false), 2));
        assertTrue(q.offer(input(26, false), 2));
    }

    @Test
    void boundedPredictionMakesProgressAtTwoAndFiveServerTicksPerSecond() throws Exception {
        var field = GroundFlightClient.class.getDeclaredField("MAX_PENDING");
        field.setAccessible(true);
        int lead = field.getInt(null);
        assertEquals(6, lead);
        assertTrue(lead < GroundFlightPackets.Queue.CAPACITY);
        for (int period : new int[] {4, 10}) {
            var q = new GroundFlightPackets.Queue();
            long sequence = 0, serverTick = 0;
            int toggles = 0;
            for (int clientTick = 0; clientTick < 240; clientTick++) {
                if (sequence - q.ack() < lead) {
                    long next = sequence + 1;
                    assertTrue(q.offer(input(next, next == 8 || next == 17), serverTick));
                    sequence = next;
                }
                if (clientTick % period == 0) {
                    var next = q.poll();
                    assertNotNull(next);
                    if (next.toggle()) toggles++;
                    serverTick++;
                }
                assertTrue(sequence - q.ack() <= lead);
            }
            assertEquals(240 / period, q.ack());
            assertEquals(2, toggles);
            int remaining = 0;
            while (q.poll() != null) remaining++;
            assertTrue(remaining <= lead);
            assertEquals(sequence, q.ack());
            assertTrue(q.offer(input(sequence + 1, false), serverTick));
        }
    }

    @Test
    void serverWorldTickEntryLeaveAndSpinUseTheFlightAdapter() throws Exception {
        var server = Sources.text("PlanetPlayers");
        assertTrue(Sources.text("StarfoldServer").contains("GroundFlightServer.tick(level,saved)"));
        assertTrue(server.contains("GroundFlightServer.entry(player,level,clock)"));
        assertTrue(
                server.indexOf("arrival=arrival(player,level)")
                        < server.indexOf("WorldSessionSave.get(level).remember(player)"));
        assertTrue(server.contains("GroundFlightServer.enter(player,arrival.clock,arrival.saved)"));
        assertTrue(server.contains("GroundFlightServer.detach(player)"));
        assertTrue(server.contains("GroundFlightServer.restart(player,clock)"));
        // The installed leave adapter is checked in StarfoldHostBindingsTest.
        assertEquals(0, new FlightHost() {}.leave(null));
        var flight = Sources.text("GroundFlightServer");
        assertEquals(
                2,
                flight.split(java.util.regex.Pattern.quote("FlightHost.get().leave(player)"), -1)
                                .length
                        - 1);
        assertFalse(flight.contains("GroundServer.leave"));
        assertTrue(flight.contains("GroundNavigationSave.get"));
        assertTrue(flight.contains("new GroundTerrain"));
        assertFalse(flight.contains("GroundWorld"));
        assertTrue(flight.contains("getChunkSource().move(player)"));
    }

    @Test
    void registeredMixinSelectorsProtectOnlyTheVesselAndLeaveVanillaGround() throws Exception {
        var json =
                com.google.gson.JsonParser.parseString(
                                Files.readString(
                                        Path.of("src/main/resources/starfold.mixins.json")))
                        .getAsJsonObject();
        for (String n : new String[] {"GroundFlightTravelMixin", "GroundFlightNetworkMixin"})
            assertTrue(
                    json.getAsJsonArray("mixins").asList().stream()
                            .anyMatch(x -> x.getAsString().equals(n)));
        for (String n :
                new String[] {
                    "GroundFlightClientMovementMixin",
                    "GroundFlightCameraMixin",
                    "GroundFlightMouseMixin",
                    "GroundFlightRenderMixin"
                })
            assertTrue(
                    json.getAsJsonArray("client").asList().stream()
                            .anyMatch(x -> x.getAsString().equals(n)));
        assertTrue(
                Sources.text("GroundFlightTravelMixin")
                        .contains("GroundFlightServer.controlled(e)"));
        assertTrue(
                Sources.text("GroundFlightClientMovementMixin")
                        .contains("GroundFlightClient.controlled()"));
        assertTrue(Sources.text("GroundClient").contains("GroundFlightClient.initialize(modBus)"));
        assertTrue(Sources.text("GroundFlightClient").contains("if(pending.size()>=MAX_PENDING)return;"));
        assertTrue(Sources.text("GroundFlightClient").contains("OrbitAttitude.turn"));
    }

    @Test
    void nativeMaskMetadataRoundTripsWithoutReplacingOriginalSkyModel() {
        var saved = GroundClock.fresh(false);
        saved.mask = new GroundSpace.Config(24, 256);
        var tag = saved.save(new net.minecraft.nbt.CompoundTag(), null);
        var copy = GroundClock.load(tag, null);
        assertEquals(saved.mask, copy.mask);
        assertEquals(saved.clock.model, copy.clock.model);
        tag.remove("native_mask");
        assertEquals(GroundSpace.DEFAULT_MASK, GroundClock.load(tag, null).mask);
        tag.putInt("native_mask", 1);
        assertThrows(IllegalArgumentException.class, () -> GroundClock.load(tag, null));
    }
}
