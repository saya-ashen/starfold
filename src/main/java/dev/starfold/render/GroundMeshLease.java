package dev.starfold.render;

/** A cancelled mesh keeps its native buffers until its active writer has left. */
final class GroundMeshLease implements AutoCloseable {
    private final Runnable release;
    private boolean closed, writing;

    GroundMeshLease(Runnable release) { this.release = release; }

    synchronized boolean begin() {
        if (closed) return false;
        writing = true;
        return true;
    }

    synchronized void end() {
        writing = false;
        if (closed) release.run();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (!writing) release.run();
    }
}
