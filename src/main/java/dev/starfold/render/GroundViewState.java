package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import java.util.*;

/** Canonical surface data and invalidations for one bound observation. No IO or GL. */
final class GroundViewState<W> {
    W level;
    long session;
    int planet = -1;

    boolean sameWorld(W world, int planet, PlanetShape shape) {
        return level == world && this.planet == planet && this.shape.equals(shape);
    }

    boolean matches(W world, long session, int planet, PlanetShape shape) {
        return this.session == session && sameWorld(world, planet, shape);
    }

    void bind(W world, long session, int planet, PlanetShape shape) {
        level = world;
        this.session = session;
        this.planet = planet;
        this.shape = shape;
    }

    PlanetShape shape = PlanetShape.STANDARD;
    final BitSet changed = new BitSet();
    final Map<Long, GroundSurface> overlays = new HashMap<>();
    final Map<Long, Long> surfaceRevisions = new HashMap<>();
    final GroundInvalidations invalidated = new GroundInvalidations();
    final Map<Long, GroundSurface> surfaces =
            new GroundSurfaceCache(key -> invalidated.surface(shape, key), true);

    boolean surface(long key, long revision, GroundSurface image) {
        if (surfaceRevisions.getOrDefault(key, -1L) >= revision) return false;
        surfaceRevisions.put(key, revision);
        if (image == null) {
            overlays.remove(key);
            surfaces.remove(key);
        } else {
            overlays.put(key, image);
            surfaces.put(key, image);
        }
        invalidated.surface(shape, key);
        return true;
    }

    void loaded(Map<Long, GroundSurface> images) {
        images.forEach((key, image) -> {
            if (changed.get(key.intValue())) surface(key, image.revision(), image);
        });
    }

    boolean coverage(BitSet next, long revision) {
        var difference = (BitSet) changed.clone();
        difference.xor(next);
        changed.clear();
        changed.or(next);
        boolean removed = false;
        for (int key = difference.nextSetBit(0); key >= 0; key = difference.nextSetBit(key + 1)) {
            if (!next.get(key)) {
                overlays.remove((long) key);
                surfaces.remove((long) key);
                surfaceRevisions.merge((long) key, revision, Math::max);
                removed = true;
            }
            invalidated.surface(shape, key);
        }
        return removed;
    }

    void clear() {
        level = null;
        session = 0;
        planet = -1;
        changed.clear();
        surfaces.clear();
        overlays.clear();
        surfaceRevisions.clear();
        invalidated.clear();
    }
}
