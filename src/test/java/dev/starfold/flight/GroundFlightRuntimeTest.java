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
    void boundedOrderedQueueConsumesGOnceAndRejectsReplayBurstAndSkippedSequence() {
        var q = new GroundFlightPackets.Queue();
        assertFalse(q.offer(input(2, false), 0));
        assertTrue(q.offer(input(1, true), 0));
        assertFalse(q.offer(input(1, true), 0));
        assertTrue(q.offer(input(2, false), 0));
        assertTrue(q.offer(input(3, false), 0));
        assertTrue(q.offer(input(4, false), 0));
        assertFalse(q.offer(input(5, false), 0));
        assertTrue(q.offer(input(5, false), 1));
        assertTrue(q.poll().toggle());
        for (int i = 0; i < 4; i++) assertFalse(q.poll().toggle());
        assertNull(q.poll());
        assertEquals(5, q.ack());
        for (int i = 6; i < 30; i++) assertTrue(q.offer(input(i, false), i));
        assertFalse(q.offer(input(30, false), 30));
    }

    @Test
    void serverWorldTickEntryLeaveAndSpinUseTheFlightAdapter() throws Exception {
        var server = Sources.text("PlanetPlayers");
        assertTrue(Sources.text("StarfoldServer").contains("GroundFlightServer.tick(level,saved)"));
        assertTrue(server.contains("GroundFlightServer.entry(player,level,clock)"));
        assertTrue(
                server.indexOf("GroundFlightServer.entry(player,level,clock)")
                        < server.indexOf("WorldSessionSave.get(level).remember(player)"));
        assertTrue(server.contains("GroundFlightServer.enter(player,clock,saved)"));
        assertTrue(server.contains("GroundFlightServer.detach(player)"));
        assertTrue(server.contains("GroundFlightServer.restart(player,clock)"));
        assertTrue(
                Sources.text("StarfoldServer")
                        .contains(
                                "public int leave(net.minecraft.server.level.ServerPlayer player){return PlanetPlayers.leave(player);}"));
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
        assertTrue(Sources.text("GroundFlightClient").contains("pending.size()>=24"));
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
