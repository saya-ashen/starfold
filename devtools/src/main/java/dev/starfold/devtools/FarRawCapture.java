package dev.starfold.devtools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded, loaded-only evidence for the center tile; independent of Minecraft initialization. */
public final class FarRawCapture {
    public static final int MAX_COLUMNS = 256;

    /** Includes supplemental neighbor/above reads, not just the primary window. */
    public static final int MAX_BLOCK_SAMPLES = 2816;

    public static final int MAX_FLUID_NEIGHBOR_SETS = 128;
    public static final String LIGHT_MEANING =
            "Client light-engine layer values at the actual block position (0..15); "
                    + "not sky visibility, direct sunlight, shader brightness, or server light; "
                    + "may be pending/stale. UNKNOWN has no numeric value.";

    private FarRawCapture() {}

    public enum Status {
        LOADED,
        UNLOADED,
        UNMAPPED,
        OUT_OF_WORLD,
        UNKNOWN
    }

    public enum Direction {
        DOWN(0, -1, 0),
        UP(0, 1, 0),
        NORTH(0, 0, -1),
        SOUTH(0, 0, 1),
        WEST(-1, 0, 0),
        EAST(1, 0, 0);
        final int dx, dy, dz;

        Direction(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    public record Position(int x, int y, int z) {
        Position offset(Direction d) {
            return new Position(x + d.dx, y + d.dy, z + d.dz);
        }
    }

    public record MappedColumn(int x, int z) {}

    /** top is the highest WORLD_SURFACE occupied Y (chunk.getHeight, not first-free Y). */
    public record Surface(Status status, Integer top, String reason) {}

    /** family identifies isSame-equivalent fluids, e.g. water and flowing_water share a family. */
    public record Fluid(
            String type,
            String family,
            boolean source,
            boolean flowing,
            int amount,
            Boolean falling,
            float ownHeight) {}

    /** fluid == null means empty only when status == LOADED. air is likewise nullable if unknown. */
    public record Block(Status status, String state, Boolean air, Fluid fluid, String reason) {
        public static Block unavailable(Status status, String reason) {
            return new Block(status, null, null, null, reason);
        }
    }

    public record Light(Status status, Integer sky, Integer block, String reason) {}

    /** Implementations must never load chunks. Null map means unmapped, never air. */
    public interface Source {
        int minY();

        int maxYExclusive();

        MappedColumn map(int face, int u, int v);

        Surface surface(int x, int z);

        Block block(Position position);

        Light light(Position position);

        /** Minecraft adapters should preserve the fluid type's actual isSame rule. */
        default boolean sameFluid(Fluid fluid, Fluid above) {
            return Objects.equals(fluid.family(), above.family());
        }
    }

    public record Neighbor(Direction direction, Position position, Block block) {}

    public record EffectiveHeight(Status status, Float value, String reason) {}

    public record Sample(
            int u,
            int v,
            Position position,
            Block block,
            Light light,
            EffectiveHeight effectiveHeight,
            List<Neighbor> neighbors,
            boolean neighborsTruncated) {
        public Sample {
            neighbors = List.copyOf(neighbors);
        }
    }

    public record Column(
            int u,
            int v,
            MappedColumn mapped,
            Surface surface,
            Integer fromY,
            Integer toY,
            int clippedSamples,
            List<Sample> samples) {
        public Column {
            samples = List.copyOf(samples);
        }
    }

    public record Bounds(
            int face,
            int x,
            int z,
            int width,
            int depth,
            int minY,
            int maxYExclusive,
            int belowTop,
            int aboveTop,
            int maxColumns,
            int maxBlockSamples,
            int maxFluidNeighborSets) {}

    /**
     * Block status counts describe primary samples; neighbor status counts describe retained sets.
     * clippedSamples counts omitted primary Y positions, not unloaded/unmapped columns whose top is
     * unknown. budgetOmissions counts denied read attempts (a coordinate may be requested twice).
     * neighborSetsTruncated counts fluid samples retained without their six-neighbor set.
     */
    public record Counts(
            int columns,
            int unmappedColumns,
            int unloadedColumns,
            int unknownColumns,
            int primarySamples,
            int loadedSamples,
            int unloadedSamples,
            int unknownSamples,
            int lightUnknownSamples,
            int clippedSamples,
            int blockReads,
            int supplementalReads,
            int budgetOmissions,
            int fluidSamples,
            int neighborSets,
            int neighborSetsTruncated,
            int loadedNeighbors,
            int unloadedNeighbors,
            int unknownNeighbors,
            int outOfWorldNeighbors,
            int effectiveHeightUnknown) {}

    public record Report(
            Bounds bounds,
            Counts counts,
            String heightMeaning,
            String lightMeaning,
            List<Column> columns) {
        public Report {
            columns = List.copyOf(columns);
        }
    }

    /** tileX/tileZ are face block coordinates 0..2047, normalized down to a 16-block origin. */
    public static Report capture(int face, int tileX, int tileZ, Source source) {
        if (face < 0 || face > 5 || tileX < 0 || tileX > 2047 || tileZ < 0 || tileZ > 2047)
            throw new IllegalArgumentException(
                    "face must be 0..5; chart coordinates must be 0..2047");
        return new Capture(face, tileX & ~15, tileZ & ~15, Objects.requireNonNull(source)).run();
    }

    private static final class Capture {
        final int face, x, z, minY, maxY;
        final Source source;
        final Map<Position, Block> cache = new HashMap<>();
        final Map<MappedColumn, Surface> surfaces = new HashMap<>();
        int reads, supplemental, omitted, sets, truncated, fluids, effectiveUnknown;
        int loadedNeighbors, unloadedNeighbors, unknownNeighbors, outsideNeighbors;

        Capture(int face, int x, int z, Source source) {
            this.face = face;
            this.x = x;
            this.z = z;
            this.source = source;
            minY = source.minY();
            maxY = source.maxYExclusive();
            if (maxY < minY) throw new IllegalArgumentException("inverted world height range");
        }

        Surface surface(MappedColumn p) {
            return surfaces.computeIfAbsent(p, key -> source.surface(key.x(), key.z()));
        }

        Block read(Position p, boolean extra) {
            if (p.y() < minY || p.y() >= maxY)
                return Block.unavailable(Status.OUT_OF_WORLD, "outside world height");
            Block existing = cache.get(p);
            if (existing != null) return existing;
            Surface s = surface(new MappedColumn(p.x(), p.z()));
            if (s.status() != Status.LOADED) return Block.unavailable(s.status(), s.reason());
            if (reads == MAX_BLOCK_SAMPLES) {
                omitted++;
                return Block.unavailable(Status.UNKNOWN, "block read budget exhausted");
            }
            reads++;
            if (extra) supplemental++;
            Block block = Objects.requireNonNull(source.block(p));
            cache.put(p, block);
            return block;
        }

        Report run() {
            List<Column> primary = new ArrayList<>(MAX_COLUMNS);
            int unmapped = 0, unloaded = 0, unknown = 0, clipped = 0;
            int samples = 0,
                    loadedBlocks = 0,
                    unloadedBlocks = 0,
                    unknownBlocks = 0,
                    unknownLight = 0;
            // Fill primary evidence first, so fluid neighbor work cannot consume its budget.
            for (int v = z; v < z + 16; v++) {
                for (int u = x; u < x + 16; u++) {
                    MappedColumn mapped = source.map(face, u, v);
                    Surface s =
                            mapped == null
                                    ? new Surface(
                                            Status.UNMAPPED,
                                            null,
                                            "chart column not drawn in client plane")
                                    : surface(mapped);
                    Integer from = null, to = null;
                    int cut = 0;
                    List<Sample> values = new ArrayList<>(11);
                    if (s.status() == Status.UNMAPPED) unmapped++;
                    else if (s.status() == Status.UNLOADED) unloaded++;
                    else if (s.status() != Status.LOADED || s.top() == null) unknown++;
                    else {
                        long low = (long) s.top() - 8, high = (long) s.top() + 2;
                        long first = Math.max(minY, low), last = Math.min((long) maxY - 1, high);
                        int size = (int) Math.max(0, last - first + 1);
                        cut = 11 - size;
                        clipped += cut;
                        if (size > 0) {
                            from = (int) first;
                            to = (int) last;
                            for (int i = 0; i < size; i++) {
                                Position p = new Position(mapped.x(), from + i, mapped.z());
                                Block b = read(p, false);
                                Light light =
                                        b.status() == Status.LOADED
                                                ? source.light(p)
                                                : new Light(b.status(), null, null, b.reason());
                                samples++;
                                if (b.status() == Status.LOADED) loadedBlocks++;
                                else if (b.status() == Status.UNLOADED) unloadedBlocks++;
                                else unknownBlocks++;
                                if (light.status() != Status.LOADED) unknownLight++;
                                values.add(new Sample(u, v, p, b, light, null, List.of(), false));
                            }
                        }
                    }
                    primary.add(new Column(u, v, mapped, s, from, to, cut, values));
                }
            }
            List<Column> result = new ArrayList<>(MAX_COLUMNS);
            for (Column column : primary) {
                List<Sample> values = new ArrayList<>(column.samples().size());
                for (Sample sample : column.samples()) values.add(enrich(sample));
                result.add(
                        new Column(
                                column.u(),
                                column.v(),
                                column.mapped(),
                                column.surface(),
                                column.fromY(),
                                column.toY(),
                                column.clippedSamples(),
                                values));
            }
            Counts counts =
                    new Counts(
                            MAX_COLUMNS,
                            unmapped,
                            unloaded,
                            unknown,
                            samples,
                            loadedBlocks,
                            unloadedBlocks,
                            unknownBlocks,
                            unknownLight,
                            clipped,
                            reads,
                            supplemental,
                            omitted,
                            fluids,
                            sets,
                            truncated,
                            loadedNeighbors,
                            unloadedNeighbors,
                            unknownNeighbors,
                            outsideNeighbors,
                            effectiveUnknown);
            return new Report(
                    new Bounds(
                            face,
                            x,
                            z,
                            16,
                            16,
                            minY,
                            maxY,
                            8,
                            2,
                            MAX_COLUMNS,
                            MAX_BLOCK_SAMPLES,
                            MAX_FLUID_NEIGHBOR_SETS),
                    counts,
                    "Loaded chunk WORLD_SURFACE highest occupied Y; inclusive top-8..top+2, "
                            + "clipped to world height. Neighbor coordinates are actual client-world "
                            + "axis neighbors, possibly one block outside the primary window. "
                            + "Effective fluid height is 1 for same-family fluid above, otherwise ownHeight, "
                            + "only when above is loaded; no flow vector is queried.",
                    LIGHT_MEANING,
                    result);
        }

        Sample enrich(Sample s) {
            Fluid f = s.block().fluid();
            if (s.block().status() != Status.LOADED || f == null) return s;
            fluids++;
            List<Neighbor> neighbors = new ArrayList<>(6);
            boolean cut = sets == MAX_FLUID_NEIGHBOR_SETS;
            if (cut) truncated++;
            else {
                sets++;
                for (Direction d : Direction.values()) {
                    Position p = s.position().offset(d);
                    Block b = read(p, true);
                    switch (b.status()) {
                        case LOADED -> loadedNeighbors++;
                        case UNLOADED -> unloadedNeighbors++;
                        case OUT_OF_WORLD -> outsideNeighbors++;
                        default -> unknownNeighbors++;
                    }
                    neighbors.add(new Neighbor(d, p, b));
                }
            }
            Block above = read(s.position().offset(Direction.UP), true);
            EffectiveHeight effective;
            if (above.status() == Status.LOADED) {
                boolean same = above.fluid() != null && source.sameFluid(f, above.fluid());
                effective =
                        new EffectiveHeight(
                                Status.LOADED,
                                same ? 1.0f : f.ownHeight(),
                                "loaded above; same-family=" + same);
            } else {
                effectiveUnknown++;
                effective = new EffectiveHeight(above.status(), null, above.reason());
            }
            return new Sample(
                    s.u(), s.v(), s.position(), s.block(), s.light(), effective, neighbors, cut);
        }
    }
}
