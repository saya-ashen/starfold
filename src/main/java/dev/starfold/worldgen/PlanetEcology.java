package dev.starfold.worldgen;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.planet.Anomaly;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** v4 pure climate and bounded per-column decorations; no neighbour chunk writes or stored edits. */
public final class PlanetEcology {
    public enum Region {
        FOREST,
        PLAINS,
        DESERT
    }

    private final PlanetTerrain terrain;

    public PlanetEcology(PlanetTerrain terrain) {
        this.terrain = terrain;
    }

    public Region region(Point direction) {
        var climate = terrain.generation().climate();
        double wet = terrain.field(direction, 4.0, 501) + climate.humidity(),
                warm =
                        terrain.field(direction, 3.0, 502)
                                + climate.temperature()
                                - Math.abs(direction.y()) * climate.latitude();
        return wet > .10
                ? Region.FOREST
                : wet < -.10 && warm > -.15 ? Region.DESERT : Region.PLAINS;
    }

    public Region region(Face f, int u, int v) {
        return region(terrain.unit(f, u + .5, v + .5));
    }

    private Cell cell(Face f, int u, int v) {
        return terrain.shape().topology().canonical(new Cell(f, u, 0, v)).cell();
    }

    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    /** Cell-centre cube coordinates are globally unique, including the six chart boundaries. */
    private long hash(Cell c) {
        var f = c.face();
        int size = terrain.shape().size();
        var p =
                f.normal
                        .scale(size)
                        .add(f.u.scale(c.u() * 2 + 1 - size))
                        .add(f.v.scale(c.v() * 2 + 1 - size));
        return mix(
                terrain.seed()
                        ^ mix((long) p.x())
                        ^ Long.rotateLeft(mix((long) p.y()), 21)
                        ^ Long.rotateLeft(mix((long) p.z()), 42));
    }

    public record Tree(Cell root, int ground, int height) {}

    /** Nothing grows on an anomaly's field. */
    private boolean cleared(Cell c) {
        return Anomaly.distance(terrain.unit(c.face(), c.u() + .5, c.v() + .5), terrain.shape())
                < Anomaly.CLEAR;
    }

    public Tree tree(Face face, int u, int v) {
        var c = cell(face, u, v);
        long hash = hash(c);
        if ((hash & 127) != 0 || cleared(c)) return null;
        var region = region(c.face(), c.u(), c.v());
        if (region == Region.DESERT || (region == Region.PLAINS && (hash & 1023) != 0)) return null;
        // Local minimum prevents overlapping trunks/crowns without generation-order state.
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++)
                if (x != 0 || z != 0) {
                    var other = cell(c.face(), c.u() + x, c.v() + z);
                    long candidate = hash(other);
                    if ((candidate & 127) == 0 && Long.compareUnsigned(candidate, hash) < 0)
                        return null;
                }
        int ground = terrain.groundY(c.face(), c.u(), c.v());
        if (ground < 130 || ground > 154) return null;
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) {
                var other = cell(c.face(), c.u() + x, c.v() + z);
                if (Math.abs(ground - terrain.groundY(other.face(), other.u(), other.v())) > 1)
                    return null;
            }
        return new Tree(c, ground, 4 + (int) ((hash >>> 10) % 3));
    }

    public static final class Decoration {
        private final int ground, peaks;
        private final BlockState[] above;
        private final boolean root;
        private final Region region;

        Decoration(int ground, BlockState[] above, boolean root, Region region, int peaks) {
            this.ground = ground;
            this.above = above;
            this.root = root;
            this.region = region;
            this.peaks = peaks;
        }

        public BlockState soil(int y, BlockState base) {
            if (y > ground || y < ground - 3) return base;
            if (region == Region.DESERT && ground >= 130 && ground <= peaks)
                return y >= ground - 1
                        ? Blocks.SAND.defaultBlockState()
                        : Blocks.SANDSTONE.defaultBlockState();
            return root && y == ground ? Blocks.DIRT.defaultBlockState() : base;
        }

        public BlockState above(int y) {
            int i = y - ground - 1;
            return i < 0 || i >= above.length ? Blocks.AIR.defaultBlockState() : above[i];
        }

        public int top() {
            for (int i = above.length - 1; i >= 0; i--)
                if (!above[i].isAir()) return ground + i + 1;
            return Math.max(ground, 127);
        }
    }

    public Decoration decoration(Face f, int u, int v) {
        var c = cell(f, u, v);
        int ground = terrain.groundY(c.face(), c.u(), c.v());
        var region = region(c.face(), c.u(), c.v());
        var above = new BlockState[10];
        java.util.Arrays.fill(above, Blocks.AIR.defaultBlockState());
        boolean root = false;
        if (cleared(c)) return new Decoration(ground, above, false, null, terrain.peaks());
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++) {
                var tree = tree(c.face(), c.u() + x, c.v() + z);
                if (tree == null) continue;
                if (x == 0 && z == 0) {
                    root = true;
                    for (int y = ground + 1; y <= ground + tree.height(); y++)
                        above[y - ground - 1] = Blocks.OAK_LOG.defaultBlockState();
                }
                if (Math.abs(x) + Math.abs(z) > 3) continue;
                for (int y = tree.ground() + tree.height() - 1;
                        y <= tree.ground() + tree.height() + 1;
                        y++) {
                    int i = y - ground - 1;
                    if (i < 0 || i >= above.length || above[i].is(Blocks.OAK_LOG)) continue;
                    int distance =
                            Math.max(
                                    1,
                                    Math.abs(x)
                                            + Math.abs(z)
                                            + Math.max(0, y - tree.ground() - tree.height()));
                    above[i] =
                            Blocks.OAK_LEAVES
                                    .defaultBlockState()
                                    .setValue(LeavesBlock.DISTANCE, Math.min(6, distance));
                }
            }
        if (!root && above[0].isAir() && ground >= 130 && ground <= terrain.peaks()) {
            long h = hash(c);
            if (region == Region.DESERT) {
                if ((h & 255) == 1) above[0] = Blocks.DEAD_BUSH.defaultBlockState();
            } else if ((h & 15) == 1) above[0] = Blocks.SHORT_GRASS.defaultBlockState();
            else if ((h & 127) == 2) above[0] = Blocks.DANDELION.defaultBlockState();
            else if ((h & 127) == 3) above[0] = Blocks.POPPY.defaultBlockState();
        }
        return new Decoration(ground, above, root, region, terrain.peaks());
    }

    public int surfaceY(Face f, int u, int v) {
        return decoration(f, u, v).top();
    }
}
