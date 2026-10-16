package dev.starfold.planet;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Cell;
import dev.starfold.storage.ChunkKeys;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Versioned addresses in ONE Level. Storage charts are not universe coordinates.
 * A keeps its existing six owners; B has separate real chunks, including separate halos.
 * No ambient/thread-local/current-player selector: every inverse operation carries a planet.
 */
public final class PlanetStorage {
    /** The square layout of planet storage charts. */
    public static final String LAYOUT = "six_face_square_v2";

    /** Planets stand in a square that grows a shell at a time: columns a stride apart along x, rows a row stride apart
     *  along z. A planet's six charts and their halos take 26624 blocks of x and 6144 of z, so no neighbour is touched,
     *  and a square of SIDE lies inside the world border. A planet keeps its place as the square grows. */
    public static final int SIDE = 900, MAX_PLANETS = SIDE * SIDE;

    public static final int PLANET_STRIDE = 32768,
            ROW_STRIDE = 8192,
            CHUNKS_PER_PLANET = ChunkKeys.CHUNKS_PER_PLANET;

    /** Shell s holds the planets s*s to (s+1)*(s+1)-1: first up its new column, then along its new row. */
    public static int column(int planet) {
        if (planet < 0 || planet >= MAX_PLANETS) throw new IllegalArgumentException("Planet");
        int s = (int) Math.sqrt(planet);
        while (s * s > planet) s--;
        while ((s + 1) * (s + 1) <= planet) s++;
        int r = planet - s * s;
        return r < s ? s : r - s;
    }

    public static int row(int planet) {
        if (planet < 0 || planet >= MAX_PLANETS) throw new IllegalArgumentException("Planet");
        int s = (int) Math.sqrt(planet);
        while (s * s > planet) s--;
        while ((s + 1) * (s + 1) <= planet) s++;
        int r = planet - s * s;
        return r < s ? r : s;
    }

    /** The planet whose place a column and row is, whether or not the system has that many. */
    public static int index(int column, int row) {
        if (column < 0 || row < 0 || column >= SIDE || row >= SIDE) return -1;
        int s = Math.max(column, row);
        return column == s && row < s ? s * s + row : s * s + s + column;
    }

    public record Owner(int planet, Cell cell) {
        public Owner {
            PlanetShape.valid(planet);
            if (cell == null || !PlanetShape.of(planet).topology().owned(cell))
                throw new IllegalArgumentException("Canonical planet owner");
        }

        public BlockPos block() {
            return PlanetStorage.block(planet, cell);
        }

        public long key() {
            return planet * (long) CHUNKS_PER_PLANET
                    + cell.u() / 16
                    + 128L * (cell.v() / 16 + 128L * cell.face().ordinal());
        }
    }

    public record Address(Owner owner, int turns) {
        public Address {
            if (owner == null) throw new IllegalArgumentException("Planet address");
            turns = Math.floorMod(turns, 4);
        }

        public GroundTopology.Address oriented() {
            return new GroundTopology.Address(owner.block(), turns);
        }
    }

    /** Where a planet's storage begins along x, and along z. */
    public static int offset(int planet) {
        return column(PlanetShape.valid(planet)) * PLANET_STRIDE;
    }

    public static int offsetZ(int planet) {
        return row(PlanetShape.valid(planet)) * ROW_STRIDE;
    }

    public static Point origin(int planet) {
        return new Point(offset(planet), 0, offsetZ(planet));
    }

    /** Only canonical storage chunks; gaps and halo queries do not own blocks. */
    public static long key(int chunkX, int chunkZ) {
        int planet =
                index(
                        Math.floorDiv(chunkX, PLANET_STRIDE / 16),
                        Math.floorDiv(chunkZ, ROW_STRIDE / 16));
        if (planet < 0 || planet >= PlanetShape.count()) return -1;
        long local =
                ChunkKeys.local(
                        chunkX - offset(planet) / 16,
                        chunkZ - offsetZ(planet) / 16,
                        PlanetShape.of(planet).chunks());
        return local < 0 ? -1 : planet * (long) CHUNKS_PER_PLANET + local;
    }

    public static ChunkPos chunk(long key) {
        if (key < 0 || key >= (long) PlanetShape.count() * CHUNKS_PER_PLANET)
            throw new IllegalArgumentException("Planet chunk key");
        int planet = (int) (key / CHUNKS_PER_PLANET);
        long local = key % CHUNKS_PER_PLANET;
        return new ChunkPos(
                offset(planet) / 16 + ChunkKeys.chunkX(local),
                offsetZ(planet) / 16 + ChunkKeys.chunkZ(local));
    }

    /** The planet whose charts or halos hold a place, or -1: the complete six-chart envelope of a planet, its outer
     *  halos included, is disjoint from every other's. Never throws; this is asked in the light engine. */
    public static int find(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) return -1;
        int column = (int) Math.floor((x + 2048) / PLANET_STRIDE),
                row = (int) Math.floor((z + 2048) / ROW_STRIDE);
        if (x - column * (double) PLANET_STRIDE >= 5 * 4096 + 4096
                || z - row * (double) ROW_STRIDE >= 4096) return -1;
        int planet = index(column, row);
        return planet >= PlanetShape.count() ? -1 : planet;
    }

    public static int planet(double x, double z) {
        int planet = find(x, z);
        if (planet < 0) throw new IllegalArgumentException("Outside planet storage charts");
        return planet;
    }

    public static int planet(BlockPos storage) {
        return planet(storage.getX(), storage.getZ());
    }

    /** How far along x the column a place lies in begins; a face is told from that alone. */
    public static int columnOffset(double x) {
        return (int) Math.floor((x + 2048) / PLANET_STRIDE) * PLANET_STRIDE;
    }

    public static BlockPos local(int planet, BlockPos storage) {
        return storage.offset(-offset(planet), 0, -offsetZ(planet));
    }

    public static BlockPos block(int planet, Cell cell) {
        return GroundTopology.block(PlanetShape.of(planet), cell)
                .offset(offset(planet), 0, offsetZ(planet));
    }

    public static Point feet(int planet, PlanetShape.Pose pose) {
        return GroundTopology.feet(pose).add(origin(planet));
    }

    public static PlanetShape.Pose body(int planet, Point feet) {
        return GroundTopology.body(PlanetShape.of(planet), feet.sub(origin(planet)));
    }

    public static Address address(BlockPos query) {
        int planet = planet(query);
        var local = local(planet, query);
        var shape = PlanetShape.of(planet);
        if (!GroundTopology.inChart(shape, local))
            throw new IllegalArgumentException("Outside planet chart halo");
        var route = GroundTopology.address(shape, local);
        return new Address(new Owner(planet, GroundTopology.cell(route.owner())), route.turns());
    }

    public static GroundTopology.Notification notification(BlockPos target, BlockPos source) {
        int planet = planet(target);
        if (planet(source) != planet)
            throw new IllegalArgumentException("Cross-planet neighbor notification");
        var n =
                GroundTopology.notification(
                        PlanetShape.of(planet), local(planet, target), local(planet, source));
        return new GroundTopology.Notification(
                n.target().offset(offset(planet), 0, offsetZ(planet)),
                n.source().offset(offset(planet), 0, offsetZ(planet)),
                n.turns());
    }

    private PlanetStorage() {}
}
