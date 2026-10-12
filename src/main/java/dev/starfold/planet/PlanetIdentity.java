package dev.starfold.planet;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** Index is the position in the complete Shapes table, never in the near-profile cache. */
public record PlanetIdentity(
        Optional<ResourceLocation> type, String family, Optional<String> name) {
    public PlanetIdentity {
        java.util.Objects.requireNonNull(type);
        java.util.Objects.requireNonNull(name);
        if (!family.equals("temperate") && !family.equals("rocky"))
            throw new IllegalArgumentException("Planet family");
        name.ifPresent(PlanetIdentity::validName);
    }

    public static PlanetIdentity of(PlanetSystem.SavedPlanet planet) {
        return planet.identity();
    }

    public static void validName(String name) {
        if (name.isBlank()
                || name.length() > 64
                || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(
                    "planet name needs 1..64 characters without control characters");
    }

    /** A, B, … Z, then P26, P27 …: what a planet is called until someone names it. */
    public static String defaultName(int planet) {
        return planet < 26 ? String.valueOf((char) ('A' + planet)) : "P" + planet;
    }

    public String label(Function<String, String> translate) {
        if (name.isPresent()) return translate.apply(name.get());
        var id = type.orElseGet(() -> ResourceLocation.fromNamespaceAndPath("starfold", family));
        String key = "planet_type." + id.getNamespace() + "." + id.getPath();
        String value = translate.apply(key);
        return value.equals(key) ? id.getPath() : value;
    }

    /** Reserve letter and live cycle rank; only the variable name is ellipsized. */
    public static String caption(
            int planet,
            String name,
            int rank,
            int count,
            int width,
            ToIntFunction<String> measure) {
        String prefix = defaultName(planet) + " · ", suffix = " · " + rank + "/" + count;
        return prefix
                + ellipsis(name, Math.max(0, width - measure.applyAsInt(prefix + suffix)), measure)
                + suffix;
    }

    static String ellipsis(String text, int width, ToIntFunction<String> measure) {
        if (measure.applyAsInt(text) <= width) return text;
        if (measure.applyAsInt("…") > width) return "";
        int end = text.length();
        while (end > 0 && measure.applyAsInt(text.substring(0, end) + "…") > width)
            end = text.offsetByCodePoints(end, -1);
        return text.substring(0, end) + "…";
    }
}
