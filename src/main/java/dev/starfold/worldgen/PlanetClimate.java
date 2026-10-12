package dev.starfold.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.FriendlyByteBuf;

/** How the regions of a temperate planet are shared out. Humidity moves the whole planet towards forest (above
 *  nought) or desert (below); temperature towards desert where it is dry (above) or away from it; latitude is how
 *  much colder the poles are than the equator, so that at nought regions lie as chance has them and the higher it is
 *  the more deserts keep to a belt about the equator. Land is how many blocks the whole of the ground stands higher
 *  (or lower) against the sea, which stays where it is: the more, the less of the planet is under it. */
public record PlanetClimate(double humidity, double temperature, double latitude, double land) {
    public PlanetClimate(double humidity, double temperature, double latitude) {
        this(humidity, temperature, latitude, 0);
    }

    public PlanetClimate withLand(double land) {
        return new PlanetClimate(humidity, temperature, latitude, land);
    }

    public static final double MAX_LAND = 40;

    /** Every temperate planet before climates were saved. */
    public static final PlanetClimate MILD = new PlanetClimate(0, 0, .15);

    public static final Codec<PlanetClimate> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Codec.doubleRange(-.5, .5)
                                                    .optionalFieldOf("humidity", 0.0)
                                                    .forGetter(PlanetClimate::humidity),
                                            Codec.doubleRange(-.5, .5)
                                                    .optionalFieldOf("temperature", 0.0)
                                                    .forGetter(PlanetClimate::temperature),
                                            Codec.doubleRange(0, 1)
                                                    .optionalFieldOf("latitude", .15)
                                                    .forGetter(PlanetClimate::latitude),
                                            Codec.doubleRange(-MAX_LAND, MAX_LAND)
                                                    .optionalFieldOf("land", 0.0)
                                                    .forGetter(PlanetClimate::land))
                                    .apply(i, PlanetClimate::new));

    public PlanetClimate {
        if (!(humidity >= -.5 && humidity <= .5)
                || !(temperature >= -.5 && temperature <= .5)
                || !(latitude >= 0 && latitude <= 1)
                || !(land >= -MAX_LAND && land <= MAX_LAND))
            throw new IllegalArgumentException("Planet climate");
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeDouble(humidity);
        buffer.writeDouble(temperature);
        buffer.writeDouble(latitude);
        buffer.writeDouble(land);
    }

    public static PlanetClimate read(FriendlyByteBuf buffer) {
        return new PlanetClimate(
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
}
