package dev.starfold.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.starfold.planet.PlanetShape;

/** Saved generator policy, not world contents. Unknown algorithms fail closed. */
public record PlanetGeneration(
        String type,
        String algorithm,
        int algorithmVersion,
        double relief,
        double oreBias,
        double caveWidth,
        int size,
        int depth,
        String shell,
        PlanetPalette palette,
        PlanetMinerals minerals,
        PlanetClimate climate,
        boolean creatures,
        boolean features,
        boolean structures) {
    /** A planet saved before it was said has neither the features of its biomes nor structures. */
    public PlanetGeneration(
            String type,
            String algorithm,
            int algorithmVersion,
            double relief,
            double oreBias,
            double caveWidth,
            int size,
            int depth,
            String shell,
            PlanetPalette palette,
            PlanetMinerals minerals,
            PlanetClimate climate,
            boolean creatures) {
        this(
                type,
                algorithm,
                algorithmVersion,
                relief,
                oreBias,
                caveWidth,
                size,
                depth,
                shell,
                palette,
                minerals,
                climate,
                creatures,
                false,
                false);
    }

    /** A planet saved before it was said whether anything lives on it has the creatures of its biomes. */
    public PlanetGeneration(
            String type,
            String algorithm,
            int algorithmVersion,
            double relief,
            double oreBias,
            double caveWidth,
            int size,
            int depth,
            String shell,
            PlanetPalette palette,
            PlanetMinerals minerals,
            PlanetClimate climate) {
        this(
                type,
                algorithm,
                algorithmVersion,
                relief,
                oreBias,
                caveWidth,
                size,
                depth,
                shell,
                palette,
                minerals,
                climate,
                true);
    }

    /** A planet saved before climates were chosen is mild. */
    public PlanetGeneration(
            String type,
            String algorithm,
            int algorithmVersion,
            double relief,
            double oreBias,
            double caveWidth,
            int size,
            int depth,
            String shell,
            PlanetPalette palette,
            PlanetMinerals minerals) {
        this(
                type,
                algorithm,
                algorithmVersion,
                relief,
                oreBias,
                caveWidth,
                size,
                depth,
                shell,
                palette,
                minerals,
                PlanetClimate.MILD);
    }

    /** A planet saved before minerals were chosen has those of its kind. */
    public PlanetGeneration(
            String type,
            String algorithm,
            int algorithmVersion,
            double relief,
            double oreBias,
            double caveWidth,
            int size,
            int depth,
            String shell,
            PlanetPalette palette) {
        this(
                type,
                algorithm,
                algorithmVersion,
                relief,
                oreBias,
                caveWidth,
                size,
                depth,
                shell,
                palette,
                PlanetMinerals.NONE);
    }

    /** A planet saved before palettes were chosen is plain rock. */
    public PlanetGeneration(
            String type,
            String algorithm,
            int algorithmVersion,
            double relief,
            double oreBias,
            double caveWidth,
            int size,
            int depth,
            String shell) {
        this(
                type,
                algorithm,
                algorithmVersion,
                relief,
                oreBias,
                caveWidth,
                size,
                depth,
                shell,
                PlanetPalette.ROCK);
    }

    /** Blocks from the nominal surface down to the floor nobody digs through, as every planet had before depths were
     *  saved: the vanilla range, whatever the planet's size. */
    public static final int STANDARD_DEPTH = PlanetTerrain.SEA_LEVEL - 1 - PlanetTerrain.MIN_Y;

    /** What the floor is made of: bedrock, as every planet had before floors were chosen, or the planet's own core shell. */
    public static final String BEDROCK = "bedrock", CORE_SHELL = "core_shell";

    /** A planet saved before floors were chosen has a bedrock floor. */
    public PlanetGeneration(
            String type,
            String algorithm,
            int algorithmVersion,
            double relief,
            double oreBias,
            double caveWidth,
            int size,
            int depth) {
        this(type, algorithm, algorithmVersion, relief, oreBias, caveWidth, size, depth, BEDROCK);
    }

    /** A planet saved before depths were chosen is the standard depth. */
    public PlanetGeneration(
            String type,
            String algorithm,
            int algorithmVersion,
            double relief,
            double oreBias,
            double caveWidth,
            int size) {
        this(type, algorithm, algorithmVersion, relief, oreBias, caveWidth, size, STANDARD_DEPTH);
    }

    /** A planet saved before sizes were chosen is the standard size. */
    public PlanetGeneration(
            String type,
            String algorithm,
            int algorithmVersion,
            double relief,
            double oreBias,
            double caveWidth) {
        this(
                type,
                algorithm,
                algorithmVersion,
                relief,
                oreBias,
                caveWidth,
                PlanetShape.STANDARD.size());
    }

    public static final Codec<PlanetGeneration> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Codec.STRING
                                                    .fieldOf("type")
                                                    .forGetter(PlanetGeneration::type),
                                            Codec.STRING
                                                    .fieldOf("algorithm")
                                                    .forGetter(PlanetGeneration::algorithm),
                                            Codec.INT
                                                    .fieldOf("algorithm_version")
                                                    .forGetter(PlanetGeneration::algorithmVersion),
                                            Codec.doubleRange(.25, 2)
                                                    .fieldOf("relief")
                                                    .forGetter(PlanetGeneration::relief),
                                            Codec.doubleRange(-.1, .1)
                                                    .fieldOf("ore_bias")
                                                    .forGetter(PlanetGeneration::oreBias),
                                            Codec.doubleRange(.03, .2)
                                                    .fieldOf("cave_width")
                                                    .forGetter(PlanetGeneration::caveWidth),
                                            Codec.INT
                                                    .optionalFieldOf(
                                                            "size", PlanetShape.STANDARD.size())
                                                    .forGetter(PlanetGeneration::size),
                                            Codec.INT
                                                    .optionalFieldOf("depth", STANDARD_DEPTH)
                                                    .forGetter(PlanetGeneration::depth),
                                            Codec.STRING
                                                    .optionalFieldOf("shell", BEDROCK)
                                                    .forGetter(PlanetGeneration::shell),
                                            PlanetPalette.CODEC
                                                    .optionalFieldOf("palette", PlanetPalette.ROCK)
                                                    .forGetter(PlanetGeneration::palette),
                                            PlanetMinerals.CODEC
                                                    .optionalFieldOf(
                                                            "minerals", PlanetMinerals.NONE)
                                                    .forGetter(PlanetGeneration::minerals),
                                            PlanetClimate.CODEC
                                                    .optionalFieldOf("climate", PlanetClimate.MILD)
                                                    .forGetter(PlanetGeneration::climate),
                                            Codec.BOOL
                                                    .optionalFieldOf("creatures", true)
                                                    .forGetter(PlanetGeneration::creatures),
                                            Codec.BOOL
                                                    .optionalFieldOf("features", false)
                                                    .forGetter(PlanetGeneration::features),
                                            Codec.BOOL
                                                    .optionalFieldOf("structures", false)
                                                    .forGetter(PlanetGeneration::structures))
                                    .apply(i, PlanetGeneration::new));

    public PlanetGeneration {
        if (type == null
                || algorithm == null
                || algorithmVersion != 1
                || !Double.isFinite(relief)
                || relief < .25
                || relief > 2
                || !Double.isFinite(oreBias)
                || oreBias < -.1
                || oreBias > .1
                || !Double.isFinite(caveWidth)
                || caveWidth < .03
                || caveWidth > .2) throw new IllegalArgumentException("Native generation policy");
        if (depth < STANDARD_DEPTH || depth > new PlanetShape(size).radius() * .75)
            throw new IllegalArgumentException("Planet depth " + depth);
        if (!BEDROCK.equals(shell) && !CORE_SHELL.equals(shell))
            throw new IllegalArgumentException("Planet shell " + shell);
        if (palette == null
                || !palette.equals(PlanetPalette.ROCK)
                        && !(type.equals("rocky") && algorithm.equals("rocky_craters")))
            throw new IllegalArgumentException(
                    "Only a cratered rocky planet has a palette of its own");
        if (minerals == null
                || !minerals.equals(PlanetMinerals.NONE)
                        && !(type.equals("rocky")
                                ? algorithm.equals("rocky_craters")
                                : algorithm.equals("ecology") || algorithm.equals("mining")))
            throw new IllegalArgumentException("This kind of planet has no minerals of its own");
        if (climate == null || !climate.equals(PlanetClimate.MILD) && !algorithm.equals("ecology"))
            throw new IllegalArgumentException(
                    "Only a planet with regions has a climate of its own");
        if (type.equals("rocky")
                ? !java.util.Set.of("rocky", "rocky_craters").contains(algorithm)
                : !type.equals("temperate")
                        || !java.util.Set.of("surface", "mining", "ecology").contains(algorithm)
                        || relief != 1
                        || oreBias != 0
                        || caveWidth != .115)
            throw new IllegalArgumentException(
                    "Unsupported native generation algorithm/parameters");
    }

    public static PlanetGeneration defaults(int version) {
        return switch (version) {
            case 2 -> temperate("surface");
            case 3 -> temperate("mining");
            case 4 -> temperate("ecology");
            case 5 -> new PlanetGeneration("rocky", "rocky", 1, 1, .035, .085);
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported native terrain version: " + version);
        };
    }

    private static PlanetGeneration temperate(String algorithm) {
        return new PlanetGeneration("temperate", algorithm, 1, 1, 0, .115);
    }

    public void validate(int terrainVersion) {
        var expected = defaults(terrainVersion);
        if (!type.equals(expected.type)
                || !(algorithm.equals(expected.algorithm) || terrainVersion == 5 && exploration()))
            throw new IllegalArgumentException("Native algorithm/terrain version mismatch");
    }

    public boolean exploration() {
        return type.equals("rocky") && algorithm.equals("rocky_craters");
    }

    public boolean rocky() {
        return type.equals("rocky");
    }

    public PlanetGeneration withClimate(PlanetClimate climate) {
        return new PlanetGeneration(
                type,
                algorithm,
                algorithmVersion,
                relief,
                oreBias,
                caveWidth,
                size,
                depth,
                shell,
                palette,
                minerals,
                climate,
                creatures,
                features,
                structures);
    }

    public boolean coreShell() {
        return shell.equals(CORE_SHELL);
    }

    public PlanetShape shape() {
        return new PlanetShape(size);
    }

    /** The lowest layer of the planet: the floor itself. Nothing of the planet is generated below it. */
    public int floor() {
        return PlanetTerrain.SEA_LEVEL - 1 - depth;
    }
}
