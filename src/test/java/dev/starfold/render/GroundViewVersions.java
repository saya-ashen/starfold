package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.planet.PlanetStorage;

import java.util.*;

/** In-memory invalidation stamps; source chunks remain authoritative. Reset on server stop. */
public final class GroundViewVersions {
    private final Map<Long, Long> versions = new HashMap<>();
    private long revision;

    public long get(long key) {
        return versions.getOrDefault(key, 0L);
    }

    public void changed(long key) {
        if (key >= 0 && key < (long) PlanetShape.count() * PlanetStorage.CHUNKS_PER_PLANET)
            versions.put(key, ++revision);
    }
}
