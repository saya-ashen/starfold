package dev.starfold.ground;

import java.util.*;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/** Initial image sends: stable new arrivals interleave with the rotating retry FIFO. */
final class ChunkSendQueue {
    private final ArrayDeque<Long> pending = new ArrayDeque<>();
    private final ArrayDeque<Long> arrivals = new ArrayDeque<>();
    private boolean arrivalTurn = true;

    void retain(List<Long> wanted, Set<Long> sent) {
        var remaining = new LinkedHashSet<>(wanted);
        remaining.removeAll(sent);
        pending.removeIf(image -> !remaining.remove(image));
        arrivals.removeIf(image -> !remaining.remove(image));
        // A window with no backlog starts in distance order; later arrivals get alternating
        // service.
        if (isEmpty()) pending.addAll(remaining);
        else arrivals.addAll(remaining);
    }

    int size() {
        return pending.size() + arrivals.size();
    }

    boolean isEmpty() {
        return pending.isEmpty() && arrivals.isEmpty();
    }

    /** One atomic attempt is allowed even after the deadline; failures do not extend that exception.
     * With both FIFOs busy, each receives at least every second attempt, even across one-slot ticks.
     * A new entry behind F arrivals waits at most 2(F+1) attempts; an old entry behind R retries
     * waits at most 2(R+1). New arrivals cannot push either bound back. */
    void drain(LongSupplier clock, long deadline, int limit, LongPredicate send) {
        int attempts = 0, retries = pending.size(), fresh = arrivals.size();
        while (retries + fresh > 0
                && attempts < limit
                && (attempts == 0 || clock.getAsLong() < deadline)) {
            boolean arrival = fresh > 0 && (arrivalTurn || retries == 0);
            long image;
            if (arrival) {
                image = arrivals.removeFirst();
                fresh--;
            } else {
                image = pending.removeFirst();
                retries--;
            }
            arrivalTurn = !arrival;
            attempts++;
            if (!send.test(image)) pending.addLast(image);
        }
    }
}
