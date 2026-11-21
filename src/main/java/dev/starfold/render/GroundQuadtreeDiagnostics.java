package dev.starfold.render;

import static dev.starfold.api.RenderWorkDiagnostics.Metric.*;

import dev.starfold.api.FarViewDiagnostics;
import dev.starfold.api.FarViewDiagnostics.Kind;
import dev.starfold.api.FarViewDiagnostics.Source;
import dev.starfold.api.FarViewDiagnostics.Versions;
import dev.starfold.api.RenderWorkDiagnostics;
import dev.starfold.storage.ChunkKeys;

import java.util.BitSet;
import java.util.Map;

/** Reports quadtree events and draw metrics without owning renderer state. */
final class GroundQuadtreeDiagnostics {
    static boolean containsTile(int node, int face, int x, int z) {
        return GroundQuadtree.face(node) == face
                && x >= GroundQuadtree.x(node)
                && z >= GroundQuadtree.z(node)
                && x < GroundQuadtree.x(node) + GroundQuadtree.size(node)
                && z < GroundQuadtree.z(node) + GroundQuadtree.size(node);
    }

    static void nodeEvent(
            long epoch,
            int node,
            int step,
            Kind kind,
            String reason,
            long frameWork,
            boolean translucentPass,
            BitSet nativeTiles) {
        var w = FarViewDiagnostics.currentWatch(epoch);
        if (w == null || w.face() != GroundQuadtree.face(node)) return;
        int minX = Math.max(GroundQuadtree.x(node), Math.max(0, w.x() - w.radius() * 16));
        int minZ = Math.max(GroundQuadtree.z(node), Math.max(0, w.z() - w.radius() * 16));
        int maxX =
                Math.min(
                        GroundQuadtree.x(node) + GroundQuadtree.size(node) - 16,
                        Math.min(2032, w.x() + w.radius() * 16));
        int maxZ =
                Math.min(
                        GroundQuadtree.z(node) + GroundQuadtree.size(node) - 16,
                        Math.min(2032, w.z() + w.radius() * 16));
        if (minX > maxX || minZ > maxZ) return;
        String detail =
                reason
                        + ";node="
                        + node
                        + ";pass="
                        + (kind == Kind.DRAW
                                ? (translucentPass ? "translucent" : "opaque")
                                : "lifecycle");
        var versions = new Versions(-1, -1, -1, frameWork, -1, -1);
        for (int z = minZ; z <= maxZ; z += 16)
            for (int x = minX; x <= maxX; x += 16) {
                // The node batch may be submitted while this tile is masked for native ownership.
                if (kind == Kind.DRAW
                        && nativeTiles.get((int) ChunkKeys.faceLocal(w.face(), x / 16, z / 16)))
                    continue;
                FarViewDiagnostics.observeEvent(
                        epoch, w.face(), x, z, Source.CHART, kind, detail, step, versions);
            }
    }

    static void nodeRecord(
            long epoch,
            GroundQuadtreeScheduler.Ticket ticket,
            String stage,
            long nanos,
            long bytes,
            int planet) {
        var w = FarViewDiagnostics.currentWatch(epoch);
        if (w == null || w.face() != GroundQuadtree.face(ticket.node())) return;
        int x = Math.max(GroundQuadtree.x(ticket.node()), Math.max(0, w.x() - 16 * w.radius()));
        int z = Math.max(GroundQuadtree.z(ticket.node()), Math.max(0, w.z() - 16 * w.radius()));
        if (x > w.x() + 16 * w.radius()
                || z > w.z() + 16 * w.radius()
                || !containsTile(ticket.node(), w.face(), x, z)) return;
        FarViewDiagnostics.record(
                epoch,
                planet,
                w.face(),
                x,
                z,
                stage,
                Map.of(
                        "node",
                        ticket.node(),
                        "step",
                        ticket.step(),
                        "targetVersion",
                        ticket.version(),
                        "session",
                        ticket.session(),
                        "cpuNanos",
                        nanos,
                        "payloadBytes",
                        bytes));
    }

    static void recordIndexDimensions(int face, int step, long indices) {
        var faceMetric =
                switch (face) {
                    case 0 -> FAR_INDICES_FACE_0;
                    case 1 -> FAR_INDICES_FACE_1;
                    case 2 -> FAR_INDICES_FACE_2;
                    case 3 -> FAR_INDICES_FACE_3;
                    case 4 -> FAR_INDICES_FACE_4;
                    default -> FAR_INDICES_FACE_5;
                };
        var stepMetric =
                switch (step) {
                    case 1 -> FAR_INDICES_STEP_1;
                    case 2 -> FAR_INDICES_STEP_2;
                    case 4 -> FAR_INDICES_STEP_4;
                    case 8 -> FAR_INDICES_STEP_8;
                    default -> FAR_INDICES_STEP_OTHER;
                };
        RenderWorkDiagnostics.add(faceMetric, indices);
        RenderWorkDiagnostics.add(stepMetric, indices);
    }

    private GroundQuadtreeDiagnostics() {}
}
