package dev.starfold.ground;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.planet.PlanetIdentity;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.sky.GroundEphemerisSave;
import dev.starfold.universe.SystemDefinition;
import dev.starfold.universe.Universe;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Only sky metadata is custom. Native packets own chunks, block entities, menus and inventory. */
public final class GroundPackets {
    /** The time, every second, and the model of everything that moves: a universe's model has an orbit for each planet
     *  found, so it goes with the time only when it has changed or has not gone for a while, and is otherwise null. */
    public record Sky(long tick, GroundEphemeris.Model model) implements CustomPacketPayload {
        public Sky {
            GroundEphemeris.time(tick);
            if (model != null && !model.sun().enabled())
                throw new IllegalArgumentException("Native sky");
        }

        public static final Type<Sky> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_ground_v2_sky"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sky> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeLong(p.tick);
                            b.writeBoolean(p.model != null);
                            if (p.model != null) b.writeNbt(GroundEphemerisSave.modelTag(p.model));
                        },
                        b -> {
                            long tick = b.readLong();
                            if (!b.readBoolean()) return new Sky(tick, null);
                            var tag = b.readNbt();
                            if (tag == null || !tag.contains("sun", 10))
                                throw new IllegalArgumentException("Missing native sun");
                            return new Sky(tick, GroundEphemerisSave.model(tag));
                        });

        @Override
        public Type<Sky> type() {
            return TYPE;
        }
    }

    /** The size and depth of each planet of the system the client has joined. */
    public record Shapes(
            int[] sizes,
            int[] depths,
            java.util.List<String> names,
            java.util.List<PlanetIdentity> identities)
            implements CustomPacketPayload {
        public Shapes(int[] sizes, int[] depths, java.util.List<String> names) {
            this(
                    sizes,
                    depths,
                    names,
                    java.util.stream.IntStream.range(0, sizes.length)
                            .mapToObj(
                                    i ->
                                            new PlanetIdentity(
                                                    java.util.Optional.empty(),
                                                    i == 0 ? "temperate" : "rocky",
                                                    java.util.Optional.empty()))
                            .toList());
        }

        public Shapes(int[] sizes, int[] depths) {
            this(sizes, depths, java.util.List.of());
        }

        public Shapes {
            identities = java.util.List.copyOf(identities);
            if (identities.size() != sizes.length)
                throw new IllegalArgumentException("Planet identities/shapes disagree");
            names = java.util.List.copyOf(names);
            if (names.size() > Universe.CAPACITY
                    || !names.isEmpty() && names.size() != sizes.length)
                throw new IllegalArgumentException("Planet names/shapes disagree");
            names.forEach(SystemDefinition::validName);
            if (sizes.length < 1
                    || sizes.length > PlanetStorage.MAX_PLANETS
                    || depths.length != sizes.length)
                throw new IllegalArgumentException("Planet shapes");
            for (int size : sizes) new PlanetShape(size);
        }

        public static final Type<Shapes> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_planet_shapes_v3"));

        /** Takes effect as it is decoded: packets decoded after it are validated against these shapes before any handler runs. */
        public static final StreamCodec<RegistryFriendlyByteBuf, Shapes> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeVarIntArray(p.sizes);
                            b.writeVarIntArray(p.depths);
                            b.writeCollection(p.names, (buf, name) -> buf.writeUtf(name, 64));
                            for (var identity : p.identities) {
                                b.writeOptional(
                                        identity.type(),
                                        (buf, id) -> buf.writeResourceLocation(id));
                                b.writeUtf(identity.family(), 16);
                                b.writeOptional(
                                        identity.name(), (buf, name) -> buf.writeUtf(name, 64));
                            }
                        },
                        b -> {
                            var sizes = b.readVarIntArray(Universe.CAPACITY);
                            var depths = b.readVarIntArray(Universe.CAPACITY);
                            var names = names(b);
                            var identities = new java.util.ArrayList<PlanetIdentity>(sizes.length);
                            for (int i = 0; i < sizes.length; i++)
                                identities.add(
                                        new PlanetIdentity(
                                                b.readOptional(buf -> buf.readResourceLocation()),
                                                b.readUtf(16),
                                                b.readOptional(buf -> buf.readUtf(64))));
                            var shapes = new Shapes(sizes, depths, names, identities);
                            shapes.assign();
                            return shapes;
                        });

        private static java.util.List<String> names(RegistryFriendlyByteBuf buffer) {
            int count = buffer.readVarInt();
            if (count < 0 || count > Universe.CAPACITY)
                throw new IllegalArgumentException("Planet names capacity");
            var names = new java.util.ArrayList<String>(count);
            for (int i = 0; i < count; i++) names.add(buffer.readUtf(64));
            return names;
        }

        public void assign() {
            PlanetShape.system(sizes, depths);
            PlanetSystem.names(names);
            PlanetSystem.identities(identities);
        }

        @Override
        public Type<Shapes> type() {
            return TYPE;
        }
    }

    private GroundPackets() {}
}
