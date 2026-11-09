package dev.starfold.universe;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.starfold.worldgen.*;
import dev.starfold.worldgen.plan.PlanetPlan;
import dev.starfold.worldgen.surface.SurfacePolicy;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
/**
 * Fixed generation identity and description; existing bounded plan transfers carry terrain and structures.
 */
public final class PlanetPackets {
    public record Profile(long session, long seed, int version, PlanetGeneration generation,
        ResourceLocation biome, Optional<SurfacePolicy> surface, Optional<PlanetPlan> plan,
        Optional<PlanetPlanSync.Reference> planReference) implements CustomPacketPayload {
        public Profile(long session, long seed, int version) {
            this(session, seed, version, PlanetGeneration.defaults(version));
        }
        public Profile(long session, long seed, int version, PlanetGeneration generation) {
            this(session, seed, version, generation, ResourceLocation.withDefaultNamespace("plains"));
        }
        public Profile(
            long session, long seed, int version, PlanetGeneration generation, ResourceLocation biome) {
            this(session, seed, version, generation, biome, Optional.empty(), Optional.empty(),
                Optional.empty());
        }
        public Profile(long session, long seed, int version, PlanetGeneration generation,
            ResourceLocation biome, Optional<SurfacePolicy> surface, Optional<PlanetPlan> plan) {
            this(session, seed, version, generation, biome, surface, plan, Optional.empty());
        }
        public Profile {
            if (session == 0 || !PlanetTerrain.supported(version) || generation == null || biome == null
                || surface == null || plan == null || planReference == null
                || plan.isPresent() && planReference.isPresent())
                throw new IllegalArgumentException("Terrain profile");
            generation.validate(version);
            if (plan.isPresent()
                && (plan.get().size() != generation.size() || plan.get().terrain().seed() != seed
                    || !plan.get().terrain().recipe().equals(generation.recipe())))
                throw new IllegalArgumentException("Plan profile identity");
            plan.ifPresent(value -> value.validateGeneration(generation.floor()));
        }
        public Profile withPlan(Optional<PlanetPlan> value, Optional<PlanetPlanSync.Reference> reference) {
            return new Profile(session, seed, version, generation, biome, surface, value, reference);
        }
        public PlanetTerrain terrain() {
            var t = new PlanetTerrain(seed, version, generation);
            plan.ifPresent(p -> {
                t.initializeTerrain(p.terrain());
                t.initializePlan(() -> p);
            });
            surface.ifPresent(p -> t.initializeSurface(p, biome));
            return t;
        }
        public boolean sameTerrain(Profile p) {
            return session == p.session && seed == p.seed && version == p.version
                && generation.equals(p.generation) && biome.equals(p.biome) && surface.equals(p.surface)
                && plan.map(PlanetPlan::identity).equals(p.plan.map(PlanetPlan::identity));
        }
        public boolean surfaceCompatible() {
            return generation.surfaceVersion() == PlanetGeneration.SURFACE_VERSION
                && (!generation.features() || surface.isPresent()) && plan.isPresent();
        }
        public boolean biomesKnown(java.util.function.Predicate<ResourceLocation> registry) {
            return registry.test(biome) && generation.recipe().biomeIds().stream().allMatch(registry);
        }
        public static Optional<Profile> whenReady(long session, PlanetChunkGenerator generator) {
            return generator.planReady() ? Optional.of(of(session, generator)) : Optional.empty();
        }
        public static Profile of(long session, PlanetChunkGenerator generator) {
            return new Profile(session, generator.seed(), generator.version(), generator.generation(),
                generator.settings().getBiome().unwrapKey().orElseThrow().location(),
                Optional.ofNullable(generator.terrain().scatter()).map(s -> s.policy()),
                Optional.of(generator.terrain().plan()));
        }
        public static final Type<Profile> TYPE =
            new Type<>(ResourceLocation.parse("starfold:native_planet_v17_profile"));
        public static final int PACKET_LIMIT = 1_000_000;
        public static final StreamCodec<RegistryFriendlyByteBuf, Profile> CODEC = StreamCodec.of(
            (b, p)
                -> {
                int start = b.writerIndex();
                b.writeLong(p.session);
                b.writeLong(p.seed);
                b.writeVarInt(p.version);
                b.writeUtf(PlanetGeneration.CODEC.encodeStart(JsonOps.INSTANCE, p.generation)
                               .getOrThrow()
                               .toString(),
                    65536);
                b.writeResourceLocation(p.biome);
                b.writeBoolean(p.surface.isPresent());
                p.surface.ifPresent(s -> s.write(b));
                b.writeBoolean(p.plan.isPresent());
                p.plan.ifPresent(s -> s.writePacket(b));
                b.writeBoolean(p.planReference.isPresent());
                p.planReference.ifPresent(r -> {
                    b.writeUtf(r.hash(), 64);
                    b.writeVarInt(r.bytes());
                });
                if (b.writerIndex() - start > PACKET_LIMIT)
                    throw new IllegalArgumentException("Profile packet budget");
            },
            b -> {
                int start = b.readerIndex();
                long session = b.readLong(), seed = b.readLong();
                int version = b.readVarInt();
                var generation =
                    PlanetGeneration.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(b.readUtf(65536)))
                        .getOrThrow();
                var p = new Profile(session, seed, version, generation, b.readResourceLocation(),
                    b.readBoolean() ? Optional.of(SurfacePolicy.read(b)) : Optional.empty(),
                    b.readBoolean() ? Optional.of(PlanetPlan.readPacket(b)) : Optional.empty(),
                    b.readBoolean() ? Optional.of(new PlanetPlanSync.Reference(b.readUtf(64), b.readVarInt()))
                                    : Optional.empty());
                if (b.readerIndex() - start > PACKET_LIMIT)
                    throw new IllegalArgumentException("Profile packet budget");
                return p;
            });
        @Override
        public Type<Profile> type() {
            return TYPE;
        }
    }
    private PlanetPackets() {
    }
}
