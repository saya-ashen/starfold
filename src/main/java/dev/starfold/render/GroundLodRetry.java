package dev.starfold.render;

/** Canonical-key retry backoff: transient failures retain demand without a hot retry loop. */
final class GroundLodRetry {
    private final long[] after = new long[98_304];
    private final byte[] failures = new byte[98_304];

    boolean ready(long key, long now) {
        return failures[(int) key] == 0 || now >= after[(int) key];
    }

    void failed(long key, long now) {
        int slot = (int) key;
        int count = Math.min(8, failures[slot] + 1);
        failures[slot] = (byte) count;
        after[slot] = now + Math.min(30_000_000_000L, 250_000_000L << (count - 1));
    }

    void succeeded(long key) {
        failures[(int) key] = 0;
        after[(int) key] = 0;
    }
}
