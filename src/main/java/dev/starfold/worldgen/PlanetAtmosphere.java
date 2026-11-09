package dev.starfold.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.FriendlyByteBuf;

/** Inert atmosphere metadata. Color is an unsigned 24-bit RGB integer (0xRRGGBB). */
public record PlanetAtmosphere(double density, int color, boolean breathable) {
    public static final int EARTH_BLUE = 0x78A7FF;
    public static final PlanetAtmosphere NONE = new PlanetAtmosphere(0, EARTH_BLUE, false);
    public static final PlanetAtmosphere EARTH = new PlanetAtmosphere(1, EARTH_BLUE, true);
    public static final Codec<PlanetAtmosphere> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Codec.DOUBLE
                                                    .fieldOf("density")
                                                    .forGetter(PlanetAtmosphere::density),
                                            Codec.intRange(0, 0xFFFFFF)
                                                    .fieldOf("color")
                                                    .forGetter(PlanetAtmosphere::color),
                                            Codec.BOOL
                                                    .fieldOf("breathable")
                                                    .forGetter(PlanetAtmosphere::breathable))
                                    .apply(i, PlanetAtmosphere::new));

    public PlanetAtmosphere {
        if (!Double.isFinite(density) || density < 0 || color < 0 || color > 0xFFFFFF)
            throw new IllegalArgumentException("Planet atmosphere");
    }

    public void write(FriendlyByteBuf b) {
        b.writeDouble(density);
        b.writeInt(color);
        b.writeBoolean(breathable);
    }

    public static PlanetAtmosphere read(FriendlyByteBuf b) {
        return new PlanetAtmosphere(b.readDouble(), b.readInt(), b.readBoolean());
    }
}
