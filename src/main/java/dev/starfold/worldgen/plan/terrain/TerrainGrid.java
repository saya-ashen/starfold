package dev.starfold.worldgen.plan.terrain;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import java.util.*;

/** Spherical node identities, aliases and adjacency shared by planning and queries. */
public class TerrainGrid {
        public final int size, n;
        public final Point[] positions;
        public final int[][] aliases;
        public final Map<Long, Integer> nodes;
        public TerrainGrid(int size) {
            this.size = size;
            n = size / 16;
            aliases = new int[6][(n + 1) * (n + 1)];
            nodes = new LinkedHashMap<>();
            var points = new ArrayList<Point>();
            for (var f : Face.VALUES)
                for (int u = 0; u <= n; u++)
                    for (int v = 0; v <= n; v++) {
                        var p = f.normal.scale(n).add(f.u.scale(2 * u - n)).add(f.v.scale(2 * v - n));
                        long key = key(p);
                        Integer id = nodes.get(key);
                        if (id == null) {
                            id = points.size();
                            nodes.put(key, id);
                            points.add(p.scale(1 / p.length()));
                        }
                        aliases[f.ordinal()][u * (n + 1) + v] = id;
                    }
            positions = points.toArray(Point[] ::new);
        }
        public int node(Face f, int u, int v) {
            return nodeIndex(size, n, aliases, nodes, f, u, v);
        }
        public int[][] neighbours() { return neighbours(false); }
        public int[][] neighbours(boolean diagonal) {
            var sets = new ArrayList<Set<Integer>>();
            for (int i = 0; i < positions.length; i++) sets.add(new TreeSet<>());
            for (var f : Face.VALUES)
                for (int u = 0; u <= n; u++)
                    for (int v = 0; v <= n; v++) {
                        int a = node(f, u, v);
                        for (int[] d : diagonal ? new int[][] {{1,0},{0,1},{1,1},{1,-1}} : new int[][] {{1,0},{0,1}})
                            if (u + d[0] <= n && v + d[1] <= n && v+d[1]>=0) {
                                int b = node(f, u + d[0], v + d[1]);
                                sets.get(a).add(b);
                                sets.get(b).add(a);
                            }
                    }
            return sets.stream()
                .map(s -> s.stream().mapToInt(Integer::intValue).toArray())
                .toArray(int[][] ::new);
        }
    private static long key(Point p) {
        return ((long) Math.round(p.x() + 256) << 20) | ((long) Math.round(p.y() + 256) << 10)
            | Math.round(p.z() + 256);
    }
    static int nodeIndex(
        int size, int n, int[][] aliases, Map<Long, Integer> nodes, Face face, int u, int v) {
        if (u >= 0 && v >= 0 && u <= n && v <= n)
            return aliases[face.ordinal()][u * (n + 1) + v];
        var route =
            new dev.starfold.geometry.CubeSurfaceTopology(size).canonical(new Cell(face, u * 16, 0, v * 16));
        var offset = route.transport(new Delta(1, 0, 1));
        var c = route.cell();
        // canonical routes cell centres; subtract its transported half-cell to recover a geometric node.
        int a = (int) Math.round((c.u() + .5 - offset.u() * .5) / 16),
            b = (int) Math.round((c.v() + .5 - offset.v() * .5) / 16);
        var p = c.face().normal.scale(n).add(c.face().u.scale(2 * a - n)).add(c.face().v.scale(2 * b - n));
        return nodes.get(key(p));
    }
}
