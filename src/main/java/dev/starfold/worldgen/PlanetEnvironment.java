package dev.starfold.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.FriendlyByteBuf;

/** Resolved once per planet; shared by generation, atmosphere visuals and surface gameplay. */
public record PlanetEnvironment(PlanetAtmosphere atmosphere, double gravity, double temperatureOffset) {
    public static final Codec<Double> TEMPERATURE_OFFSET =
            Codec.DOUBLE.validate(
                    v -> Double.isFinite(v) && Float.isFinite(v.floatValue())
                            ? com.mojang.serialization.DataResult.success(v)
                            : com.mojang.serialization.DataResult.error(
                                    () -> "Temperature offset must be finite in Java and GLSL"));
    public static final Codec<Double> GRAVITY =
            Codec.DOUBLE.validate(
                    v ->
                            Double.isFinite(v) && v > 0
                                    ? com.mojang.serialization.DataResult.success(v)
                                    : com.mojang.serialization.DataResult.error(
                                            () -> "Planet gravity must be finite and positive"));
    public static final Codec<PlanetEnvironment> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            PlanetAtmosphere.CODEC
                                                    .fieldOf("atmosphere")
                                                    .forGetter(PlanetEnvironment::atmosphere),
                                            GRAVITY.fieldOf("gravity")
                                                    .forGetter(PlanetEnvironment::gravity),
                                            TEMPERATURE_OFFSET.optionalFieldOf("temperature_offset", 0.0)
                                                    .forGetter(PlanetEnvironment::temperatureOffset))
                                    .apply(i, PlanetEnvironment::new));

    public PlanetEnvironment {
        if (atmosphere == null || !Double.isFinite(gravity) || gravity <= 0)
            throw new IllegalArgumentException("Planet environment");
    }

    public static PlanetEnvironment defaults(boolean temperate, int size) {
        return new PlanetEnvironment(
                temperate ? PlanetAtmosphere.EARTH : PlanetAtmosphere.NONE,
                Math.clamp(Math.sqrt(size / 1024.0), .35, 1.4), 0);
    }

    public void write(FriendlyByteBuf b) {
        atmosphere.write(b);
        b.writeDouble(gravity);
        b.writeDouble(temperatureOffset);
    }

    public static PlanetEnvironment read(FriendlyByteBuf b) {
        var atmosphere = PlanetAtmosphere.read(b);
        double gravity = b.readDouble();
        double offset = b.readDouble();
        if (!Double.isFinite(offset) || !Float.isFinite((float) offset))
            throw new io.netty.handler.codec.DecoderException("Invalid temperature offset");
        return new PlanetEnvironment(atmosphere, gravity, offset);
    }
}
