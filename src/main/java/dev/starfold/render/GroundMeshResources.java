package dev.starfold.render;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Reload waits for native atlas readers before Minecraft closes their images. */
public final class GroundMeshResources {
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private static volatile long generation;
    private static volatile boolean reloading;

    static long generation() { return generation; }
    static boolean current(long captured) { return !reloading && captured == generation; }

    static void read(long captured, Runnable work) {
        lock.readLock().lock();
        try {
            if (current(captured)) work.run();
        } finally {
            lock.readLock().unlock();
        }
    }

    public static void beginReload() {
        invalidate();
        GroundRenderer.clear();
    }

    static void invalidate() {
        lock.writeLock().lock();
        try {
            reloading = true;
            generation++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    static void endReload() { reloading = false; }

    private GroundMeshResources() {}
}
