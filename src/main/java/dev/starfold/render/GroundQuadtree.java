package dev.starfold.render;

import dev.starfold.chart.PlanetShape;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;

/**
 * Render-thread-owned, fixed six-face quadtree. No GL or worker access is required.
 *
 * <p>Integration: updateTargets at AFTER_SKY, build (node, desiredStep, version) off-thread,
 * then publish only after upload succeeds. Call selectCut separately with each pass's ACTUALLY
 * drawn native tiles. A returned Cut is caller-owned and reusable. Native tile indices are
 * face*16384 + tileZ*128 + tileX; root patch indices retain face*64 + py*8 + px.
 * Roots must be bootstrapped before remote coverage is withdrawn: missingCount explicitly
 * reports branches with no fallback. Dirty resident meshes remain drawable until replacement.
 * minY/maxY enclose drawable resident meshes; targetMinY/targetMaxY also cover unknown terrain
 * and must be used for updateTargets/visitDemand. A coarse mesh is not proof of fine terrain bounds.
 * Relieve budget pressure before either pass selects its cut. selectCut pins its ranges for the
 * shared frame, even if the other pass selects a different cut. Release callbacks run synchronously;
 * callers must retire GPU work from earlier frames (and replaced meshes) before reusing storage.
 * All methods are confined to one render thread.
 */
public final class GroundQuadtree {
    public static final int FACES = 6, DEPTHS = 5, NODES_PER_FACE = 21824;
    public static final int NODE_COUNT = FACES * NODES_PER_FACE, ROOT_COUNT = 384;
    private static final int TILES_PER_FACE = 128 * 128, TILE_COUNT = FACES * TILES_PER_FACE;
    public static final double SPLIT_SSE = 3.0, MERGE_SSE = 2.4;
    public static final long DEFAULT_DETAIL_BUDGET = GroundViewPolicy.DETAIL_BYTES;
    private static final int[] BASE = {0, 64, 320, 1344, 5440};
    private static final int[] COARSE = {64, 16, 8, 4, 2};
    private static final int[] FINE = {32, 16, 8, 2, 1};

    private final PlanetShape shape;
    private final byte[] depth = new byte[NODE_COUNT], desired = new byte[NODE_COUNT], built = new byte[NODE_COUNT];
    private final boolean[] valid = new boolean[NODE_COUNT], split = new boolean[NODE_COUNT], dirty = new boolean[NODE_COUNT];
    private final int[] handles = new int[NODE_COUNT], minY = new int[NODE_COUNT], maxY = new int[NODE_COUNT];
    private final long[] versions = new long[NODE_COUNT], bytes = new long[NODE_COUNT], lastUsed = new long[NODE_COUNT];
    private final int initialMinY, initialMaxY;
    private final int[] meshMinY = new int[NODE_COUNT], meshMaxY = new int[NODE_COUNT];
    private final int[] pendingMinY = new int[NODE_COUNT], pendingMaxY = new int[NODE_COUNT];
    private final int[] targetMinY = new int[NODE_COUNT], targetMaxY = new int[NODE_COUNT];
    // Fine terrain evidence outlives its GPU LOD; a downgrade must not restore unknown heights.
    private final int[] fineMinY = new int[NODE_COUNT], fineMaxY = new int[NODE_COUNT];
    private final boolean[] hasResident = new boolean[NODE_COUNT], targetVisible = new boolean[NODE_COUNT];
    private final boolean[] targetNear = new boolean[NODE_COUNT];
    // Cost of falling back to this node's resident mesh; unknown targets sort last.
    private final double[] fallbackSse = new double[NODE_COUNT];
    // A pressure collapse is a target decision, not merely a cache miss. Do not immediately
    // regenerate the same children; reconsider when their projected error grows materially.
    // Negative means arm from the next target evaluation, AFTER released bounds have expanded.
    private final double[] pressureSse = new double[NODE_COUNT];
    private final long[] pressureCost = new long[NODE_COUNT], retryView = new long[NODE_COUNT], retrySpace = new long[NODE_COUNT];
    // Scratch is reused across both passes. 0=missing, 1=self, 2=children, 3=native/outside.
    private final byte[] choice = new byte[NODE_COUNT];
    private final int[] nativePrefix = new int[FACES * 129 * 129];
    private final BitSet preparedNative = new BitSet(FACES * 16384);
    private long residentBytes, detailBytes, cutRevision;
    private final BitSet targetRoots = new BitSet(ROOT_COUNT);
    private final BitSet unrefreshedViewRoots = new BitSet(ROOT_COUNT);
    private int targetCursor;
    private final long[] rootRevision = new long[ROOT_COUNT], resolvedRevision = new long[ROOT_COUNT];
    private long resolvedView = Long.MIN_VALUE;
    private final BitSet resolvedNative = new BitSet(TILE_COUNT);
    private final BitSet changedNative = new BitSet(TILE_COUNT);

    private static int patchOf(int node) {
        return face(node) * 64 + (z(node) / 256) * 8 + x(node) / 256;
    }

    /** Same tie order as emitting roots and then visiting each root's four quadrants. */
    static int drawOrder(int node) {
        int x = x(node) / 16, z = z(node) / 16;
        int order = 0;
        for (int bit = 3; bit >= 0; bit--)
            order = (order << 2) | ((z >> bit & 1) << 1) | (x >> bit & 1);
        return (face(node) * 64 + (z >> 4) * 8 + (x >> 4)) * 256 + order;
    }

    private void changed(int node) {
        int patch = patchOf(node);
        rootRevision[patch]++;
        targetRoots.set(patch);
    }
    public boolean hasTargetUpdates() { return !targetRoots.isEmpty(); }

    /** Uses the full packed vertex height range, including water offsets and skirts. */
    public GroundQuadtree(PlanetShape shape) { this(shape, -2048, 2048); }

    /** Bounds must include all possible terrain, vegetation, fluid and boundary skirts until tightened. */
    public GroundQuadtree(PlanetShape shape, int initialMinY, int initialMaxY) {
        this.shape = Objects.requireNonNull(shape);
        targetRoots.set(0, ROOT_COUNT);
        Arrays.fill(resolvedRevision, -1);
        if (initialMinY > initialMaxY) throw new IllegalArgumentException("height bounds");
        this.initialMinY = initialMinY;
        this.initialMaxY = initialMaxY;
        Arrays.fill(pendingMinY, Integer.MAX_VALUE);
        Arrays.fill(pendingMaxY, Integer.MIN_VALUE);
        Arrays.fill(targetMinY, initialMinY);
        Arrays.fill(targetMaxY, initialMaxY);
        Arrays.fill(fineMinY, Integer.MAX_VALUE);
        Arrays.fill(fineMaxY, Integer.MIN_VALUE);
        Arrays.fill(fallbackSse, Double.POSITIVE_INFINITY);
        Arrays.fill(handles, -1);
        Arrays.fill(lastUsed, Long.MIN_VALUE);
        Arrays.fill(retryView, Long.MIN_VALUE);
        Arrays.fill(minY, initialMinY);
        Arrays.fill(maxY, initialMaxY);
        for (int f = 0; f < FACES; f++) for (int d = 0; d < DEPTHS; d++) {
            int width = 8 << d;
            for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
                int id = nodeId(f, d, x, z);
                depth[id] = (byte) d;
                desired[id] = (byte) COARSE[d];
                valid[id] = x * (256 >> d) < shape.size() && z * (256 >> d) < shape.size();
                dirty[id] = valid[id];
            }
        }
    }

    public PlanetShape shape() { return shape; }
    public static int nodeId(int face, int depth, int nx, int nz) {
        if (face < 0 || face >= FACES || depth < 0 || depth >= DEPTHS
                || nx < 0 || nz < 0 || nx >= (8 << depth) || nz >= (8 << depth))
            throw new IllegalArgumentException("node coordinates");
        return face * NODES_PER_FACE + BASE[depth] + (nz << (depth + 3)) + nx;
    }
    public static int face(int node) { check(node); return node / NODES_PER_FACE; }
    public static int depth(int node) {
        check(node);
        int local = node % NODES_PER_FACE;
        for (int d = 4; d > 0; d--) if (local >= BASE[d]) return d;
        return 0;
    }
    public static int nx(int node) { int d = depth(node); return (node % NODES_PER_FACE - BASE[d]) & ((8 << d) - 1); }
    public static int nz(int node) { int d = depth(node); return (node % NODES_PER_FACE - BASE[d]) >> (d + 3); }
    public static int size(int node) { return 256 >> depth(node); }
    public static int x(int node) { return nx(node) * size(node); }
    public static int z(int node) { return nz(node) * size(node); }
    public static int parent(int node) {
        int d = depth(node);
        return d == 0 ? -1 : nodeId(face(node), d - 1, nx(node) >> 1, nz(node) >> 1);
    }
    public static int child(int node, int quadrant) {
        int d = depth(node);
        if (d == 4 || quadrant < 0 || quadrant > 3) throw new IllegalArgumentException("child");
        return nodeId(face(node), d + 1, nx(node) * 2 + (quadrant & 1), nz(node) * 2 + (quadrant >> 1));
    }
    public static int root(int patch) {
        if (patch < 0 || patch >= ROOT_COUNT) throw new IllegalArgumentException("patch");
        return nodeId(patch / 64, 0, patch & 7, (patch % 64) >> 3);
    }
    public static int tile(int face, int tx, int tz) { return nodeId(face, 4, tx, tz) - face * NODES_PER_FACE - BASE[4] + face * 16384; }
    private static void check(int node) { if (node < 0 || node >= NODE_COUNT) throw new IllegalArgumentException("node"); }
    public boolean valid(int node) { check(node); return valid[node]; }
    public int desiredStep(int node) { check(node); return desired[node]; }
    public int builtStep(int node) { check(node); return built[node]; }
    public int meshHandle(int node) { check(node); return handles[node]; }
    public long version(int node) { check(node); return versions[node]; }
    public boolean dirty(int node) { check(node); return dirty[node]; }
    public boolean wantsSplit(int node) { check(node); return split[node]; }
    /** Drawable envelope: own mesh, ALL resident descendants, and edits to those meshes.
     * Without any resident mesh, returns initial bounds expanded by pending edits.
     * Safe for selectCut visibility; never use this envelope to exclude unbuilt target terrain. */
    public int minY(int node) { check(node); return minY[node]; }
    /** Upper end of the drawable envelope; see {@link #minY(int)}. */
    public int maxY(int node) { check(node); return maxY[node]; }
    /** Target envelope: leaves without verified fine terrain retain initial bounds, plus pending edits.
     * Only clean step-1 leaf meshes certify terrain bounds; a coarse publication cannot certify
     * unseen detail or discard earlier fine evidence. Also encloses all resident meshes. */
    public int targetMinY(int node) { check(node); return targetMinY[node]; }
    /** Upper end of the target envelope; see {@link #targetMinY(int)}. */
    public int targetMaxY(int node) { check(node); return targetMaxY[node]; }
    public long residentBytes() { return residentBytes; }
    public long detailBytes() { return detailBytes; }
    public long cutRevision() { return cutRevision; }

    /** Cached submissions still protect every selected range in both passes this frame. */
    public void markUsed(Cut cut, long frame) {
        for (int i = 0; i < cut.pureCount; i++) lastUsed[cut.pure[i]] = frame;
        for (int i = 0; i < cut.maskedCount; i++) lastUsed[cut.masked[i]] = frame;
    }

    /** Direct target override for schedulers/tests. Allowed steps follow the design's depth table. */
    public void target(int node, int step, boolean subdivide) {
        check(node);
        if (!allowed(depth[node], step) || subdivide && depth[node] == 4) throw new IllegalArgumentException("target");
        // A step request affects build tickets. Drawing keeps the resident mesh until publication.
        if (desired[node] != step) { desired[node] = (byte) step; versions[node]++; }
        if (split[node] != subdivide) {
            split[node] = subdivide;
            cutRevision++;
            changed(node);
        }
    }
    private static boolean allowed(int d, int s) { return s == COARSE[d] || s == FINE[d]; }

    public static double sse(int step, double nearestDistance, int windowHeight, double fovYRadians) {
        if (step <= 0 || windowHeight <= 0 || !(fovYRadians > 0 && fovYRadians < Math.PI)
                || Double.isNaN(nearestDistance)) throw new IllegalArgumentException("SSE inputs");
        return step * (double) windowHeight / (2 * Math.max(1, nearestDistance) * Math.tan(fovYRadians / 2));
    }

    /**
     * Distance and visible must use targetMinY/targetMaxY, projected into view space, in block
     * units. minY/maxY are only for drawing already resident meshes. nearIntersection
     * must test branch rectangles (not only leaves); it refines native branches to depth four
     * unless budget pressure requires retaining a masked coarse fallback.
     * Invisible branches retain targets and produce no demand through visitDemand.
     */
    public void updateTargets(int windowHeight, double fovYRadians, IntToDoubleFunction distance,
            IntPredicate visible, IntPredicate nearIntersection) {
        Objects.requireNonNull(distance); Objects.requireNonNull(visible); Objects.requireNonNull(nearIntersection);
        double scale = sse(1, 1, windowHeight, fovYRadians);
        Arrays.fill(targetVisible, false);
        for (int patch = 0; patch < ROOT_COUNT; patch++) updateTarget(root(patch), scale, distance, visible, nearIntersection);
        targetRoots.clear();
        unrefreshedViewRoots.clear();
    }

    /** Queue the latest view without restarting the round-robin position. */
    public void requestTargetRefresh() {
        targetRoots.set(0, ROOT_COUNT);
        unrefreshedViewRoots.set(0, ROOT_COUNT);
    }

    /** Same view/native inputs: only publications, edits and pressure collapses reopen a root. */
    public void updateChangedTargets(int windowHeight, double fovYRadians, IntToDoubleFunction distance,
            IntPredicate visible, IntPredicate nearIntersection) {
        updateChangedTargets(windowHeight, fovYRadians, distance, visible, nearIntersection, Long.MAX_VALUE);
    }

    /** Each root is atomic; even a zero budget makes progress with the latest supplied view. */
    public void updateChangedTargets(int windowHeight, double fovYRadians, IntToDoubleFunction distance,
            IntPredicate visible, IntPredicate nearIntersection, long budgetNanos) {
        if (budgetNanos < 0) throw new IllegalArgumentException("target budget");
        double scale = sse(1, 1, windowHeight, fovYRadians);
        long start = System.nanoTime();
        for (int visited = 0; visited < ROOT_COUNT && !targetRoots.isEmpty(); visited++) {
            int patch = targetRoots.nextSetBit(targetCursor);
            if (patch < 0) patch = targetRoots.nextSetBit(0);
            targetCursor = (patch + 1) % ROOT_COUNT;
            int root = root(patch);
            // Clear only this root's rows; unprocessed roots retain their previous complete targets.
            for (int d = 0; d < DEPTHS; d++) {
                int width = 1 << d, x = nx(root) * width, z = nz(root) * width;
                for (int row = 0; row < width; row++) {
                    int first = nodeId(face(root), d, x, z + row);
                    Arrays.fill(targetVisible, first, first + width, false);
                }
            }
            updateTarget(root, scale, distance, visible, nearIntersection);
            targetRoots.clear(patch);
            unrefreshedViewRoots.clear(patch);
            if (System.nanoTime() - start >= budgetNanos) break;
        }
    }
    /** Convenience form: forces refinement along every actually drawn native tile's ancestors. */
    public void updateTargets(int windowHeight, double fovYRadians, IntToDoubleFunction distance,
            IntPredicate visible, BitSet nativeTiles) {
        prepareNative(Objects.requireNonNull(nativeTiles));
        updateTargets(windowHeight, fovYRadians, distance, visible, n -> nativeCount(n) > 0);
    }
    private void updateTarget(int node, double scale, IntToDoubleFunction distance,
            IntPredicate visible, IntPredicate near) {
        if (!valid[node] || !visible.test(node)) return;
        int d = depth[node];
        double inverseDistance = scale / Math.max(1, distance.applyAsDouble(node));
        targetVisible[node] = true;
        fallbackSse[node] = (handles[node] >= 0 ? built[node] : desired[node]) * inverseDistance;
        double coarseError = COARSE[d] * inverseDistance;
        int step = desired[node];
        if (step == COARSE[d] && coarseError > SPLIT_SSE) step = FINE[d];
        else if (step == FINE[d] && coarseError < MERGE_SSE) step = COARSE[d];
        boolean divide = split[node];
        double fineError = FINE[d] * inverseDistance;
        if (divide && fineError < MERGE_SSE) divide = false;
        else if (!divide && fineError > SPLIT_SSE) divide = d < 4;
        boolean needsNative = d < 4 && near.test(node);
        // A NEW native requirement can retry detail; an unchanged one cannot defeat pressure.
        if (needsNative && !targetNear[node]) pressureSse[node] = 0;
        targetNear[node] = needsNative;
        if (pressureSse[node] < 0) pressureSse[node] = fallbackSse[node] * 1.25;
        if (pressureSse[node] > 0) {
            if (fallbackSse[node] <= pressureSse[node]) divide = false;
            else pressureSse[node] = 0;
        }
        if (needsNative && pressureSse[node] == 0) divide = true;
        // Bootstrap cheap global fallback before refining an absent root generation.
        if (d == 0 && handles[node] < 0) step = COARSE[0];
        target(node, step, divide);
        // Refine the drawable frontier, not every possible future leaf. Missing parents
        // must publish first; their children acquire fresh camera/native targets next frame.
        if (divide && handles[node] >= 0)
            for (int q = 0; q < 4; q++) updateTarget(child(node, q), scale, distance, visible, near);
    }

    /** Visits fallback roots first, then desired descendants. visible uses target bounds;
     * the consumer snapshots step/version. */
    public void visitDemand(IntPredicate visible, IntConsumer consumer) {
        Objects.requireNonNull(visible); Objects.requireNonNull(consumer);
        for (int patch = 0; patch < ROOT_COUNT; patch++) {
            int n = root(patch);
            if (valid[n] && visible.test(n) && (handles[n] < 0 || dirty[n] || built[n] != desired[n])) consumer.accept(n);
        }
        for (int patch = 0; patch < ROOT_COUNT; patch++) {
            if (unrefreshedViewRoots.get(patch)) continue;
            int n = root(patch);
            if (valid[n] && visible.test(n) && split[n] && handles[n] >= 0)
                for (int q = 0; q < 4; q++) demand(child(n, q), visible, consumer);
        }
    }
    private void demand(int node, IntPredicate visible, IntConsumer consumer) {
        if (!valid[node] || !visible.test(node)) return;
        if (handles[node] < 0 || dirty[node] || built[node] != desired[node]) consumer.accept(node);
        // Build a usable coverage frontier before spending workers on grandchildren that
        // cannot replace it yet. Targets still describe the final SSE/native requirement.
        if (split[node] && handles[node] >= 0)
            for (int q = 0; q < 4; q++) demand(child(node, q), visible, consumer);
    }

    /**
     * Atomically installs one uploaded generation. False leaves ownership with the caller, which
     * must free the rejected new handle. On success oldHandle is released exactly once. Heights
     * include every emitted vertex (skirts, fluids, vegetation). This replaces the node's actual
     * bounds and clears its pending edits. Drawable ancestor bounds are recomputed from all still
     * resident meshes, including dirty descendants; unknown target terrain remains conservative.
     */
    public boolean publish(int node, int step, long version, int newHandle, long meshBytes,
            int meshMinY, int meshMaxY, IntConsumer release) {
        check(node); Objects.requireNonNull(release);
        if (newHandle < 0 || meshBytes < 0 || meshMinY > meshMaxY || !allowed(depth[node], step))
            throw new IllegalArgumentException("mesh");
        if (!valid[node] || versions[node] != version || desired[node] != step) return false;
        int old = handles[node];
        if (old == newHandle) throw new IllegalArgumentException("replacement must use an independent handle");
        cutRevision++;
        changed(node);
        residentBytes += meshBytes - bytes[node];
        if (depth[node] != 0) detailBytes += meshBytes - bytes[node];
        handles[node] = newHandle; bytes[node] = meshBytes; built[node] = (byte) step; dirty[node] = false;
        this.meshMinY[node] = meshMinY; this.meshMaxY[node] = meshMaxY;
        if (depth[node] == 4 && step == 1) {
            fineMinY[node] = meshMinY; fineMaxY[node] = meshMaxY;
        }
        pendingMinY[node] = Integer.MAX_VALUE; pendingMaxY[node] = Integer.MIN_VALUE;
        refreshAncestors(node);
        if (old >= 0) release.accept(old);
        return true;
    }

    /** Invalidates all intersecting levels, cancels in-flight tickets, and expands bounds immediately. */
    public void invalidate(int face, int x0, int z0, int x1, int z1, int changedMinY, int changedMaxY) {
        if (face < 0 || face >= 6 || x1 <= x0 || z1 <= z0 || changedMinY > changedMaxY)
            throw new IllegalArgumentException("dirty region");
        cutRevision++;
        // Children first: resident descendants and unknown target leaves have distinct envelopes.
        for (int d = 4; d >= 0; d--) {
            int s = 256 >> d, width = 8 << d;
            int a = Math.max(0, Math.floorDiv(x0, s)), b = Math.min(width - 1, Math.floorDiv(x1 - 1, s));
            int c = Math.max(0, Math.floorDiv(z0, s)), e = Math.min(width - 1, Math.floorDiv(z1 - 1, s));
            for (int nz = c; nz <= e; nz++) for (int nx = a; nx <= b; nx++) {
                int n = nodeId(face, d, nx, nz);
                if (!valid[n]) continue;
                changed(n);
                dirty[n] = true; versions[n]++;
                pendingMinY[n] = Math.min(pendingMinY[n], changedMinY);
                pendingMaxY[n] = Math.max(pendingMaxY[n], changedMaxY);
                refreshBounds(n);
            }
        }
    }

    /** Coalesce one frame's canonical 16-block dirty tiles: each ancestor advances once. */
    public void invalidateTiles(BitSet tiles, int changedMinY, int changedMaxY) {
        Objects.requireNonNull(tiles);
        if (tiles.length() > TILE_COUNT || changedMinY > changedMaxY)
            throw new IllegalArgumentException("dirty tiles");
        invalidationNodes.clear();
        for (int tile = tiles.nextSetBit(0); tile >= 0; tile = tiles.nextSetBit(tile + 1)) {
            int face = tile / TILES_PER_FACE, u = tile % 128, v = tile % TILES_PER_FACE / 128;
            for (int d = 0; d <= 4; d++) {
                int n = nodeId(face, d, u >> (4 - d), v >> (4 - d));
                if (valid[n]) invalidationNodes.set(n);
            }
        }
        if (!invalidationNodes.isEmpty()) cutRevision++;
        for (int n = invalidationNodes.length() - 1; n >= 0;
                n = invalidationNodes.previousSetBit(n - 1)) {
            changed(n);
            dirty[n] = true;
            versions[n]++;
            pendingMinY[n] = Math.min(pendingMinY[n], changedMinY);
            pendingMaxY[n] = Math.max(pendingMaxY[n], changedMaxY);
            refreshBounds(n);
        }
    }

    private final BitSet invalidationNodes = new BitSet(NODE_COUNT);

    private void refreshAncestors(int node) {
        for (int n = node; n >= 0; n = parent(n)) refreshBounds(n);
    }
    private void refreshBounds(int node) {
        boolean resident = handles[node] >= 0;
        int lo = resident ? Math.min(meshMinY[node], pendingMinY[node]) : Integer.MAX_VALUE;
        int hi = resident ? Math.max(meshMaxY[node], pendingMaxY[node]) : Integer.MIN_VALUE;
        // Internal edit envelopes come from children and any resident own mesh. Keeping an
        // absent ancestor's pending edit here would prevent fully rebuilt fine leaves tightening it.
        int targetLo = Integer.MAX_VALUE, targetHi = Integer.MIN_VALUE;
        if (depth[node] < 4) {
            for (int q = 0; q < 4; q++) {
                int c = child(node, q);
                if (!valid[c]) continue;
                if (hasResident[c]) {
                    resident = true;
                    lo = Math.min(lo, minY[c]); hi = Math.max(hi, maxY[c]);
                }
                targetLo = Math.min(targetLo, targetMinY[c]);
                targetHi = Math.max(targetHi, targetMaxY[c]);
            }
        } else {
            if (dirty[node]) {
                fineMinY[node] = Integer.MAX_VALUE; fineMaxY[node] = Integer.MIN_VALUE;
            }
            targetLo = Math.min(pendingMinY[node], fineMinY[node]);
            targetHi = Math.max(pendingMaxY[node], fineMaxY[node]);
            if (fineMinY[node] > fineMaxY[node]) {
                targetLo = Math.min(targetLo, initialMinY); targetHi = Math.max(targetHi, initialMaxY);
            }
        }
        hasResident[node] = resident;
        minY[node] = resident ? lo : Math.min(initialMinY, pendingMinY[node]);
        maxY[node] = resident ? hi : Math.max(initialMaxY, pendingMaxY[node]);
        if (resident) {
            targetLo = Math.min(targetLo, minY[node]); targetHi = Math.max(targetHi, maxY[node]);
        }
        targetMinY[node] = targetLo; targetMaxY[node] = targetHi;
    }

    /** Reusable pass output. Only [0,count) entries are populated; no per-frame node objects. */
    public static final class Cut {
        public final int[] pure = new int[NODE_COUNT], masked = new int[NODE_COUNT], missing = new int[ROOT_COUNT];
        public int pureCount, maskedCount, missingCount;
        public void clear() { pureCount = maskedCount = missingCount = 0; }
        public boolean complete() { return missingCount == 0; }
    }

    /**
     * Produces an atomic disjoint cut. Masked entries are coarse nodes overlapping native tiles;
     * their shader MUST mask precisely the supplied native bitset. A leaf owned by native is
     * omitted. Missing roots stay explicit; culling never manufactures remote readiness.
     * visible may use the drawable minY/maxY envelope. Selection reserves every returned mesh
     * against eviction for this frame; discard/reselect does not unpin it. frame must be monotonic
     * and shared between the two passes. Evict before selection to allow immediate pressure relief.
     */
    public void selectCut(BitSet nativeTiles, IntPredicate visible, long frame, Cut output) {
        Objects.requireNonNull(nativeTiles); Objects.requireNonNull(visible); Objects.requireNonNull(output);
        Arrays.fill(resolvedRevision, -1);
        selectCut(nativeTiles, visible, frame, output, -frame - 1);
    }

    /** viewRevision certifies an unchanged conservative visibility predicate across frames. */
    public void selectCut(BitSet nativeTiles, IntPredicate visible, long frame, Cut output, long viewRevision) {
        Objects.requireNonNull(nativeTiles); Objects.requireNonNull(visible); Objects.requireNonNull(output);
        boolean nativeChanged = !resolvedNative.equals(nativeTiles);
        if (resolvedView != viewRevision) {
            Arrays.fill(resolvedRevision, -1);
            resolvedView = viewRevision;
        } else if (nativeChanged) {
            // The two passes can disagree on native tiles without changing any other root's cut.
            changedNative.clear();
            changedNative.or(resolvedNative);
            changedNative.xor(nativeTiles);
            for (int tile = changedNative.nextSetBit(0); tile >= 0 && tile < TILE_COUNT;
                    tile = changedNative.nextSetBit(tile + 1)) {
                int patch = (tile / 16384) * 64 + (tile / 2048 % 8) * 8 + (tile / 16 % 8);
                resolvedRevision[patch] = -1;
            }
        }
        if (nativeChanged) {
            resolvedNative.clear();
            resolvedNative.or(nativeTiles);
        }
        output.clear(); prepareNative(nativeTiles);
        for (int patch = 0; patch < ROOT_COUNT; patch++) {
            int node = root(patch);
            if (!valid[node] || !visible.test(node)) continue;
            if (resolvedRevision[patch] != rootRevision[patch]) {
                resolve(node, visible);
                resolvedRevision[patch] = rootRevision[patch];
            }
            if (choice[node] == 0) output.missing[output.missingCount++] = node;
            else emit(node, visible, frame, output);
        }
    }
    private void prepareNative(BitSet tiles) {
        if (preparedNative.equals(tiles)) return;
        preparedNative.clear();
        preparedNative.or(tiles);
        Arrays.fill(nativePrefix, 0);
        if (tiles.isEmpty()) return;
        for (int f = 0; f < FACES; f++) {
            int base = f * 129 * 129;
            for (int z = 0; z < shape.chunks(); z++) {
                int row = 0;
                for (int x = 0; x < shape.chunks(); x++) {
                    if (tiles.get(f * 16384 + z * 128 + x)) row++;
                    nativePrefix[base + (z + 1) * 129 + x + 1] = row + nativePrefix[base + z * 129 + x + 1];
                }
                for (int x = shape.chunks(); x < 128; x++)
                    nativePrefix[base + (z + 1) * 129 + x + 1] = row + nativePrefix[base + z * 129 + x + 1];
            }
            for (int z = shape.chunks(); z < 128; z++)
                System.arraycopy(nativePrefix, base + z * 129, nativePrefix, base + (z + 1) * 129, 129);
        }
    }
    private int nativeCount(int node) {
        int x0 = x(node) / 16, z0 = z(node) / 16;
        int x1 = Math.min(shape.chunks(), x0 + size(node) / 16), z1 = Math.min(shape.chunks(), z0 + size(node) / 16);
        if (x0 >= x1 || z0 >= z1) return 0;
        int b = face(node) * 129 * 129;
        return nativePrefix[b + z1 * 129 + x1] - nativePrefix[b + z0 * 129 + x1]
                - nativePrefix[b + z1 * 129 + x0] + nativePrefix[b + z0 * 129 + x0];
    }
    private int tileCount(int node) {
        return Math.max(0, Math.min(shape.size(), x(node) + size(node)) - x(node)) / 16
                * (Math.max(0, Math.min(shape.size(), z(node) + size(node)) - z(node)) / 16);
    }
    private boolean resolve(int node, IntPredicate visible) {
        // This pass needs coverage only for visible branches; remote readiness uses covered().
        if (!valid[node] || !visible.test(node) || nativeCount(node) == tileCount(node)) {
            choice[node] = 3; return true;
        }
        boolean self = handles[node] >= 0;
        if (depth[node] == 4) { choice[node] = (byte) (self ? 1 : 0); return self; }
        // A clean requested coarse node wins immediately, unless native requires descent.
        if (!split[node] && nativeCount(node) == 0 && self && !dirty[node]) { choice[node] = 1; return true; }
        // One uncovered child already forces this entire sibling group back to self.
        // Do not traverse the other (potentially enormous) subtrees in that case.
        for (int q = 0; q < 4; q++) {
            if (!resolve(child(node, q), visible)) {
                choice[node] = (byte) (self ? 1 : 0);
                return self;
            }
        }
        choice[node] = 2;
        return choice[node] != 0;
    }
    private void emit(int node, IntPredicate visible, long frame, Cut out) {
        // resolve certified visibility for this view/revision; choice 3 excludes hidden/native branches.
        if (choice[node] == 1) {
            lastUsed[node] = Math.max(lastUsed[node], frame);
            if (nativeCount(node) > 0) out.masked[out.maskedCount++] = node;
            else out.pure[out.pureCount++] = node;
        } else if (choice[node] == 2) for (int q = 0; q < 4; q++) emit(child(node, q), visible, frame, out);
    }

    /** Pass-independent complete resident coverage, suitable for remotePatchReady (never uses culling/native). */
    public boolean patchReady(int patch) { return covered(root(patch)); }
    /** Same root-patch mapping as the renderer's remotePatchReady bridge. */
    public boolean rootReady(int patch) { return patchReady(patch); }
    private boolean covered(int node) {
        if (!valid[node] || handles[node] >= 0) return true;
        if (depth[node] == 4) return false;
        for (int q = 0; q < 4; q++) if (!covered(child(node, q))) return false;
        return true;
    }

    /**
     * Coverage-safe pressure relief, preferably BEFORE either pass selects its cut for frame.
     * Reclaims whole sibling subtrees only under a clean resident fallback parent; roots stay
     * pinned. Offscreen groups go first, then lowest fallback SSE from the latest updateTargets;
     * retainFrames is a soft tie-break preference for cold groups, never a pressure veto.
     * Equal scores prefer deeper groups. Direct target() overrides have no camera/SSE information.
     *
     * <p>Any range selected by either pass at frame or later is a hard pin. Earlier GPU submissions
     * must be retired by the caller/release callback before storage reuse. Returns released bytes;
     * clean fallback absence, dirty parents or current-frame pins can leave the budget exceeded.
     * Collapse cancels descendant tickets (even absent meshes) and resets their split targets;
     * updateTargets arms the pressure limit from the post-collapse bounds, then retains the
     * parent until that fallback SSE grows by 25%, or a new native intersection needs detail. This
     * prevents an eviction/rebuild loop at a fixed camera. Re-select each pass after eviction.
     */
    public long evictToBudget(long detailBudget, long frame, long retainFrames, IntConsumer release) {
        return evictToBudget(detailBudget, frame, retainFrames, -1, release);
    }

    public long evictToBudget(long detailBudget, long frame, long retainFrames, int preferredFace, IntConsumer release) {
        return evictToBudget(detailBudget, frame, retainFrames, preferredFace, false, release);
    }

    /** GPU admission may replace offscreen detail, never churn the currently visible cut. */
    public long evictOffscreenToBudget(long detailBudget, long frame, int preferredFace, IntConsumer release) {
        return evictToBudget(detailBudget, frame, 0, preferredFace, true, release);
    }

    private long evictToBudget(long detailBudget, long frame, long retainFrames, int preferredFace,
            boolean offscreenOnly, IntConsumer release) {
        Objects.requireNonNull(release);
        if (detailBudget < 0 || retainFrames < 0) throw new IllegalArgumentException("budget");
        if (detailBytes <= detailBudget) return 0;
        long before = residentBytes;
        // Freeze preferences: subtree releases must not mutate the queue's ordering keys.
        var cold = new BitSet(NODE_COUNT);
        var candidates = new PriorityQueue<Integer>((a, b) -> {
            int order = Boolean.compare(offscreen(b), offscreen(a));
            if (order == 0 && preferredFace >= 0) order = Boolean.compare(face(a) != preferredFace, face(b) != preferredFace);
            if (order == 0) order = Double.compare(fallbackSse[a], fallbackSse[b]);
            if (order == 0) order = Boolean.compare(!cold.get(a), !cold.get(b));
            if (order == 0) order = Integer.compare(depth[b], depth[a]);
            return order != 0 ? order : Integer.compare(a, b);
        });
        for (int n = 0; n < NODE_COUNT; n++) {
            if (valid[n] && depth[n] < 4 && handles[n] >= 0 && !dirty[n]
                    && (!offscreenOnly || offscreen(n))
                    && hasResidentChildren(n) && descendantsCold(n, frame, 0)) {
                cold.set(n, descendantsCold(n, frame, retainFrames));
                candidates.add(n);
            }
        }
        while (detailBytes > detailBudget && !candidates.isEmpty()) {
            int p = candidates.remove();
            // An earlier ancestor collapse may already have removed this candidate.
            if (handles[p] < 0 || dirty[p] || !hasResidentChildren(p)) continue;
            changed(p);
            split[p] = false;
            if (Double.isFinite(fallbackSse[p])) pressureSse[p] = -1;
            long oldBytes = residentBytes;
            for (int q = 0; q < 4; q++) releaseSubtree(child(p, q), release);
            pressureCost[p] = Math.max(1, oldBytes - residentBytes);
            refreshAncestors(p);
        }
        if (before != residentBytes) cutRevision++;
        return before - residentBytes;
    }

    /** Suppresses immediate re-selection of a non-root node after unrecoverable GPU admission rejection. */
    public void deferNode(int node) { deferNode(node, 1024L * 1024); }

    public void deferNode(int node, long meshBytes) {
        if (node < 0 || node >= NODE_COUNT || !valid[node] || depth[node] == 0) return;
        int p = parent(node);
        if (p >= 0 && Double.isFinite(fallbackSse[p])) {
            pressureSse[p] = Math.max(pressureSse[p], fallbackSse[p] * 1.25);
            pressureCost[p] = Math.max(pressureCost[p], Math.max(1, meshBytes) * 4);
            changed(p);
        }
    }
    private boolean offscreen(int node) {
        // A root awaiting the new view cannot certify that its old geometry is offscreen.
        return !targetVisible[node] && !unrefreshedViewRoots.get(patchOf(node));
    }

    /** Optimistic recovery allowance; actual per-layer allocation remains authoritative. */
    public long offscreenDetailBytes() {
        long total = 0;
        for (int n = 0; n < NODE_COUNT; n++)
            if (depth[n] > 0 && bytes[n] > 0 && offscreen(n)) total += bytes[n];
        return total;
    }

    /** Retry the most valuable visible fallbacks, keeping them drawable until publication.
     * A failed attempt cannot repeat at a fixed view/space: it needs a new view epoch or at
     * least 8 MiB MORE actual room. Merely waiting or expanding unknown bounds cannot unlock it.
     */
    public int recoverPressure(long viewEpoch, long availableBytes, int maxGroups) {
        if (availableBytes <= 0 || maxGroups <= 0) return 0;
        var candidates = new PriorityQueue<Integer>((a, b) -> Double.compare(fallbackSse[b], fallbackSse[a]));
        for (int n = 0; n < NODE_COUNT; n++) {
            if (pressureSse[n] == 0 || !targetVisible[n] || handles[n] < 0 || dirty[n]
                    || unrefreshedViewRoots.get(patchOf(n))) continue;
            if (retryView[n] == viewEpoch && availableBytes - retrySpace[n] < (8L << 20)) continue;
            if (pressureCost[n] <= availableBytes) candidates.add(n);
        }
        int recovered = 0;
        long observedSpace = availableBytes;
        while (!candidates.isEmpty() && recovered < maxGroups) {
            int n = candidates.remove();
            if (pressureCost[n] > availableBytes) continue;
            retryView[n] = viewEpoch;
            retrySpace[n] = observedSpace;
            availableBytes -= pressureCost[n];
            pressureSse[n] = 0;
            changed(n);
            recovered++;
        }
        return recovered;
    }

    private boolean hasResidentChildren(int node) {
        for (int q = 0; q < 4; q++) if (hasResident[child(node, q)]) return true;
        return false;
    }
    private boolean descendantsCold(int node, long frame, long retain) {
        for (int q = 0; q < 4; q++) {
            int c = child(node, q);
            if (handles[c] >= 0 && lastUsed[c] != Long.MIN_VALUE && (frame < lastUsed[c] || frame - lastUsed[c] <= retain)) return false;
            if (depth[c] < 4 && !descendantsCold(c, frame, retain)) return false;
        }
        return true;
    }
    private void releaseSubtree(int node, IntConsumer release) {
        if (depth[node] < 4) for (int q = 0; q < 4; q++) releaseSubtree(child(node, q), release);
        int h = handles[node]; handles[node] = -1;
        residentBytes -= bytes[node]; if (depth[node] != 0) detailBytes -= bytes[node];
        bytes[node] = 0; built[node] = 0; versions[node]++; dirty[node] = valid[node]; split[node] = false;
        lastUsed[node] = Long.MIN_VALUE;
        refreshBounds(node);
        if (h >= 0) release.accept(h);
    }
    /** Session/resource reset: releases every handle, cancels all tickets, restores initial bounds.
     * Caller must retire submitted ranges before invoking clear. External base caches are untouched. */
    public void clear(IntConsumer release) {
        cutRevision++;
        requestTargetRefresh();
        Arrays.fill(resolvedRevision, -1);
        Objects.requireNonNull(release);
        for (int patch = 0; patch < ROOT_COUNT; patch++) releaseSubtree(root(patch), release);
        Arrays.fill(pendingMinY, Integer.MAX_VALUE); Arrays.fill(pendingMaxY, Integer.MIN_VALUE);
        Arrays.fill(minY, initialMinY); Arrays.fill(maxY, initialMaxY);
        Arrays.fill(targetMinY, initialMinY); Arrays.fill(targetMaxY, initialMaxY);
        Arrays.fill(targetVisible, false); Arrays.fill(fallbackSse, Double.POSITIVE_INFINITY);
        Arrays.fill(pressureSse, 0); Arrays.fill(targetNear, false);
        Arrays.fill(pressureCost, 0); Arrays.fill(retrySpace, 0); Arrays.fill(retryView, Long.MIN_VALUE);
        for (int n = 0; n < NODE_COUNT; n++) desired[n] = (byte) COARSE[depth[n]];
    }
}
