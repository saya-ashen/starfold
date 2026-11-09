package dev.starfold.chart;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Saved gas-layer policy. Radius and orbital identity belong to the catalog body, not this policy. */
public record GasGiant(
        int version,
        long seed,
        String name,
        int lowColor,
        int highColor,
        double outerHeight,
        double maxDepth) {
    public static final int VERSION = 1;
    public static final int MAX_PER_SUN = 8, MAX_COUNT = 64;
    public static final double MIN_RADIUS = 1024, MAX_RADIUS = 16384;
    public static final Codec<GasGiant> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Codec.INT
                                                    .optionalFieldOf("version", VERSION)
                                                    .forGetter(GasGiant::version),
                                            Codec.LONG.fieldOf("seed").forGetter(GasGiant::seed),
                                            Codec.STRING.fieldOf("name").forGetter(GasGiant::name),
                                            Codec.INT
                                                    .fieldOf("low_color")
                                                    .forGetter(GasGiant::lowColor),
                                            Codec.INT
                                                    .fieldOf("high_color")
                                                    .forGetter(GasGiant::highColor),
                                            Codec.DOUBLE
                                                    .optionalFieldOf("outer_height", 256.0)
                                                    .forGetter(GasGiant::outerHeight),
                                            Codec.DOUBLE
                                                    .optionalFieldOf("max_depth", 512.0)
                                                    .forGetter(GasGiant::maxDepth))
                                    .apply(i, GasGiant::new));

    public GasGiant {
        if (version != VERSION
                || name == null
                || name.isBlank()
                || name.length() > 64
                || name.codePoints().anyMatch(Character::isISOControl)
                || lowColor < 0
                || lowColor > 0xffffff
                || highColor < 0
                || highColor > 0xffffff
                || !Double.isFinite(outerHeight)
                || outerHeight < 16
                || outerHeight > 1024
                || !Double.isFinite(maxDepth)
                || maxDepth < 16
                || maxDepth > 2048) throw new IllegalArgumentException("Gas giant policy");
    }

    public void validateRadius(double radius) {
        if (!Double.isFinite(radius)
                || radius < MIN_RADIUS
                || radius > MAX_RADIUS
                || maxDepth >= radius) throw new IllegalArgumentException("Gas giant radius/depth");
    }

    /** Depth below the nominal radius; negative values are still in the outer gas layer. */
    public double pressure(double depth) {
        if (!Double.isFinite(depth)) throw new IllegalArgumentException("Gas depth");
        return Math.clamp((depth + outerHeight) / (maxDepth + outerHeight), 0, 1);
    }

    /** Zero is safe; positive values are a game-tick interval, not a ship HP/exposure ledger. */
    public static int damageInterval(double pressure) {
        if (!Double.isFinite(pressure) || pressure < 0 || pressure > 1)
            throw new IllegalArgumentException("Gas pressure");
        return pressure < .5 ? 0 : (int) Math.max(20, Math.round(100 - 160 * (pressure - .5)));
    }
}
