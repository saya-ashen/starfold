package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GasPlayerExposure;
import dev.starfold.chart.GasPlayerExposureState.Participation;
import dev.starfold.chart.GasPlayerExposureValues;
import dev.starfold.chart.GasPressure;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

class GasPlayerExposurePacketTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.parse("starfold:system");

    private static GasPlayerExposurePacket packet(GasPlayerExposure.Sample sample) {
        return GasPlayerExposurePacket.sampled(17, DIMENSION, 99, sample,
                new GasPlayerExposureValues(37, 0, 0), Participation.ACTIVE);
    }

    @Test
    void codecRoundTripsPersonalSafeDangerUnknownAndClearWithoutAnyHullIdentity() {
        var packets = List.of(
                packet(GasPlayerExposure.confirmedSafe()),
                packet(new GasPlayerExposure.Sample(GasPlayerExposure.Condition.SAFE,
                        Optional.of(new GasPressure.Reading(3, 128, .5)))),
                packet(new GasPlayerExposure.Sample(GasPlayerExposure.Condition.DANGER,
                        Optional.of(new GasPressure.Reading(3, 512, 1)))),
                packet(GasPlayerExposure.unknown()),
                GasPlayerExposurePacket.clear(17, DIMENSION, 100));
        for (var original : packets) {
            var raw = io.netty.buffer.Unpooled.buffer();
            try {
                var buffer = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
                GasPlayerExposurePacket.CODEC.encode(buffer, original);
                assertEquals(original, GasPlayerExposurePacket.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                raw.release();
            }
        }
        assertNotEquals(dev.starfold.ship.GasPressurePacket.TYPE.id(),
                GasPlayerExposurePacket.TYPE.id());
    }

    @Test
    void codecRetainsAgeModeAndBoundedPostTransitionTimers() {
        for (var participation : Participation.values()) {
            var original = new GasPlayerExposurePacket(17, DIMENSION, Long.MAX_VALUE, 39,
                    Optional.of(new GasPlayerExposurePacket.Snapshot(
                            GasPlayerExposure.unknown(),
                            new GasPlayerExposureValues(0, 99, 20), participation)));
            var raw = io.netty.buffer.Unpooled.buffer();
            try {
                var buffer = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
                GasPlayerExposurePacket.CODEC.encode(buffer, original);
                assertEquals(original, GasPlayerExposurePacket.CODEC.decode(buffer));
            } finally {
                raw.release();
            }
        }
    }

    @Test
    void malformedIdentityAgeClearAndSampleCannotBecomeValidPackets() {
        var snapshot = packet(GasPlayerExposure.unknown()).snapshot();
        assertThrows(IllegalArgumentException.class,
                () -> new GasPlayerExposurePacket(0, DIMENSION, 0, 0, snapshot));
        assertThrows(IllegalArgumentException.class,
                () -> new GasPlayerExposurePacket(17, null, 0, 0, snapshot));
        assertThrows(IllegalArgumentException.class,
                () -> new GasPlayerExposurePacket(17, DIMENSION, -1, 0, snapshot));
        for (int age : new int[] {-1, 40, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,
                    () -> new GasPlayerExposurePacket(17, DIMENSION, 0, age, snapshot));
        assertThrows(IllegalArgumentException.class,
                () -> new GasPlayerExposurePacket(17, DIMENSION, 0, 1, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new GasPlayerExposure.Sample(GasPlayerExposure.Condition.DANGER,
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new GasPlayerExposure.Sample(GasPlayerExposure.Condition.UNKNOWN,
                        Optional.of(new GasPressure.Reading(3, 0, 0))));
    }

    @Test
    void decoderRejectsMalformedTimersEnumsAndNonfiniteReadings() {
        for (int fault = 0; fault < 6; fault++) {
            var raw = io.netty.buffer.Unpooled.buffer();
            try {
                var b = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
                b.writeLong(17);
                b.writeResourceLocation(DIMENSION);
                b.writeVarLong(0);
                b.writeVarInt(0);
                b.writeBoolean(true);
                b.writeVarInt(fault == 0 ? 999 : GasPlayerExposure.Condition.SAFE.ordinal());
                b.writeBoolean(true);
                b.writeVarInt(3);
                b.writeDouble(fault == 1 ? Double.NaN : 128);
                b.writeDouble(fault == 2 ? Double.POSITIVE_INFINITY : .5);
                b.writeVarInt(fault == 3 ? 101 : 0);
                b.writeVarInt(0);
                b.writeVarInt(fault == 4 ? 21 : 0);
                b.writeVarInt(fault == 5 ? 999 : Participation.ACTIVE.ordinal());
                assertThrows(RuntimeException.class, () -> GasPlayerExposurePacket.CODEC.decode(b));
            } finally {
                raw.release();
            }
        }
    }
}
