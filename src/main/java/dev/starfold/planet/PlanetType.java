package dev.starfold.planet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.starfold.worldgen.PlanetBiomes;
import dev.starfold.worldgen.PlanetChunkGenerator;
import dev.starfold.worldgen.PlanetClimate;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetMinerals;
import dev.starfold.worldgen.PlanetPalette;
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

/** A kind of planet, defined by a data pack (data/<namespace>/starfold/planet_type/<name>.json): the range
 *  of sizes its planets have, how deep they go, what they are made of and how their ground is shaped. A type is read
 *  when a world is made; each planet is then saved with the values it was given, so a world keeps its planets
 *  whatever becomes of the pack. A type is rocky (algorithm rocky or rocky_craters) or temperate (algorithm ecology,
 *  with seas, soil and growth, and the biomes of its regions). */
public record PlanetType(
        Holder<Biome> biome,
        int minSize,
        int maxSize,
        double depthRatio,
        String algorithm,
        double relief,
        double oreBias,
        double caveWidth,
        String shell,
        PlanetPalette palette,
        Optional<Life> life,
        PlanetMinerals minerals,
        PlanetClimate climate,
        Optional<Double> ocean,
        boolean creatures,
        boolean features,
        boolean structures) {
    /** The biomes of the regions of a temperate planet. */
    public record Life(
            Holder<Biome> forest, Holder<Biome> plains, Holder<Biome> desert, Holder<Biome> ocean) {
        static final Codec<Life> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                Biome.CODEC
                                                        .fieldOf("forest")
                                                        .forGetter(Life::forest),
                                                Biome.CODEC
                                                        .fieldOf("plains")
                                                        .forGetter(Life::plains),
                                                Biome.CODEC
                                                        .fieldOf("desert")
                                                        .forGetter(Life::desert),
                                                Biome.CODEC.fieldOf("ocean").forGetter(Life::ocean))
                                        .apply(i, Life::new));
    }

    public static final String ECOLOGY = "ecology";

    public boolean temperate() {
        return algorithm.equals(ECOLOGY);
    }

    public static final ResourceKey<Registry<PlanetType>> KEY =
            ResourceKey.createRegistryKey(
                    ResourceLocation.fromNamespaceAndPath("starfold", "planet_type"));

    private record Sizes(int min, int max) {
        static final Codec<Sizes> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                Codec.INT.fieldOf("min").forGetter(Sizes::min),
                                                Codec.INT.fieldOf("max").forGetter(Sizes::max))
                                        .apply(i, Sizes::new));
    }

    public static final Codec<PlanetType> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Biome.CODEC
                                                    .fieldOf("biome")
                                                    .forGetter(PlanetType::biome),
                                            Sizes.CODEC
                                                    .fieldOf("size")
                                                    .forGetter(
                                                            t -> new Sizes(t.minSize, t.maxSize)),
                                            Codec.DOUBLE
                                                    .optionalFieldOf("depth_ratio", .5)
                                                    .forGetter(PlanetType::depthRatio),
                                            Codec.STRING
                                                    .optionalFieldOf("algorithm", "rocky_craters")
                                                    .forGetter(PlanetType::algorithm),
                                            Codec.DOUBLE
                                                    .optionalFieldOf("relief")
                                                    .forGetter(t -> Optional.of(t.relief)),
                                            Codec.DOUBLE
                                                    .optionalFieldOf("ore_bias")
                                                    .forGetter(t -> Optional.of(t.oreBias)),
                                            Codec.DOUBLE
                                                    .optionalFieldOf("cave_width")
                                                    .forGetter(t -> Optional.of(t.caveWidth)),
                                            Codec.STRING
                                                    .optionalFieldOf(
                                                            "shell", PlanetGeneration.CORE_SHELL)
                                                    .forGetter(PlanetType::shell),
                                            PlanetPalette.CODEC
                                                    .optionalFieldOf("palette", PlanetPalette.ROCK)
                                                    .forGetter(PlanetType::palette),
                                            Life.CODEC
                                                    .optionalFieldOf("biomes")
                                                    .forGetter(PlanetType::life),
                                            PlanetMinerals.CODEC
                                                    .optionalFieldOf(
                                                            "minerals", PlanetMinerals.NONE)
                                                    .forGetter(PlanetType::minerals),
                                            PlanetClimate.CODEC
                                                    .optionalFieldOf("climate", PlanetClimate.MILD)
                                                    .forGetter(PlanetType::climate),
                                            Codec.doubleRange(0, .95)
                                                    .optionalFieldOf("ocean")
                                                    .forGetter(PlanetType::ocean),
                                            Codec.BOOL
                                                    .optionalFieldOf("creatures")
                                                    .forGetter(t -> Optional.of(t.creatures)),
                                            Codec.BOOL
                                                    .optionalFieldOf("features")
                                                    .forGetter(t -> Optional.of(t.features)),
                                            Codec.BOOL
                                                    .optionalFieldOf("structures")
                                                    .forGetter(t -> Optional.of(t.structures))
                                            // What is left unsaid is what its kind of planet has
                                            // always had; and a planet with seas and growth has the
                                            // creatures of its biomes, where a bare one has none
                                            // unless its type says so.
                                            )
                                    .apply(
                                            i,
                                            (biome,
                                                    size,
                                                    depth,
                                                    algorithm,
                                                    relief,
                                                    ore,
                                                    cave,
                                                    shell,
                                                    palette,
                                                    life,
                                                    minerals,
                                                    climate,
                                                    ocean,
                                                    creatures,
                                                    features,
                                                    structures) -> {
                                                boolean temperate = algorithm.equals(ECOLOGY);
                                                return new PlanetType(
                                                        biome,
                                                        size.min(),
                                                        size.max(),
                                                        depth,
                                                        algorithm,
                                                        relief.orElse(1.0),
                                                        ore.orElse(temperate ? 0 : .035),
                                                        cave.orElse(temperate ? .115 : .085),
                                                        shell,
                                                        palette,
                                                        life,
                                                        minerals,
                                                        climate,
                                                        ocean,
                                                        creatures.orElse(temperate),
                                                        features.orElse(temperate),
                                                        structures.orElse(temperate));
                                            }));

    /** A planet names its type, or carries one written out in its place. */
    public static final Codec<Holder<PlanetType>> HOLDER = RegistryFileCodec.create(KEY, CODEC);

    public PlanetType {
        if (biome == null || life == null || life.isPresent() != algorithm.equals(ECOLOGY))
            throw new IllegalArgumentException(
                    "A temperate planet, and only one, has the biomes of its regions");
        if (ocean == null
                || ocean.isPresent()
                        && (!algorithm.equals(ECOLOGY)
                                || climate.land() != 0
                                || !(ocean.get() >= 0 && ocean.get() <= .95)))
            throw new IllegalArgumentException(
                    "A share of ocean is for a planet with a sea, and in place of a height of land");
        if (minSize > maxSize || !Double.isFinite(depthRatio) || depthRatio < 0 || depthRatio > .75)
            throw new IllegalArgumentException("Planet type sizes/depth");
        new PlanetShape(minSize);
        new PlanetShape(maxSize);
        // The same checks a saved planet passes, made once here for both ends of the range.
        for (int size : new int[] {minSize, maxSize})
            new PlanetGeneration(
                    algorithm.equals(ECOLOGY) ? "temperate" : "rocky",
                    algorithm,
                    1,
                    relief,
                    oreBias,
                    caveWidth,
                    size,
                    depth(size, depthRatio),
                    shell,
                    palette,
                    minerals,
                    climate);
        // The biomes of a temperate planet are checked as they are for a planet written out.
        life.ifPresent(l -> new PlanetBiomes(0, l.forest(), l.plains(), l.desert(), l.ocean()));
    }

    /** How deep a planet of this type and size goes: its share of the radius, never less than the vanilla range. */
    public int depth(int size) {
        return depth(size, depthRatio);
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
        int steps = (maxSize - minSize) / 16 + 1;
        long mixed = (seed ^ 0x9E3779B97F4A7C15L) * 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 31;
        return minSize + 16 * (int) Long.remainderUnsigned(mixed, steps);
    }

    public PlanetGeneration generation(int size) {
        if (size < minSize || size > maxSize)
            throw new IllegalArgumentException(
                    "Planet size " + size + " outside its type's " + minSize + " to " + maxSize);
        return new PlanetGeneration(
                temperate() ? "temperate" : "rocky",
                algorithm,
                1,
                relief,
                oreBias,
                caveWidth,
                size,
                depth(size),
                shell,
                palette,
                minerals,
                climate,
                creatures,
                features,
                structures);
    }

    /** One planet of this type: the size it is given, or the one its seed gives it. */
    public PlanetChunkGenerator planet(long seed, Optional<Integer> size) {
        var settings =
                new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()), biome, List.of());
        settings.getLayersInfo().add(new FlatLayerInfo(1, Blocks.BEDROCK));
        settings.updateLayers();
        var generation = generation(size.orElseGet(() -> size(seed)));
        if (temperate()) {
            var l = life.get();
            // The share of ocean a type asks for is met on each planet as its own seed makes it,
            // and saved as the height
            // its ground was raised by.
            if (ocean.isPresent())
                generation =
                        generation.withClimate(
                                climate.withLand(
                                        new PlanetTerrain(
                                                        seed,
                                                        PlanetTerrain.ECOLOGY_VERSION,
                                                        generation)
                                                .landFor(ocean.get())));
            return new PlanetChunkGenerator(
                    settings,
                    seed,
                    PlanetTerrain.ECOLOGY_VERSION,
                    Optional.of(
                            new PlanetBiomes(seed, l.forest(), l.plains(), l.desert(), l.ocean())),
                    Optional.of(generation));
        }
        return new PlanetChunkGenerator(
                settings,
                seed,
                PlanetTerrain.ROCK_VERSION,
                Optional.empty(),
                Optional.of(generation));
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
