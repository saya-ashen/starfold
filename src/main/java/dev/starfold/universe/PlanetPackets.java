package dev.starfold.universe;

import dev.starfold.worldgen.PlanetClimate;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetMinerals;
import dev.starfold.worldgen.PlanetPalette;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Certified generator identity, not a second material ledger. Unknown versions are refused. */
public final class PlanetPackets {
    public record Profile(
            long session,
            long seed,
            int version,
            PlanetGeneration generation,
            ResourceLocation biome)
            implements CustomPacketPayload {
        public Profile(long session, long seed, int version) {
            this(session, seed, version, PlanetGeneration.defaults(version));
        }

        public Profile(long session, long seed, int version, PlanetGeneration generation) {
            this(
                    session,
                    seed,
                    version,
                    generation,
                    ResourceLocation.withDefaultNamespace("plains"));
        }

        public static Profile of(long session, PlanetChunkGenerator generator) {
            return new Profile(
                    session,
                    generator.seed(),
                    generator.version(),
                    generator.generation(),
                    generator.settings().getBiome().unwrapKey().orElseThrow().location());
        }

        public Profile {
            if (session == 0
                    || !PlanetTerrain.supported(version)
                    || generation == null
                    || biome == null) throw new IllegalArgumentException("Native terrain profile");
            generation.validate(version);
        }

        public static final Type<Profile> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_planet_v5_profile"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Profile> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeLong(p.session);
                            b.writeLong(p.seed);
                            b.writeVarInt(p.version);
                            b.writeUtf(p.generation.type(), 32);
                            b.writeUtf(p.generation.algorithm(), 32);
                            b.writeVarInt(p.generation.algorithmVersion());
                            b.writeDouble(p.generation.relief());
                            b.writeDouble(p.generation.oreBias());
                            b.writeDouble(p.generation.caveWidth());
                            b.writeVarInt(p.generation.size());
                            b.writeVarInt(p.generation.depth());
                            b.writeUtf(p.generation.shell(), 32);
                            p.generation.palette().write(b);
                            p.generation.minerals().write(b);
                            p.generation.climate().write(b);
                            b.writeBoolean(p.generation.creatures());
                            b.writeBoolean(p.generation.features());
                            b.writeBoolean(p.generation.structures());
                            b.writeResourceLocation(p.biome);
                        },
                        b ->
                                new Profile(
                                        b.readLong(),
                                        b.readLong(),
                                        b.readVarInt(),
                                        new PlanetGeneration(
                                                b.readUtf(32),
                                                b.readUtf(32),
                                                b.readVarInt(),
                                                b.readDouble(),
                                                b.readDouble(),
                                                b.readDouble(),
                                                b.readVarInt(),
                                                b.readVarInt(),
                                                b.readUtf(32),
                                                PlanetPalette.read(b),
                                                PlanetMinerals.read(b),
                                                PlanetClimate.read(b),
                                                b.readBoolean(),
                                                b.readBoolean(),
                                                b.readBoolean()),
                                        b.readResourceLocation()));

        @Override
        public Type<Profile> type() {
            return TYPE;
        }
    }

    private PlanetPackets() {}
}
