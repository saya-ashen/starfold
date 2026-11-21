package dev.starfold.worldgen.surface;

import dev.starfold.worldgen.PlanetTerrain;

/** Identity shared by voxel memory and disk caches, including resolved scatter and plan policies. */
public record SurfaceKey(long seed, String type, int size, String algorithmVersion) {
    public SurfaceKey {
        if (type == null
                || type.isBlank()
                || algorithmVersion == null
                || algorithmVersion.isBlank()
                || size < 16
                || size > 2048
                || size % 16 != 0) throw new IllegalArgumentException("Cache key");
    }

    public static SurfaceKey of(PlanetTerrain t) {
        // Include the resolved policy: changing a datapack without renaming its type invalidates
        // the cache too.
        return new SurfaceKey(
                t.seed(),
                t.generation().type(),
                t.shape().size(),
                "surface-cache-2/terrain-"
                        + t.version()
                        + "/"
                        + t.generation()
                        + "/surface-"
                        + dev.starfold.worldgen.PlanetGeneration.SURFACE_VERSION
                        + "/roles-"
                        // biomeIds is a set with JVM-randomized iteration order. Canonicalize
                        // only its identity text; biome rule order remains significant.
                        + t.generation().recipe().biomeIds().stream()
                                .map(Object::toString)
                                .sorted()
                                .toList()
                        + "/scatter-"
                        + (t.scatter() == null ? "native" : t.scatter().identity())
                        + "/plan-"
                        + t.plan().identity());
    }
}
