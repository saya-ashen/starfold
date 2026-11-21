package dev.starfold.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Soft, on-demand cache allowance; never reserve disk blocks or consume the safety margin. */
final class GroundDiskBudget {
    static final long GiB = 1L << 30;

    static long allowance(long total, long usable, long cached) {
        long reserve = Math.max(8 * GiB, total / 20);
        return Math.min(512 * GiB, Math.max(0, usable - reserve + cached) / 2);
    }

    static long allowance(Path existing, long cached) throws IOException {
        var store = Files.getFileStore(existing);
        return allowance(store.getTotalSpace(), store.getUsableSpace(), cached);
    }

    static boolean writable(Path existing) throws IOException {
        var store = Files.getFileStore(existing);
        return store.getUsableSpace() > Math.max(8 * GiB, store.getTotalSpace() / 20);
    }
}
