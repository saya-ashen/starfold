package dev.starfold.worldgen.lod;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Incremental multi-height occupancy mesher. Horizontal representative samples; exact vertical cells. */
public final class VoxelMesher {
    private static final int[][] NEIGHBOURS = {
        {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
    };

    public record Quad(BlockState material, int axis, List<Point> points, boolean volume, int rgb) {
        public Quad(BlockState material, int axis, List<Point> points) {
            this(material, axis, points, true, -1);
        }
        public Quad(BlockState material,int axis,List<Point> points,boolean volume) {
            this(material,axis,points,volume,-1);
        }

        public Quad {
            points = List.copyOf(points);
        }
    }

    /** Far blocks are whole cubes, so thin non-solid blocks (grass, flowers, bushes) read as air
     *  and waterlogged ones as their fluid; otherwise one plant becomes a coloured block. */
    public static BlockState shown(BlockState state) {
        if (state.isAir() || state.isSolid()) return state;
        var fluid = state.getFluidState();
        return fluid.isEmpty() ? Blocks.AIR.defaultBlockState() : fluid.createLegacyBlock();
    }

    public static boolean plant(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.BushBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.SugarCaneBlock;
    }

    public static boolean visible(BlockState state, BlockState neighbour) {
        if (state.isAir()) return false;
        boolean fluid = !state.getFluidState().isEmpty();
        if (fluid) return neighbour.isAir();
        return neighbour.isAir() || plant(neighbour) || !neighbour.getFluidState().isEmpty();
    }

    public static final class Job implements AutoCloseable {
        private record RunKey(int axis, int u, int v, BlockState material, boolean aboveGround) {}

        private record Run(int index, int low, int high) {}

        private final VoxelSource source;
        private final Face face;
        private final int x, z, step, count, height, cap;
        private final boolean entireTile;
        private final VoxelSource.Column[] columns;
        private final ArrayList<Quad> quads = new ArrayList<>();
        private final Map<RunKey, Run> runs;
        private int loaded, cursor, mergedVerticalFaces, mergedHorizontalFaces;
        private boolean closed, overflow;
        private long checks;

        public Job(VoxelSource source, Face face, int x, int z, int step, int cap) {
            this(source, face, x, z, step, cap, false);
        }

        public Job(
                VoxelSource source,
                Face face,
                int x,
                int z,
                int step,
                int cap,
                boolean mergeCoplanarRuns) {
            this(source, face, x, z, step, cap, mergeCoplanarRuns, false);
        }

        /** Full tiles preserve structures on the boundary; only procedural refinement uses a collar. */
        public Job(
                VoxelSource source,
                Face face,
                int x,
                int z,
                int step,
                int cap,
                boolean mergeCoplanarRuns,
                boolean entireTile) {
            if ((x & 15) != 0
                    || (z & 15) != 0
                    || (step != 1 && step != 2 && step != 4 && step != 8)
                    || cap < 0) throw new IllegalArgumentException("Voxel tile/LOD/cap");
            this.source = source;
            this.face = face;
            this.x = x;
            this.z = z;
            this.step = step;
            this.cap = cap;
            runs = mergeCoplanarRuns ? new HashMap<>() : null;
            this.entireTile = entireTile;
            count = entireTile ? 16 / step : (14 + step - 1) / step;
            height = source.ceiling() - source.floor();
            if (height <= 0 || height > 4096) throw new IllegalArgumentException("Column height");
            columns = new VoxelSource.Column[(count + 2) * (count + 2)];
        }

        private int index(int u, int v) {
            return u + 1 + (count + 2) * (v + 1);
        }

        private int coordinate(int i) {
            return entireTile ? i * step + step / 2 : i < 0 ? 0 : i >= count ? 15 : 1 + i * step;
        }

        /** Deadlines checked before every new column/cell. A single source column is the atomic CPU unit. */
        public void step(long deadline, int columnLimit, int cellLimit, BooleanSupplier cancelled) {
            step(deadline, columnLimit, cellLimit, cancelled, System::nanoTime);
        }

        public void step(
                long deadline,
                int columnLimit,
                int cellLimit,
                BooleanSupplier cancelled,
                java.util.function.LongSupplier clock) {
            if (closed || overflow) return;
            int newColumns = 0, cells = 0;
            while (loaded < columns.length && newColumns < columnLimit) {
                if (cancelled.getAsBoolean()) {
                    close();
                    return;
                }
                if (clock.getAsLong() >= deadline) return;
                int u = loaded % (count + 2) - 1, v = loaded / (count + 2) - 1;
                columns[loaded++] = source.column(face, x + coordinate(u), z + coordinate(v));
                newColumns++;
            }
            if (loaded < columns.length) return;
            while (cursor < count * count * height && cells++ < cellLimit) {
                if (cancelled.getAsBoolean()) {
                    close();
                    return;
                }
                if (clock.getAsLong() >= deadline) return;
                int u = cursor % count,
                        v = cursor / count % count,
                        y = source.floor() + cursor / (count * count);
                cursor++;
                var state = shown(columns[index(u, v)].state(y));
                checks++;
                if (state.isAir()) continue;
                for (int axis = 0; axis < 6; axis++) {
                    var n = NEIGHBOURS[axis];
                    int ny = y + n[1];
                    var other =
                            ny < source.floor() || ny >= source.ceiling()
                                    ? Blocks.AIR.defaultBlockState()
                                    : shown(columns[index(u + n[0], v + n[2])].state(ny));
                    // The collar renders ground/water, not halo trees: absent volume cannot cull a
                    // cut face.
                    boolean collar =
                            axis == 0 && u == 0
                                    || axis == 1 && u == count - 1
                                    || axis == 4 && v == 0
                                    || axis == 5 && v == count - 1;
                    if (!entireTile
                            && collar
                            && y > columns[index(u + n[0], v + n[2])].ground()
                            && other.getFluidState().isEmpty())
                        other = Blocks.AIR.defaultBlockState();
                    if (!visible(state, other)) continue;
                    int inset = entireTile ? 0 : 1;
                    double a = x + inset + u * step,
                            b = Math.min(x + 16 - inset, a + step),
                            c = z + inset + v * step,
                            d = Math.min(z + 16 - inset, c + step);
                    if (!add(state, axis, u, v, y, a, b, c, d)) {
                        overflow = true;
                        quads.clear();
                        if (runs != null) runs.clear();
                        return;
                    }
                }
            }
        }

        // One tail lookup per face: vertical stacks or horizontal rows, never a full sweep.
        // Preserve material, plane, gaps and vertical ground boundary; spans stay <=16 blocks.
        private boolean add(
                BlockState state,
                int axis,
                int u,
                int v,
                int y,
                double a,
                double b,
                double c,
                double d) {
            RunKey key = null;
            boolean horizontal = axis == 2 || axis == 3;
            int low = horizontal ? (int) a : y, high = horizontal ? (int) b : y + 1;
            if (runs != null) {
                key =
                        new RunKey(
                                axis,
                                horizontal ? y : u,
                                v,
                                state,
                                !horizontal && y > columns[index(u, v)].ground());
                var prior = runs.get(key);
                if (prior != null && prior.high() == low && high - prior.low() <= 16) {
                    quads.set(
                            prior.index(),
                            new Quad(
                                    state,
                                    axis,
                                    horizontal
                                            ? points(axis, prior.low(), y, c, b, y + 1, d)
                                            : points(axis, a, prior.low(), c, b, y + 1, d)));
                    runs.put(key, new Run(prior.index(), prior.low(), high));
                    if (horizontal) mergedHorizontalFaces++;
                    else mergedVerticalFaces++;
                    return true;
                }
            }
            if (quads.size() == cap) return false;
            int index = quads.size();
            quads.add(new Quad(state, axis, points(axis, a, y, c, b, y + 1, d)));
            if (key != null) runs.put(key, new Run(index, low, high));
            return true;
        }

        public int mergedVerticalFaces() {
            return mergedVerticalFaces;
        }

        public int mergedHorizontalFaces() {
            return mergedHorizontalFaces;
        }

        int activeRuns() {
            return runs == null ? 0 : runs.size();
        }

        public boolean done() {
            return !closed && (overflow || cursor == count * count * height);
        }

        public boolean overflow() {
            return overflow;
        }

        public int loadedColumns() {
            return loaded;
        }

        public long checkedCells() {
            return checks;
        }

        public List<Quad> quads() {
            if (!done()) throw new IllegalStateException("Incomplete voxel tile");
            return List.copyOf(quads);
        }

        public int quadCount() {
            return quads.size();
        }

        public Quad quad(int index) {
            return quads.get(index);
        }

        public VoxelSource.Column column(int u, int v) {
            return columns[index(u, v)];
        }

        public int count() {
            return count;
        }

        public void close() {
            closed = true;
            quads.clear();
            if (runs != null) runs.clear();
            java.util.Arrays.fill(columns, null);
        }
    }

    /** Counter-clockwise viewed from outside, including undersides. Coordinates are world block edges. */
    public static List<Point> points(
            int axis, double a, double y, double c, double b, double h, double d) {
        return switch (axis) {
            case 0 ->
                    List.of(
                            new Point(a, y, c),
                            new Point(a, y, d),
                            new Point(a, h, d),
                            new Point(a, h, c));
            case 1 ->
                    List.of(
                            new Point(b, y, d),
                            new Point(b, y, c),
                            new Point(b, h, c),
                            new Point(b, h, d));
            case 2 ->
                    List.of(
                            new Point(a, y, c),
                            new Point(b, y, c),
                            new Point(b, y, d),
                            new Point(a, y, d));
            case 3 ->
                    List.of(
                            new Point(a, h, d),
                            new Point(b, h, d),
                            new Point(b, h, c),
                            new Point(a, h, c));
            case 4 ->
                    List.of(
                            new Point(b, y, c),
                            new Point(a, y, c),
                            new Point(a, h, c),
                            new Point(b, h, c));
            case 5 ->
                    List.of(
                            new Point(a, y, d),
                            new Point(b, y, d),
                            new Point(b, h, d),
                            new Point(a, h, d));
            default -> throw new IllegalArgumentException("Face axis");
        };
    }

    @FunctionalInterface
    public interface Columns { VoxelColumn column(int x, int z); }

    /** Run interval subtraction, not a heightfield. Boundary faces also close mixed-level edges. */
    public static List<Quad> boxes(int x0, int z0, int x1, int z1, int step, Columns field) {
        var out = new ArrayList<Quad>();
        for (int z = z0; z < z1; z += step) for (int x = x0; x < x1; x += step) {
            int right = Math.min(x1, x + step), far = Math.min(z1, z + step);
            var column = field.column(x, z);
            for (var r : column.runs()) {
                if (!r.block().getFluidState().isEmpty()) {
                    if(visible(r.block(),column.state(r.top())))
                        out.add(new Quad(r.block(),3,points(3,x,r.bottom(),z,right,r.top()-1.0/9,far)));
                    continue;
                }
                if (plant(r.block())) {
                    if(step<=2) {
                        double a=x+.1464466,b=a+.7071068;
                        double c=z+.1464466,d=c+.7071068;
                        for(int y=r.bottom();y<r.top();y++) {
                            out.add(new Quad(r.block(),0,List.of(new Point(a,y,c),new Point(b,y,d),
                                    new Point(b,y+1,d),new Point(a,y+1,c)),false));
                            out.add(new Quad(r.block(),2,List.of(new Point(a,y,d),new Point(b,y,c),
                                    new Point(b,y+1,c),new Point(a,y+1,d)),false));
                        }
                    }
                    continue;
                }
                if (visible(r.block(), column.state(r.bottom() - 1)))
                    out.add(new Quad(r.block(), 2, points(2, x, r.bottom(), z, right, r.top(), far)));
                if (visible(r.block(), column.state(r.top())))
                    out.add(new Quad(r.block(), 3, points(3, x, r.bottom(), z, right, r.top(), far)));
                for (int axis : new int[]{0, 1, 4, 5}) {
                    boolean boundary = axis == 0 ? x == x0 : axis == 1 ? right == x1 : axis == 4 ? z == z0 : far == z1;
                    var other = field.column(axis == 0 ? x - step : axis == 1 ? x + step : x,
                            axis == 4 ? z - step : axis == 5 ? z + step : z);
                    int low = r.bottom();
                    for (var n : other.runs()) {
                        if (boundary || visible(r.block(), n.block()) || n.top() <= low || n.bottom() >= r.top()) continue;
                        if (n.bottom() > low) out.add(new Quad(r.block(), axis,
                                points(axis, x, low, z, right, Math.min(n.bottom(), r.top()), far)));
                        low = Math.max(low, n.top());
                        if (low >= r.top()) break;
                    }
                    if (low < r.top()) out.add(new Quad(r.block(), axis, points(axis, x, low, z, right, r.top(), far)));
                }
            }
        }
        var merged=new ArrayList<>(merge(merge(out,false),true));
        for(var q:out) if(!q.volume())
            merged.add(new Quad(q.material(),q.axis(),q.points().reversed(),false));
        return List.copyOf(merged);
    }

    private record Plane(int axis, double position, double low, double high, BlockState material) {}

    /** Greedy rows, then equal-width rows into rectangles; never merge across material/plane/gap. */
    private static List<Quad> merge(List<Quad> input, boolean vertical) {
        var sorted = new ArrayList<>(input);
        sorted.sort(java.util.Comparator.comparingDouble(q -> low(q, along(q.axis(), vertical))));
        var tails = new HashMap<Plane, Integer>();
        var out = new ArrayList<Quad>();
        for (var q : sorted) {
            if(!q.volume()){out.add(q);continue;}
            double[] b = bounds(q);
            int plane = q.axis() / 2, u = along(q.axis(), vertical), v = along(q.axis(), !vertical);
            var key = new Plane(q.axis(), b[plane], b[v], b[v + 3], q.material());
            var index = tails.get(key);
            if (index != null) {
                var previous = out.get(index);
                double[] a = bounds(previous);
                if (a[u + 3] == b[u]) {
                    a[u + 3] = b[u + 3];
                    out.set(index, new Quad(q.material(), q.axis(), points(q.axis(), a[0], a[1], a[2], a[3], a[4], a[5])));
                    continue;
                }
            }
            tails.put(key, out.size());
            out.add(q);
        }
        return List.copyOf(out);
    }

    private static int along(int axis, boolean vertical) {
        return vertical ? (axis / 2 == 1 ? 2 : 1) : (axis / 2 == 0 ? 2 : 0);
    }

    private static double low(Quad q, int axis) {
        double low = Double.POSITIVE_INFINITY;
        for (var p : q.points())
            low = Math.min(low, switch (axis) { case 0 -> p.x(); case 1 -> p.y(); default -> p.z(); });
        return low;
    }

    private static double[] bounds(Quad q) {
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (var p : q.points()) {
            b[0] = Math.min(b[0], p.x()); b[3] = Math.max(b[3], p.x());
            b[1] = Math.min(b[1], p.y()); b[4] = Math.max(b[4], p.y());
            b[2] = Math.min(b[2], p.z()); b[5] = Math.max(b[5], p.z());
        }
        return b;
    }

    private VoxelMesher() {}
}
