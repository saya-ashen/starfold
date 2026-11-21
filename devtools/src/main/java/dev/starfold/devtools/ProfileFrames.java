package dev.starfold.devtools;

import dev.starfold.api.RenderWorkDiagnostics;

/** Shared production frame lifecycle, independent of GL and export I/O. Render-thread only. */
final class ProfileFrames {
    private final Capture capture;
    private boolean rendering, stopped;

    ProfileFrames(Capture capture) {
        this.capture = capture;
        RenderWorkDiagnostics.register(capture::recordWork);
    }

    void open(long now) {
        if (stopped || capture.full()) return;
        capture.open(now);
        rendering = true;
        RenderWorkDiagnostics.beginFrame();
        begin(Capture.FRAME, now);
    }

    void begin(int section, long now) {
        if (rendering) capture.begin(section, now);
    }

    void beginCpu(int section, long now) {
        if (rendering) capture.beginCpu(section, now);
    }

    void end(int section, long now) {
        if (rendering) capture.end(section, now);
    }

    void frameEnd(long now) {
        if (!rendering) return;
        rendering = false;
        try {
            capture.end(Capture.FRAME, now);
        } finally {
            RenderWorkDiagnostics.endFrame();
        }
    }

    void stop(long now) {
        try {
            frameEnd(now);
        } finally {
            stopped = true;
            RenderWorkDiagnostics.register(null);
        }
    }

    void boundary(long now) {
        // Exception/abandonment fallback. A normally delivered fine row cannot be copied twice.
        frameEnd(now);
        capture.boundary(now);
        if (capture.full()) stop(now);
    }
}
