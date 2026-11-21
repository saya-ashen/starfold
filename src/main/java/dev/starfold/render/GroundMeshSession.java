package dev.starfold.render;

import dev.starfold.api.FarViewDiagnostics.Kind;
import dev.starfold.render.GroundRenderer.Bundle;
import dev.starfold.render.GroundRenderer.NodeMesh;
import dev.starfold.render.GroundRenderer.Pending;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns the work and GPU allocations tied to the current terrain mesh generation. */
final class GroundMeshSession {
    final GroundQuadtreeScheduler<NodeMesh> scheduler;
    final GroundMipCache inputs = new GroundMipCache();
    final ArrayDeque<Pending> pendingUploads = new ArrayDeque<>();
    final Map<Integer, Bundle> bundles = new HashMap<>();
    final Map<GroundQuadtreeScheduler.Ticket, AtomicBoolean> buildPermits = new HashMap<>();
    // Fixed capacity and primitive IDs keep disabled diagnostics allocation-free per job.
    final int[] inFlightNodes;
    final Map<Integer, Long> watchedRequests = new HashMap<>();
    GroundFaceArenas arenas = new GroundFaceArenas();
    GroundLodLifecycleDiagnostics.ReadyBytes readyBytes =
            new GroundLodLifecycleDiagnostics.ReadyBytes();
    AtomicLong packingBytes = new AtomicLong();
    int nextHandle;
    // Only the render thread advances the generation. Workers may read it during admission.
    volatile long generation;

    GroundMeshSession(int threads) {
        scheduler = new GroundQuadtreeScheduler<>(threads);
        inFlightNodes = new int[scheduler.nodeCapacity()];
    }

    void invalidate() {
        generation++;
    }

    void clear(long epoch, long frame, BitSet nativeTiles) {
        if (epoch != 0)
            for (int encoded : inFlightNodes)
                if (encoded != 0)
                    GroundQuadtreeDiagnostics.nodeEvent(
                            epoch,
                            encoded - 1,
                            -1,
                            Kind.CANCEL,
                            "renderer-clear-worker",
                            frame,
                            false,
                            nativeTiles);
        for (var permit : buildPermits.values()) permit.set(false);
        buildPermits.clear();
        scheduler.reset();
        // Late CPU payloads release against the counters captured at admission.
        readyBytes = new GroundLodLifecycleDiagnostics.ReadyBytes();
        packingBytes = new AtomicLong();
        for (var pending : pendingUploads) {
            report(epoch, frame, nativeTiles, pending.ticket, Kind.CANCEL);
            pending.close();
        }
        pendingUploads.clear();
        for (var bundle : bundles.values()) {
            report(epoch, frame, nativeTiles, bundle.ticket(), Kind.RETIRE);
            bundle.close();
        }
        bundles.clear();
        watchedRequests.clear();
        Arrays.fill(inFlightNodes, 0);
        inputs.clear();
        arenas.close();
        arenas = new GroundFaceArenas();
    }

    private static void report(
            long epoch, long frame, BitSet nativeTiles, GroundQuadtreeScheduler.Ticket ticket,
            Kind kind) {
        if (epoch != 0)
            GroundQuadtreeDiagnostics.nodeEvent(
                    epoch, ticket.node(), ticket.step(), kind, "renderer-clear",
                    frame, false, nativeTiles);
    }
}
