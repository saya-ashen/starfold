package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

import java.util.*;

/** Distance-first frontier: no planet-sized sort or scan on the server tick. */
final class GroundSurfaceStream {
    static final int PROBES = 16;

    private record Candidate(long key, double distance) {}

    private final PriorityQueue<Candidate> queue =
            new PriorityQueue<>(
                    Comparator.comparingDouble(Candidate::distance)
                            .thenComparingLong(Candidate::key));
    private final BitSet visited = new BitSet(), queued = new BitSet();
    private Point eye;
    private PlanetShape shape;
    private long revision = -1;

    private void offer(long key) {
        if (queued.get((int) key)) return;
        int x = (int) (key % 128) * 16 + 8, z = (int) (key / 128 % 128) * 16 + 8;
        var delta =
                shape.direction(Face.VALUES[(int) (key / 16384)], x, z)
                        .scale(shape.radius())
                        .sub(eye);
        queue.add(new Candidate(key, delta.dot(delta)));
        queued.set((int) key);
    }

    void retry(long key) {
        offer(key);
    }

    private void reset(PlanetShape nextShape, Point nextEye) {
        shape = nextShape;
        eye = nextEye;
        queue.clear();
        visited.clear();
        queued.clear();
        // Seed every face close to the eye's projected direction, including across a seam.
        for (var face : Face.VALUES) {
            double normal = Math.max(shape.half(), eye.dot(face.normal));
            int x =
                    (int)
                            Math.clamp(
                                    (shape.half() + eye.dot(face.u) * shape.half() / normal) / 16,
                                    0,
                                    shape.chunks() - 1);
            int z =
                    (int)
                            Math.clamp(
                                    (shape.half() + eye.dot(face.v) * shape.half() / normal) / 16,
                                    0,
                                    shape.chunks() - 1);
            offer(face.ordinal() * 16384L + x + z * 128L);
        }
    }

    long next(GroundSurfaceIndex index, Map<Long, Long> sent, PlanetShape shape, Point eye) {
        if (!index.ready()) return -1;
        // Radial movement cannot change ordering on the nominal sphere. In particular a
        // distant orbiting camera must not restart its frontier on every small translation.
        if (this.eye == null
                || !shape.equals(this.shape)
                || this.eye
                                        .scale(1 / Math.max(1, this.eye.length()))
                                        .sub(eye.scale(1 / Math.max(1, eye.length())))
                                        .length()
                                * shape.radius()
                        >= 512
                || queue.isEmpty()) reset(shape, eye);
        // A queued generated tile may become ready after the frontier has already passed it.
        // Replay recent commits, not all keys; new viewers instead walk the distance frontier.
        if (revision >= 0) index.changesSince(revision, this::offer);
        revision = index.revision();
        for (int probe = 0; probe < PROBES && !queue.isEmpty(); probe++) {
            long key = queue.remove().key;
            queued.clear((int) key);
            if (!visited.get((int) key)) {
                visited.set((int) key);
                for (long neighbour : GroundStreaming.neighbours(shape, key))
                    if (!visited.get((int) neighbour)) offer(neighbour);
            }
            long version = index.surfaceRevision(key);
            if (version >= 0 && !index.dirty(key) && sent.getOrDefault(key, -1L) != version
                    && (index.hasSurface(key) || sent.containsKey(key))) return key;
        }
        return -1;
    }
}
