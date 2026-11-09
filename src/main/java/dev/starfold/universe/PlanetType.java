package dev.starfold.universe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.starfold.chart.PlanetShape;
import dev.starfold.worldgen.PlanetBiomes;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetMinerals;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryFileCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;

import java.util.List;
import java.util.Optional;

/** Data-pack recipe and physical/population policy, resolved once into a saved planet. */
public record PlanetType(
        PlanetTypeShape shape,
        PlanetTypeTerrain terrain,
        PlanetTypeLife life,
        PlanetTypeEnvironment environment) {
    public static final ResourceKey<Registry<PlanetType>> KEY =
            ResourceKey.createRegistryKey(
                    ResourceLocation.fromNamespaceAndPath("starfold", "planet_type"));
    public static final Codec<PlanetType> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            PlanetTypeShape.CODEC
                                                    .fieldOf("shape")
                                                    .forGetter(PlanetType::shape),
                                            PlanetTypeTerrain.CODEC
                                                    .fieldOf("terrain")
                                                    .forGetter(PlanetType::terrain),
                                            PlanetTypeLife.CODEC
                                                    .fieldOf("life")
                                                    .forGetter(PlanetType::life),
                                            PlanetTypeEnvironment.CODEC
                                                    .optionalFieldOf(
                                                            "environment",
                                                            PlanetTypeEnvironment.DEFAULT)
                                                    .forGetter(PlanetType::environment))
                                    .apply(i, PlanetType::new));

    // Retain the existing query API; new properties belong to their section, not this facade.
    public Holder<Biome> biome() {
        return life.biome();
    }

    public int minSize() {
        return shape.size().min();
    }

    public int maxSize() {
        return shape.size().max();
    }

    public double depthRatio() {
        return shape.depthRatio();
    }

    public String shell() {
        return shape.shell();
    }

    public boolean creatures() { return life.creatures().orElse(true); }
    public boolean features() { return life.features().orElse(true); }
    public boolean structures() { return life.structures().orElse(true); }

    /** A planet names its type, or carries one written out in its place. */
    public static final Codec<Holder<PlanetType>> HOLDER = RegistryFileCodec.create(KEY, CODEC);

    public PlanetType {
        java.util.Objects.requireNonNull(shape);
        java.util.Objects.requireNonNull(terrain);
        java.util.Objects.requireNonNull(life);
        java.util.Objects.requireNonNull(environment);
        for (int size : new int[] {shape.size().min(), shape.size().max()}) {
            depth(size, shape.depthRatio());
            environment.resolve(true, size);
        }
    }

    /** How deep a planet of this type and size goes: its share of the radius, never less than the vanilla range. */
    public int depth(int size) {
        return depth(size, depthRatio());
    }

    private static int depth(int size, double ratio) {
        double radius = new PlanetShape(size).radius();
        if (Math.floor(radius * .75) < PlanetGeneration.STANDARD_DEPTH)
            throw new IllegalArgumentException(
                    "A planet of "
                            + size
                            + " blocks a face is too small to be "
                            + PlanetGeneration.STANDARD_DEPTH
                            + " deep");
        return (int)
                Math.clamp(
                        Math.round(radius * ratio),
                        PlanetGeneration.STANDARD_DEPTH,
                        Math.floor(radius * .75));
    }

    /** The size a seed gives a planet of this type: any multiple of sixteen in the range, as likely as any other. */
    public int size(long seed) {
        int steps = (maxSize() - minSize()) / 16 + 1;
        long mixed = (seed ^ 0x9E3779B97F4A7C15L) * 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 31;
        return minSize() + 16 * (int) Long.remainderUnsigned(mixed, steps);
    }

    public PlanetGeneration generation(int size) {
        if (size < minSize() || size > maxSize())
            throw new IllegalArgumentException(
                    "Planet size "
                            + size
                            + " outside its type's "
                            + minSize()
                            + " to "
                            + maxSize());
        return new PlanetGeneration("recipe",PlanetGeneration.SURFACE_VERSION,terrain.recipe(),size,depth(size),shell(),creatures(),features(),structures(),environment.resolve(true,size));
    }

    public PlanetChunkGenerator planet(long seed, Optional<Integer> size) {
        var settings=new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()),biome(),List.of());
        settings.getLayersInfo().add(new FlatLayerInfo(1,Blocks.BEDROCK));settings.updateLayers();
        var generation=generation(size.orElseGet(()->size(seed)));
        return new PlanetChunkGenerator(settings,seed,PlanetTerrain.VERSION,Optional.of(new PlanetBiomes(seed,terrain.biomes())),Optional.of(generation));
    }

    /** Resolve a type once, retaining its registry identity in the saved generator, not a live pack dependency. */
    static PlanetChunkGenerator materialize(
            Holder<PlanetType> type, long seed, Optional<Integer> size, Optional<String> name) {
        return type.value()
                .planet(seed, size)
                .identity(type.unwrapKey().map(ResourceKey::location), name);
    }

    /** A planet of a system: written out in full, or a type and a seed that become a planet the first time it is asked
     *  for. A registry's values are not there to be read while the data packs are still loading, which is when a world
     *  preset is read; they are by the time a world is made. A planet is always saved in full. */
    public static final class Entry {
        private final Holder<PlanetType> type;
        private final long seed;
        private final Optional<Integer> size;
        private final Optional<String> name;
        private PlanetChunkGenerator made;

        public Entry(PlanetChunkGenerator made) {
            this.made = java.util.Objects.requireNonNull(made);
            type = null;
            seed = 0;
            size = Optional.empty();
            name = made.displayName();
        }

        Entry(Holder<PlanetType> type, long seed, Optional<Integer> size) {
            this(type, seed, size, Optional.empty());
        }

        Entry(Holder<PlanetType> type, long seed, Optional<Integer> size, Optional<String> name) {
            this.type = java.util.Objects.requireNonNull(type);
            this.seed = seed;
            this.size = size;
            this.name = name;
            name.ifPresent(SystemDefinition::validName);
        }

        /** Whether the planet was written out in full, and so is there without its type. */
        public boolean written() {
            return type == null;
        }

        public synchronized PlanetChunkGenerator get() {
            if (made == null)
                try {
                    made = materialize(type, seed, size, name);
                } catch (IllegalArgumentException | IllegalStateException error) {
                    throw new IllegalArgumentException(
                            "Planet of type "
                                    + type.unwrapKey()
                                            .map(k -> k.location().toString())
                                            .orElse("written in place")
                                    + ": "
                                    + error.getMessage(),
                            error);
                }
            return made;
        }
    }

    private static final Codec<Entry> TYPED =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            HOLDER.fieldOf("planet_type").forGetter(e -> e.type),
                                            Codec.LONG
                                                    .fieldOf("terrain_seed")
                                                    .forGetter(e -> e.seed),
                                            Codec.INT
                                                    .optionalFieldOf("size")
                                                    .forGetter(e -> e.size),
                                            Codec.STRING
                                                    .optionalFieldOf("name")
                                                    .forGetter(e -> e.name))
                                    .apply(i, Entry::new));
    public static final Codec<Entry> PLANET =
            new Codec<>() {
                @Override
                public <T>
                        com.mojang.serialization.DataResult<
                                        com.mojang.datafixers.util.Pair<Entry, T>>
                                decode(com.mojang.serialization.DynamicOps<T> ops, T input) {
                    // Which of the two it is shows in what is written, so a planet of a type that
                    // fails says why.
                    if (ops.get(input, "planet_type").isError())
                        return PlanetChunkGenerator.CODEC
                                .codec()
                                .decode(ops, input)
                                .map(pair -> pair.mapFirst(Entry::new));
                    return TYPED.decode(ops, input);
                }

                @Override
                public <T> com.mojang.serialization.DataResult<T> encode(
                        Entry planet, com.mojang.serialization.DynamicOps<T> ops, T prefix) {
                    return PlanetChunkGenerator.CODEC.codec().encode(planet.get(), ops, prefix);
                }
            };

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(
                (net.neoforged.neoforge.registries.DataPackRegistryEvent.NewRegistry event) ->
                        event.dataPackRegistry(KEY, CODEC));
        UniverseLayout.initialize(modBus);
        SystemDefinition.initialize(modBus);
    }
}
