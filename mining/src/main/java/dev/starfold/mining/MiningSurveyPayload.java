package dev.starfold.mining;

import dev.starfold.api.MiningMapApi;
import dev.starfold.api.MiningMapApi.Ore;
import dev.starfold.api.MiningMapApi.Survey;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Bounded S2C display snapshots. No C2S survey or owner-selection channel exists. */
record MiningSurveyPayload(List<Survey> surveys) implements CustomPacketPayload {
    static final Type<MiningSurveyPayload> TYPE =
            new Type<>(ResourceLocation.parse("starfold_mining:surveys"));
    static final StreamCodec<RegistryFriendlyByteBuf, MiningSurveyPayload> CODEC =
            StreamCodec.of(MiningSurveyPayload::write, MiningSurveyPayload::read);

    MiningSurveyPayload {
        surveys = List.copyOf(surveys);
        if (surveys.size() > MiningMapApi.MAX_SITES)
            throw new IllegalArgumentException("Survey capacity");
    }

    @Override
    public Type<MiningSurveyPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf b, MiningSurveyPayload packet) {
        b.writeVarInt(packet.surveys().size());
        for (var s : packet.surveys()) {
            b.writeUtf(s.dimension(), 256);
            b.writeVarInt(s.planetSlot());
            b.writeVarInt(s.body());
            b.writeVarInt(s.face());
            b.writeDouble(s.u());
            b.writeDouble(s.v());
            b.writeInt(s.x());
            b.writeInt(s.y());
            b.writeInt(s.z());
            b.writeLong(s.completedAt());
            b.writeVarInt(s.scannedDepth());
            b.writeVarInt(s.scannedBlocks());
            b.writeUtf(s.markerName(), 32);
            b.writeVarInt(s.ores().size());
            for (var ore : s.ores()) {
                b.writeUtf(ore.blockId(), 256);
                b.writeVarInt(ore.count());
                b.writeVarInt(ore.minDepth());
                b.writeVarInt(ore.maxDepth());
            }
        }
    }

    private static MiningSurveyPayload read(RegistryFriendlyByteBuf b) {
        int count = bounded(b.readVarInt(), MiningMapApi.MAX_SITES);
        var surveys = new ArrayList<Survey>(count);
        for (int i = 0; i < count; i++) {
            String dimension = b.readUtf(256);
            int slot = b.readVarInt(), body = b.readVarInt(), face = b.readVarInt();
            double u = b.readDouble(), v = b.readDouble();
            int x = b.readInt(), y = b.readInt(), z = b.readInt();
            long time = b.readLong();
            int depth = b.readVarInt(), scanned = b.readVarInt();
            String marker = b.readUtf(32);
            int oreCount = bounded(b.readVarInt(), MiningMapApi.MAX_ORES);
            var ores = new ArrayList<Ore>(oreCount);
            for (int j = 0; j < oreCount; j++)
                ores.add(new Ore(b.readUtf(256), b.readVarInt(), b.readVarInt(), b.readVarInt()));
            surveys.add(
                    new Survey(
                            dimension, slot, body, face, u, v, x, y, z, time, depth, scanned,
                            marker, ores));
        }
        return new MiningSurveyPayload(surveys);
    }

    private static int bounded(int value, int max) {
        if (value < 0 || value > max) throw new IllegalArgumentException("Survey payload capacity");
        return value;
    }
}
