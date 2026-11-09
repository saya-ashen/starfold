package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class GasPressureTransportTest {
    private static final UUID HULL = new UUID(12, 34);
    private static final GasPressureCache.Identity FOCUS =
            new GasPressureCache.Identity(ResourceLocation.parse("starfold:system"), 17, HULL);

    private static GroundEphemeris.Model model() {
        var bodies = new ArrayList<>(GroundSolar.defaults().bodies());
        bodies.add(
                GroundEphemeris.Body.gas(
                        new GroundEphemeris.Fixed(new Point(40000, 0, 0)),
                        4096,
                        new GasGiant(1, 31, "Amber", 0x98653d, 0xead1a2, 256, 512)));
        return new GroundEphemeris.Model(bodies);
    }

    private static GasPressurePacket known(GroundEphemeris.Model model, long sequence, int age) {
        return new GasPressurePacket(
                17,
                HULL,
                sequence,
                age,
                GasPressurePacket.Status.KNOWN,
                List.of(new GasPressure.Reading(model.bodies().size() - 1, 128, .5)));
    }

    @Test
    void boundedPacketRoundTripsKnownUnknownAndClearWithTheirOrderingValues() {
        var model = model();
        for (var packet :
                List.of(
                        known(model, 18, 12),
                        new GasPressurePacket(
                                17, HULL, 19, 0, GasPressurePacket.Status.UNKNOWN, List.of()),
                        new GasPressurePacket(
                                17, HULL, 20, 0, GasPressurePacket.Status.CLEAR, List.of()))) {
            var raw = io.netty.buffer.Unpooled.buffer();
            try {
                var buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
                GasPressurePacket.CODEC.encode(buf, packet);
                assertEquals(packet, GasPressurePacket.CODEC.decode(buf));
                assertEquals(0, buf.readableBytes());
            } finally {
                raw.release();
            }
        }
    }

    @Test
    void staleOrReorderedPacketsNeverRefreshFreshnessOrResurrectExpiredPressure() {
        var model = model();
        var cache = new GasPressureCache();
        cache.focus(FOCUS);
        assertTrue(cache.receive(known(model, 10, 10), model, 100));
        assertTrue(cache.ambient(129).isPresent());
        assertFalse(cache.receive(known(model, 9, 0), model, 129));
        assertFalse(cache.receive(known(model, 10, 0), model, 129));
        assertNull(cache.current(130));
        assertFalse(cache.receive(known(model, 10, 0), model, 131));
        assertNull(cache.current(131));
        assertTrue(cache.receive(known(model, 11, 0), model, 132));
        assertTrue(cache.selected(model.bodies().size() - 1, 171).isPresent());
        assertTrue(cache.selected(model.bodies().size() - 1, 172).isEmpty());
    }

    @Test
    void wrongSessionHullAndUnknownOrSurfaceBodyAreRejectedBeforeSequenceMutation() {
        var model = model();
        var cache = new GasPressureCache();
        cache.focus(FOCUS);
        var good = known(model, 1, 0);
        assertFalse(
                cache.receive(
                        new GasPressurePacket(18, HULL, 1000, 0, good.status(), good.readings()),
                        model,
                        1));
        assertFalse(
                cache.receive(
                        new GasPressurePacket(
                                17, new UUID(0, 1), 1000, 0, good.status(), good.readings()),
                        model,
                        1));
        for (int index : new int[] {0, model.index(0), model.bodies().size()})
            assertFalse(
                    cache.receive(
                            new GasPressurePacket(
                                    17,
                                    HULL,
                                    1000,
                                    0,
                                    good.status(),
                                    List.of(new GasPressure.Reading(index, 128, .5))),
                            model,
                            1));
        assertTrue(cache.receive(good, model, 1));
        assertTrue(cache.ambient(1).isPresent());
        assertFalse(
                GasPressureCache.compatible(good, GroundSolar.defaults()),
                "replacing the model must not expose the old GAS index");
    }

    @Test
    void pressurePolicyMismatchCannotPoisonTheSequenceFence() {
        var model = model();
        var cache = new GasPressureCache();
        cache.focus(FOCUS);
        int index = model.bodies().size() - 1;
        assertFalse(
                cache.receive(
                        new GasPressurePacket(
                                17,
                                HULL,
                                999,
                                0,
                                GasPressurePacket.Status.KNOWN,
                                List.of(new GasPressure.Reading(index, 128, .1))),
                        model,
                        1));
        assertTrue(cache.receive(known(model, 1, 0), model, 1));
        assertEquals(.5, cache.ambient(1).orElseThrow().pressure());
    }

    @Test
    void unknownAndClearAreOrderedAndChangingAuthorityDiscardsOldHullData() {
        var model = model();
        var cache = new GasPressureCache();
        cache.focus(FOCUS);
        assertTrue(cache.receive(known(model, 1, 0), model, 1));
        assertTrue(
                cache.receive(
                        new GasPressurePacket(
                                17, HULL, 2, 0, GasPressurePacket.Status.UNKNOWN, List.of()),
                        model,
                        2));
        assertTrue(cache.ambient(2).isEmpty());
        assertFalse(cache.receive(known(model, 1, 0), model, 2));
        assertTrue(
                cache.receive(
                        new GasPressurePacket(
                                17, HULL, 3, 0, GasPressurePacket.Status.CLEAR, List.of()),
                        model,
                        3));
        assertTrue(cache.ambient(3).isEmpty());
        cache.focus(new GasPressureCache.Identity(FOCUS.dimension(), 18, HULL));
        assertNull(cache.current(4));
        assertFalse(cache.receive(known(model, 100, 0), model, 4));
        cache.focus(new GasPressureCache.Identity(FOCUS.dimension(), 17, new UUID(0, 99)));
        assertNull(cache.current(4));
        assertFalse(cache.receive(known(model, 100, 0), model, 4));
        cache.focus(FOCUS);
        assertTrue(cache.receive(known(model, 1, 0), model, 5));
        cache.focus(null);
        assertNull(cache.current(5));
        assertFalse(cache.receive(known(model, 2, 0), model, 5));
    }

    @Test
    void malformedCountsAndUnboundedOrAmbiguousSamplesAreRejected() {
        var model = model();
        var good = known(model, 0, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> new GasPressurePacket(0, HULL, 0, 0, good.status(), good.readings()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GasPressurePacket(17, HULL, -1, 0, good.status(), good.readings()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GasPressurePacket(17, HULL, 0, 40, good.status(), good.readings()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GasPressurePacket(17, HULL, 0, 0, good.status(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GasPressurePacket(
                                17, HULL, 0, 0, GasPressurePacket.Status.UNKNOWN, good.readings()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GasPressurePacket(
                                17,
                                HULL,
                                0,
                                0,
                                good.status(),
                                List.of(good.readings().getFirst(), good.readings().getFirst())));
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            buf.writeLong(17);
            buf.writeUUID(HULL);
            buf.writeVarLong(0);
            buf.writeVarInt(0);
            buf.writeEnum(GasPressurePacket.Status.KNOWN);
            buf.writeVarInt(Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> GasPressurePacket.CODEC.decode(buf));
        } finally {
            raw.release();
        }
    }
}
