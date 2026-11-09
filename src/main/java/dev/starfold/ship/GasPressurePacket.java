package dev.starfold.ship;

import dev.starfold.chart.GasPressure;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server-sampled hull estimate, never a damage request or navigation/flight authority. */
public record GasPressurePacket(
        long session,
        UUID hull,
        long sequence,
        int age,
        Status status,
        List<GasPressure.Reading> readings)
        implements CustomPacketPayload {
    public static final int FRESH_TICKS = 40;

    public enum Status {
        KNOWN,
        UNKNOWN,
        CLEAR
    }

    public GasPressurePacket {
        if (session == 0
                || hull == null
                || sequence < 0
                || age < 0
                || age >= FRESH_TICKS
                || status == null
                || readings == null
                || readings.size() > 2
                || status == Status.KNOWN && readings.isEmpty()
                || status != Status.KNOWN && (!readings.isEmpty() || age != 0))
            throw new IllegalArgumentException("Gas pressure packet");
        readings = List.copyOf(readings);
        if (readings.size() == 2 && readings.get(0).bodyIndex() == readings.get(1).bodyIndex())
            throw new IllegalArgumentException("Duplicate gas pressure reading");
    }

    public static final Type<GasPressurePacket> TYPE =
            new Type<>(ResourceLocation.parse("starfold:native_gas_v1_pressure"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GasPressurePacket> CODEC =
            StreamCodec.of(
                    (b, p) -> {
                        b.writeLong(p.session);
                        b.writeUUID(p.hull);
                        b.writeVarLong(p.sequence);
                        b.writeVarInt(p.age);
                        b.writeEnum(p.status);
                        b.writeVarInt(p.readings.size());
                        for (var r : p.readings) {
                            b.writeVarInt(r.bodyIndex());
                            b.writeDouble(r.depth());
                            b.writeDouble(r.pressure());
                        }
                    },
                    b -> {
                        long session = b.readLong();
                        UUID hull = b.readUUID();
                        long sequence = b.readVarLong();
                        int age = b.readVarInt();
                        var status = b.readEnum(Status.class);
                        int count = b.readVarInt();
                        if (count < 0 || count > 2)
                            throw new IllegalArgumentException("Gas pressure reading count");
                        var readings = new ArrayList<GasPressure.Reading>(count);
                        for (int i = 0; i < count; i++)
                            readings.add(
                                    new GasPressure.Reading(
                                            b.readVarInt(), b.readDouble(), b.readDouble()));
                        return new GasPressurePacket(
                                session, hull, sequence, age, status, readings);
                    });

    @Override
    public Type<GasPressurePacket> type() {
        return TYPE;
    }
}
