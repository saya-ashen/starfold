package dev.starfold.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.starfold.chart.PlanetShape;
import dev.starfold.worldgen.plan.terrain.TerrainRecipe;
import java.io.*;

/** Saved S1 recipe and physical policy. Older surface formats are rejected. */
public record PlanetGeneration(String type, int surfaceVersion, TerrainRecipe recipe, int size, int depth,
    String shell, boolean creatures, boolean features, boolean structures, PlanetEnvironment environment) {
    public static final int SURFACE_VERSION = 10, STANDARD_DEPTH = PlanetShape.STANDARD_DEPTH;
    public static final String BEDROCK = "bedrock", CORE_SHELL = "core_shell";
    public static final Codec<PlanetGeneration> CODEC = RecordCodecBuilder.create(i
        -> i.group(Codec.STRING.fieldOf("type").forGetter(PlanetGeneration::type),
                Codec.INT
                    .validate(v
                        -> v == SURFACE_VERSION ? DataResult.success(v)
                                                : DataResult.error(() -> "Unsupported surface version " + v))
                    .fieldOf("surface_version")
                    .forGetter(PlanetGeneration::surfaceVersion),
                TerrainRecipe.CODEC.fieldOf("recipe").forGetter(PlanetGeneration::recipe),
                Codec.intRange(416, 2048).fieldOf("size").forGetter(PlanetGeneration::size),
                Codec.intRange(STANDARD_DEPTH, 2048).fieldOf("depth").forGetter(PlanetGeneration::depth),
                Codec.STRING.fieldOf("shell").forGetter(PlanetGeneration::shell),
                Codec.BOOL.fieldOf("creatures").forGetter(PlanetGeneration::creatures),
                Codec.BOOL.fieldOf("features").forGetter(PlanetGeneration::features),
                Codec.BOOL.fieldOf("structures").forGetter(PlanetGeneration::structures),
                PlanetEnvironment.CODEC.fieldOf("environment").forGetter(PlanetGeneration::environment))
            .apply(i, PlanetGeneration::new));
    public PlanetGeneration {
        if (surfaceVersion != SURFACE_VERSION || recipe == null || size % 16 != 0 || size < 416 || size > 2048
            || depth < STANDARD_DEPTH || depth > new PlanetShape(size).radius() * .75
            || !BEDROCK.equals(shell) && !CORE_SHELL.equals(shell) || environment == null)
            throw new IllegalArgumentException("Planet generation policy");
    }
    public static PlanetGeneration defaults(int version) {
        if (version != PlanetTerrain.VERSION)
            throw new IllegalArgumentException("Unsupported terrain version " + version);
        return new PlanetGeneration("default", SURFACE_VERSION, DefaultRecipe.VALUE, 2048, STANDARD_DEPTH,
            CORE_SHELL, true, false, false, PlanetEnvironment.defaults(true, 2048));
    }
    private static final class DefaultRecipe {
        static final TerrainRecipe VALUE = load();
        static TerrainRecipe load() {
            try (var reader = new InputStreamReader(PlanetGeneration.class.getResourceAsStream(
                                                        "/data/starfold/starfold/planet_type/temperate.json"),
                     java.nio.charset.StandardCharsets.UTF_8)) {
                return TerrainRecipe.CODEC
                    .parse(JsonOps.INSTANCE, JsonParser.parseReader(reader).getAsJsonObject().get("terrain"))
                    .getOrThrow();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
    public PlanetGeneration withRecipe(TerrainRecipe recipe) {
        return new PlanetGeneration(
            type, surfaceVersion, recipe, size, depth, shell, creatures, features, structures, environment);
    }
    public void validate(int version) {
        if (version != PlanetTerrain.VERSION || surfaceVersion != SURFACE_VERSION)
            throw new IllegalArgumentException("Unsupported terrain version");
    }
    public boolean coreShell() {
        return CORE_SHELL.equals(shell);
    }
    public PlanetShape shape() {
        return new PlanetShape(size);
    }
    public int floor() {
        return PlanetTerrain.SEA_LEVEL - 1 - depth;
    }
}
