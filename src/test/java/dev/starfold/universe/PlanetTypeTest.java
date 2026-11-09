package dev.starfold.universe;

import static org.junit.jupiter.api.Assertions.*;
import dev.starfold.worldgen.PlanetTerrain;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Planet types come from data; a planet made from one retains its decoded values. */
class PlanetTypeTest extends PlanetTypeFixtures {
    @Test void allBuiltinTypesUseStrictRecipe() {
        for(String name:java.util.List.of("rocky","desert","ice","temperate","lush","arid","archipelago","steppe")) {
            var type=type(name);assertEquals(2,type.terrain().recipe().json().get("version").getAsInt());
            var planet=type.planet(42,Optional.empty());assertEquals(PlanetTerrain.VERSION,planet.version());
            var json=PlanetChunkGenerator.CODEC.codec().encodeStart(ops(),planet).getOrThrow();
            assertEquals(planet.generation(),PlanetChunkGenerator.CODEC.codec().parse(ops(),json).getOrThrow().generation());
        }
    }
}
