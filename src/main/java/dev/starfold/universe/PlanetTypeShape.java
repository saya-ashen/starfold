package dev.starfold.universe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.starfold.chart.PlanetShape;
import dev.starfold.worldgen.PlanetGeneration;

/** Size range, excavation depth and core shell of a planet type. */
public record PlanetTypeShape(Sizes size, double depthRatio, String shell) {
    public record Sizes(int min, int max) {
        public static final Codec<Sizes> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                Codec.INT.fieldOf("min").forGetter(Sizes::min),
                                                Codec.INT.fieldOf("max").forGetter(Sizes::max))
                                        .apply(i, Sizes::new));

        public Sizes {
            new PlanetShape(min);
            new PlanetShape(max);
            if (min > max) throw new IllegalArgumentException("Planet type sizes");
        }
    }

    public static final Codec<PlanetTypeShape> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Sizes.CODEC
                                                    .fieldOf("size")
                                                    .forGetter(PlanetTypeShape::size),
                                            Codec.doubleRange(0, .75)
                                                    .optionalFieldOf("depth_ratio", .5)
                                                    .forGetter(PlanetTypeShape::depthRatio),
                                            Codec.STRING
                                                    .optionalFieldOf(
                                                            "shell", PlanetGeneration.CORE_SHELL)
                                                    .forGetter(PlanetTypeShape::shell))
                                    .apply(i, PlanetTypeShape::new));

    public PlanetTypeShape {
        if (size == null
                || !Double.isFinite(depthRatio)
                || depthRatio < 0
                || depthRatio > .75
                || !java.util.Set.of(PlanetGeneration.CORE_SHELL, PlanetGeneration.BEDROCK)
                        .contains(shell)) throw new IllegalArgumentException("Planet type shape");
    }
}
