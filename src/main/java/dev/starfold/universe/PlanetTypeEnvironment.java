package dev.starfold.universe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.starfold.worldgen.PlanetAtmosphere;
import dev.starfold.worldgen.PlanetEnvironment;

import java.util.Optional;

/** Authoring overrides; missing values use an Earth-like atmosphere and size-based gravity. */
public record PlanetTypeEnvironment(
        Optional<PlanetAtmosphere> atmosphere, Optional<Double> gravity, double temperatureOffset) {
    public static final PlanetTypeEnvironment DEFAULT =
            new PlanetTypeEnvironment(Optional.empty(), Optional.empty(), 0);
    public static final Codec<PlanetTypeEnvironment> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            PlanetAtmosphere.CODEC
                                                    .optionalFieldOf("atmosphere")
                                                    .forGetter(PlanetTypeEnvironment::atmosphere),
                                            PlanetEnvironment.GRAVITY
                                                    .optionalFieldOf("gravity")
                                                    .forGetter(PlanetTypeEnvironment::gravity),
                                            PlanetEnvironment.TEMPERATURE_OFFSET
                                                    .optionalFieldOf("temperature_offset", 0.0)
                                                    .forGetter(PlanetTypeEnvironment::temperatureOffset))
                                    .apply(i, PlanetTypeEnvironment::new));

    public PlanetTypeEnvironment {
        java.util.Objects.requireNonNull(atmosphere);
        java.util.Objects.requireNonNull(gravity);
        gravity.ifPresent(
                v -> {
                    if (!Double.isFinite(v) || v <= 0)
                        throw new IllegalArgumentException("Planet gravity");
                });
    }

    public PlanetEnvironment resolve(boolean temperate, int size) {
        var defaults = PlanetEnvironment.defaults(temperate, size);
        return new PlanetEnvironment(
                atmosphere.orElse(defaults.atmosphere()), gravity.orElse(defaults.gravity()), temperatureOffset);
    }
}
