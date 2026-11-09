package dev.starfold.worldgen.plan.terrain;

import static dev.starfold.worldgen.plan.terrain.TerrainPlan.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.starfold.geometry.BentStripGeometry.Point;
import java.io.*;
import java.util.*;

/** Binary terrain format, fixed-point precision and input budgets. */
final class TerrainPlanCodec {
    static float precision(int field) {
        return field == HUMIDITY ? 16384 : field == ROUGHNESS ? 1024 : 8;
    }
    static void write(TerrainPlan plan, DataOutputStream out) throws IOException {
        out.writeInt(FORMAT);
        out.writeLong(plan.seed());
        out.writeUTF(plan.recipe().toString());
        out.writeInt(plan.nodeCount());
        // Fixed point removes mantissa entropy; in-memory fields use the identical quantized values.
        for (int f = 0; f < 6; f++)
            for (int n = 0; n < plan.nodeCount(); n++) out.writeShort(Math.round(plan.field(f, n) * precision(f)));
        for (int n = 0; n < plan.nodeCount(); n++) out.writeShort(plan.biome(n));
        for (int n = 0; n < plan.nodeCount(); n++) out.writeByte(plan.water(n).ordinal());
        out.writeInt(plan.landforms().size());
        for (var f : plan.landforms()) {
            out.writeUTF(f.type());
            out.writeUTF(f.parameters().toString());
            out.writeDouble(f.center().x());
            out.writeDouble(f.center().y());
            out.writeDouble(f.center().z());
            out.writeDouble(f.radius());
            out.writeDouble(f.amplitude());
            out.writeByte(f.operation().ordinal());
        }
        out.writeInt(plan.paths().size());
        for (var p : plan.paths()) {
            out.writeInt(p.nodes().size());
            for (int n : p.nodes()) out.writeInt(n);
            out.writeDouble(p.width());
            out.writeDouble(p.depth());
            out.writeDouble(p.level());
            out.writeByte(p.operation().ordinal());
        }
        out.writeInt(plan.relations().size());
        for (var r : plan.relations()) {
            writeRef(out, r.source());
            out.writeUTF(r.relation());
            writeRef(out, r.target());
        }
        out.writeInt(plan.volumes().size());
        for (var volume : plan.volumes()) {
            out.writeUTF(volume.type());
            out.writeUTF(volume.parameters().toString());
            out.writeDouble(volume.center().x()); out.writeDouble(volume.center().y()); out.writeDouble(volume.center().z());
            out.writeDouble(volume.level());
            out.writeDouble(volume.radiusU()); out.writeDouble(volume.radiusY()); out.writeDouble(volume.radiusV());
            out.writeByte(volume.operation().ordinal());
        }
        out.writeInt(plan.roads().size());
        for (var road : plan.roads()) {
            out.writeDouble(road.width());
            out.writeInt(road.nodes().size());
            for (int i = 0; i < road.nodes().size(); i++) {
                out.writeInt(road.nodes().get(i)); out.writeDouble(road.levels().get(i));
                if (i > 0) out.writeByte(road.modes().get(i - 1).ordinal());
            }
        }
    }
    private static void writeRef(DataOutputStream out, Ref ref) throws IOException {
        out.writeByte(ref.kind().ordinal()); out.writeLong(ref.id());
    }
    private static Ref readRef(DataInputStream in) throws IOException {
        int kind = in.readUnsignedByte();
        if (kind >= Kind.values().length) throw new IOException("Terrain reference kind");
        return new Ref(Kind.values()[kind], in.readLong());
    }
    public static TerrainPlan read(DataInputStream in, int size) throws IOException {
        if (in.readInt() != FORMAT)
            throw new IOException("Terrain plan version");
        long seed = in.readLong();
        var recipe =
            TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(in.readUTF())).getOrThrow();
        int count = in.readInt();
        if (count != 6 * (size / 16) * (size / 16) + 2)
            throw new IOException("Terrain node count");
        var fields = new float[6][count];
        for (int f = 0; f < 6; f++)
            for (int n = 0; n < count; n++) {
                float v = in.readShort() / precision(f);
                if (!Float.isFinite(v) || v < -2048 || v > 2048 || f == ROUGHNESS && (v < 0 || v > 16)
                    || f == HUMIDITY && (v < 0 || v > 1))
                    throw new IOException("Terrain field bounds");
                fields[f][n] = v;
            }
        var biomes = new short[count];
        for (int n = 0; n < count; n++) {
            biomes[n] = in.readShort();
            if (biomes[n] < 0 || biomes[n] >= recipe.biomes().size())
                throw new IOException("Terrain biome index");
        }
        var water = new byte[count];
        in.readFully(water);
        for (byte w : water)
            if (w < 0 || w >= Water.values().length)
                throw new IOException("Terrain water");
        int forms = bounded(in, MAX_LANDFORMS);
        var landforms = new ArrayList<Landform>();
        for (int i = 0; i < forms; i++) {
            String type = in.readUTF();
            var params = TerrainRegistries.LANDFORMS.get(type)
                             .codec()
                             .parse(JsonOps.INSTANCE, JsonParser.parseString(in.readUTF()))
                             .getOrThrow();
            var p = new Point(in.readDouble(), in.readDouble(), in.readDouble());
            double radius = in.readDouble(), amplitude = in.readDouble();
            var op = operation(in);
            if (!p.finite() || Math.abs(p.length() - 1) > 1e-8 || !Double.isFinite(radius) || radius < 16
                || radius > 256 || !Double.isFinite(amplitude) || amplitude < 0 || amplitude > 128
                || op != Operation.RAISE && op != Operation.LOWER)
                throw new IOException("Terrain landform bounds");
            if (!TerrainPlanner.clear(p, radius, size))
                throw new IOException("Landform intersects anomaly");
            landforms.add(new Landform(type, params, p, radius, amplitude, op));
        }
        var grid = new Grid(size);
        int pathCount = bounded(in, MAX_PATHS), work = 0;
        var paths = new ArrayList<Path>();
        for (int i = 0; i < pathCount; i++) {
            int length = bounded(in, 4096);
            work += length;
            if (length < 2 || work > 200_000)
                throw new IOException("Terrain path work");
            var indices = new ArrayList<Integer>();
            for (int j = 0; j < length; j++) {
                int n = in.readInt();
                if (n < 0 || n >= count)
                    throw new IOException("Terrain path node");
                indices.add(n);
            }
            double width = in.readDouble(), depth = in.readDouble(), level = in.readDouble();
            var op = operation(in);
            if (!Double.isFinite(width) || width < 1 || width > 16 || !Double.isFinite(depth) || depth < 0
                || depth > 32 || !Double.isFinite(level) || level < -1024 || level > 320
                || op != Operation.CARVE && op != Operation.FILL)
                throw new IOException("Terrain path bounds");
            if (new HashSet<>(indices).size() != indices.size()) throw new IOException("Repeated terrain path node");
            for (int j = 1; j < indices.size(); j++) validateEdge(grid, indices.get(j - 1), indices.get(j), width);
            paths.add(new Path(indices, width, depth, level, op));
        }
        var next = new ArrayList<Set<Integer>>();
        var degree = new int[count];
        for (int i = 0; i < count; i++) next.add(new HashSet<>());
        for (var path : paths)
            for (int j = 1; j < path.nodes().size(); j++)
                if (next.get(path.nodes().get(j - 1)).add(path.nodes().get(j))) degree[path.nodes().get(j)]++;
        var queue = new ArrayDeque<Integer>();
        for (int i = 0; i < count; i++) if (degree[i] == 0) queue.add(i);
        int visited = 0;
        while (!queue.isEmpty()) {
            int node = queue.remove(); visited++;
            for (int to : next.get(node)) if (--degree[to] == 0) queue.add(to);
        }
        if (visited != count) throw new IOException("Cyclic terrain paths");
        int relations = bounded(in, 16384);
        var links = new ArrayList<Relation>();
        for (int i = 0; i < relations; i++) {
            var source = readRef(in);
            String relation = in.readUTF();
            var target = readRef(in);
            if (!Set.of("connects", "cuts", "bridges", "buries", "overlaps", "damages").contains(relation))
                throw new IOException("Terrain relation");
            links.add(new Relation(source, relation, target));
        }
        int volumeCount = bounded(in, 4096);
        var volumes = new ArrayList<Volume>();
        for (int i = 0; i < volumeCount; i++) {
            String type = in.readUTF();
            var parameters = TerrainRegistries.VOLUMES.get(type).codec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString(in.readUTF())).getOrThrow();
            var center = new Point(in.readDouble(), in.readDouble(), in.readDouble());
            double level = in.readDouble(), u = in.readDouble(), y = in.readDouble(), v = in.readDouble();
            var operation = operation(in);
            if (!center.finite() || Math.abs(center.length() - 1) > 1e-8 || !Double.isFinite(level)
                || level < -1024 || level > 318 || !Double.isFinite(u) || u < 1 || u > 64
                || !Double.isFinite(y) || y < 1 || y > 96 || !Double.isFinite(v) || v < 1 || v > 64
                || operation != Operation.CARVE && operation != Operation.FILL
                || !TerrainPlanner.clear(center, volumeReach(u, v, size), size))
                throw new IOException("Terrain volume bounds");
            volumes.add(new Volume(type, parameters, center, level, u, y, v, operation));
        }
        int roadCount = bounded(in, 4096), roadWork = 0;
        var roads = new ArrayList<Road>();
        for (int i = 0; i < roadCount; i++) {
            double width = in.readDouble();
            int length = bounded(in, 4096);
            roadWork += length;
            if (!Double.isFinite(width) || width < 1 || width > 8 || length < 2 || roadWork > 200_000)
                throw new IOException("Terrain road work");
            var indices = new ArrayList<Integer>();
            var levels = new ArrayList<Double>();
            var modes = new ArrayList<RoadMode>();
            for (int j = 0; j < length; j++) {
                int node = in.readInt();
                double level = in.readDouble();
                if (node < 0 || node >= count || !Double.isFinite(level) || level < -1024 || level > 318)
                    throw new IOException("Terrain road node");
                if (j > 0) {
                    validateEdge(grid, indices.getLast(), node, width);
                    int mode = in.readUnsignedByte();
                    if (mode >= RoadMode.values().length) throw new IOException("Terrain road mode");
                    modes.add(RoadMode.values()[mode]);
                }
                indices.add(node); levels.add(level);
            }
            if(new HashSet<>(indices).size()!=indices.size())throw new IOException("Repeated terrain road node");
            roads.add(new Road(indices, levels, modes, width));
        }
        var result = new TerrainPlan(seed, size, recipe, fields, biomes, water, landforms, paths, links, volumes, roads);
        result.validateReferences(Set.of(), false);
        return result;
    }
    private static void validateEdge(Grid grid, int a, int b, double width) throws IOException {
        double length = grid.positions[a].sub(grid.positions[b]).length() * grid.size * .5;
        if (a == b || length > SPACING * 2 + 1e-8
            || !TerrainPlanner.clear(grid.positions[a], length + width, grid.size)
            || !TerrainPlanner.clear(grid.positions[b], length + width, grid.size))
            throw new IOException("Terrain path edge");
    }
    private static Operation operation(DataInputStream in) throws IOException {
        int n = in.readUnsignedByte();
        if (n >= Operation.values().length)
            throw new IOException("Terrain operation");
        return Operation.values()[n];
    }
    private static int bounded(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max)
            throw new IOException("Terrain count");
        return n;
    }
    private TerrainPlanCodec() {}
}
