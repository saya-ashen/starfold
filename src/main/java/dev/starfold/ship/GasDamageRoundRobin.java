package dev.starfold.ship;

import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/** Bounded service order over a distinct UUID-sorted cohort, independent of caller iteration order. */
final class GasDamageRoundRobin {
    static final int MAX_HULLS = 16;

    record Batch(int cohort, int start, int size) {
        int index(int visit) {
            if (visit < 0 || visit >= size) throw new IllegalArgumentException("Gas hull visit");
            return (int) (((long) start + visit) % cohort);
        }
    }

    /** UUID-only O(n log n) normalization; no pose/chunk/geometry work outside the bounded batch. */
    static <T> List<T> ordered(List<T> cohort, Function<T, UUID> identity) {
        var sorted = new TreeMap<UUID, T>();
        for (var member : cohort) {
            UUID uuid = identity.apply(member);
            if (uuid == null || sorted.putIfAbsent(uuid, member) != null)
                throw new IllegalArgumentException("Duplicate/unknown gas hull identity");
        }
        return List.copyOf(sorted.values());
    }

    private int next;
    private Batch active;

    Batch begin(int cohort) {
        if (cohort < 0 || active != null) throw new IllegalStateException("Gas hull batch");
        next = cohort == 0 ? 0 : next % cohort;
        active = new Batch(cohort, next, Math.min(cohort, MAX_HULLS));
        return active;
    }

    void finish(Batch batch, int lastSuccessVisit) {
        if (batch != active || lastSuccessVisit < -1 || lastSuccessVisit >= batch.size())
            throw new IllegalArgumentException("Gas hull batch completion");
        next =
                batch.size() == 0
                        ? 0
                        : (int)
                                (((long) batch.start()
                                                + (lastSuccessVisit < 0
                                                        ? batch.size()
                                                        : lastSuccessVisit + 1))
                                        % batch.cohort());
        active = null;
    }
}
