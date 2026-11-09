package dev.starfold.space;

import dev.starfold.chart.GasPlayerExposure;
import dev.starfold.chart.GasPlayerExposureState.Participation;
import dev.starfold.chart.GasPlayerExposureValues;
import dev.starfold.chart.GasPressure;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

/** Personal server observation only; neither hull telemetry nor a client damage command. */
public record GasPlayerExposurePacket(
        long session,
        ResourceLocation dimension,
        long sequence,
        int age,
        Optional<Snapshot> snapshot)
        implements CustomPacketPayload {
    public static final int FRESH_TICKS = 40;

    public record Snapshot(
            GasPlayerExposure.Sample sample,
            GasPlayerExposureValues values,
            Participation participation) {
        public Snapshot {
            Objects.requireNonNull(sample);
            Objects.requireNonNull(values);
            Objects.requireNonNull(participation);
        }
    }

    public GasPlayerExposurePacket {
        if (session == 0 || dimension == null || sequence < 0
                || age < 0 || age >= FRESH_TICKS || snapshot == null
                || snapshot.isEmpty() && age != 0)
            throw new IllegalArgumentException("Personal gas packet");
    }

    /** Use the current passive-view session, actual player dimension and post-transition values. */
    public static GasPlayerExposurePacket sampled(
            long session, ResourceLocation dimension, long sequence,
            GasPlayerExposure.Sample sample, GasPlayerExposureValues values,
            Participation participation) {
        return new GasPlayerExposurePacket(
                session, dimension, sequence, 0,
                Optional.of(new Snapshot(sample, values, participation)));
    }

    /** Ordered display clear, not a reset of the player's persistent exposure values. */
    public static GasPlayerExposurePacket clear(
            long session, ResourceLocation dimension, long sequence) {
        return new GasPlayerExposurePacket(session, dimension, sequence, 0, Optional.empty());
    }

    public static final Type<GasPlayerExposurePacket> TYPE =
            new Type<>(ResourceLocation.parse("starfold:gas_player_exposure_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GasPlayerExposurePacket> CODEC =
            StreamCodec.of(
                    (b, p) -> {
                        b.writeLong(p.session);
                        b.writeResourceLocation(p.dimension);
                        b.writeVarLong(p.sequence);
                        b.writeVarInt(p.age);
                        b.writeBoolean(p.snapshot.isPresent());
                        if (p.snapshot.isEmpty()) return;
                        var s = p.snapshot.orElseThrow();
                        b.writeEnum(s.sample.condition());
                        b.writeBoolean(s.sample.reading().isPresent());
                        s.sample.reading().ifPresent(r -> {
                            b.writeVarInt(r.bodyIndex());
                            b.writeDouble(r.depth());
                            b.writeDouble(r.pressure());
                        });
                        b.writeVarInt(s.values.graceTicks());
                        b.writeVarInt(s.values.safeTicks());
                        b.writeVarInt(s.values.attemptTicks());
                        b.writeEnum(s.participation);
                    },
                    b -> {
                        long session = b.readLong();
                        var dimension = b.readResourceLocation();
                        long sequence = b.readVarLong();
                        int age = b.readVarInt();
                        Optional<Snapshot> snapshot = Optional.empty();
                        if (b.readBoolean()) {
                            var condition = b.readEnum(GasPlayerExposure.Condition.class);
                            Optional<GasPressure.Reading> reading = b.readBoolean()
                                    ? Optional.of(new GasPressure.Reading(
                                            b.readVarInt(), b.readDouble(), b.readDouble()))
                                    : Optional.empty();
                            var values = new GasPlayerExposureValues(
                                    b.readVarInt(), b.readVarInt(), b.readVarInt());
                            snapshot = Optional.of(new Snapshot(
                                    new GasPlayerExposure.Sample(condition, reading), values,
                                    b.readEnum(Participation.class)));
                        }
                        return new GasPlayerExposurePacket(
                                session, dimension, sequence, age, snapshot);
                    });

    @Override
    public Type<GasPlayerExposurePacket> type() {
        return TYPE;
    }
}
