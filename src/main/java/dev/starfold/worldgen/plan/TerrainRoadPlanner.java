package dev.starfold.worldgen.plan;

import static dev.starfold.worldgen.plan.terrain.TerrainPlan.*;

import dev.starfold.worldgen.plan.terrain.TerrainPlan;
import dev.starfold.worldgen.surface.Scatter;
import java.util.*;

/** Deterministic minimum-cost grid routes; bridge and break decisions are fixed in the description. */
final class TerrainRoadPlanner {
    static TerrainPlan connect(TerrainPlan terrain, List<PlanetPlan.Site> villages, List<Relation> relations) {
        if (villages.size() < 2) return terrain.withContent(relations, terrain.volumes(), List.of());
        var grid = new Grid(terrain.size());
        int[][] neighbours = grid.neighbours(true);
        int count = grid.positions.length;
        var heights = new double[count];
        var passable = new boolean[count];
        for (int i = 0; i < count; i++) {
            var c = terrain.chart(grid.positions[i]);
            heights[i] = Math.clamp(terrain.height(c.face(), c.u(), c.v()), -63, 318);
            passable[i] = dev.starfold.worldgen.plan.terrain.TerrainPlanner.clear(grid.positions[i], SPACING + 4, terrain.size());
        }
        double sampleSpacing=Math.min(1,terrain.paths().stream().mapToDouble(Path::width).min().orElse(2)*.5);
        var nodes = new int[villages.size()];
        for (int i = 0; i < nodes.length; i++) {
            var site = villages.get(i);
            nodes[i] = grid.node(site.face(), (site.u() + 8) / 16, (site.v() + 8) / 16);
        }
        var connected = new BitSet(nodes.length);
        connected.set(0);
        var roads = new ArrayList<Road>();
        int work = 0;
        while (connected.cardinality() < nodes.length && roads.size() < 4096) {
            int from = -1, to = -1;
            double nearest = Double.POSITIVE_INFINITY;
            for (int a = connected.nextSetBit(0); a >= 0; a = connected.nextSetBit(a + 1))
                for (int b = 0; b < nodes.length; b++)
                    if (!connected.get(b)) {
                        double distance = grid.positions[nodes[a]].sub(grid.positions[nodes[b]]).length();
                        if (distance < nearest) { nearest = distance; from = a; to = b; }
                    }
            connected.set(to);
            var route = route(nodes[from], nodes[to], neighbours, heights, passable, grid);
            if (route.size() < 2 || route.size() > 4096 || work + route.size() > 200_000) continue;
            work += route.size();
            var levels = new ArrayList<Double>();
            for (int node : route) levels.add(heights[node]);
            for (int pass = 0; pass < 3; pass++) {
                var previous = List.copyOf(levels);
                for (int i = 1; i + 1 < levels.size(); i++)
                    levels.set(i, (previous.get(i - 1) + 2 * previous.get(i) + previous.get(i + 1)) * .25);
            }
            var modes = new ArrayList<RoadMode>();
            int road = roads.size();
            for (int i = 1; i < route.size(); i++) {
                var start=grid.positions[route.get(i-1)];
                var end=grid.positions[route.get(i)];
                double length=start.sub(end).length()*terrain.size()*.5;
                int steps=(int)Math.ceil(length/sampleSpacing);
                double water=Double.NEGATIVE_INFINITY;
                boolean crossing=Math.abs(heights[route.get(i)]-heights[route.get(i-1)])>12;
                var barriers=new LinkedHashSet<Ref>();
                for(int sample=0;sample<=steps;sample++) {
                    double t=sample/(double)steps;
                    var point=TerrainPlan.roadPoint(grid.positions,route,i,t);
                    var chart=terrain.chart(point);
                    double expected=heights[route.get(i-1)]*(1-t)+heights[route.get(i)]*t;
                    double actual=terrain.height(chart.face(),chart.u(),chart.v());
                    if(actual<expected-4) {
                        crossing=true;
                        for(int form=0;form<terrain.landforms().size();form++) {
                            var ref=new Ref(Kind.LANDFORM,form);
                            if(terrain.cuts(ref,point,(int)Math.ceil(expected)))barriers.add(ref);
                        }
                    }
                    double river=terrain.riverLevel(chart.face(),chart.u(),chart.v(),point);
                    water=Math.max(water,river);
                    if(Double.isFinite(river)) {
                        crossing=true;
                        barriers.addAll(terrain.riverCrossings(chart.face(),chart.u(),chart.v(),point));
                    }
                    for(var volume:terrain.localVolumes(chart.face(),chart.u(),chart.v()))
                        if(volume.operation()==Operation.CARVE && terrain.contains(volume,point,(int)Math.floor(expected))) {
                            crossing=true;
                            barriers.add(new Ref(Kind.VOLUME,terrain.volumes().indexOf(volume)));
                        }
                    int u=Math.clamp((int)chart.u(),0,terrain.size()-1),v=Math.clamp((int)chart.v(),0,terrain.size()-1);
                    var kind=terrain.water(chart.face(),u,v);
                    if(kind==Water.LAKE || kind==Water.OCEAN) {
                        crossing=true;
                        water=Math.max(water,terrain.waterLevel(chart.face(),u,v));
                    }
                }
                RoadMode mode = RoadMode.GROUND;
                if (crossing) {
                    long hash = Scatter.mix(terrain.seed() ^ villages.get(from).id() ^ villages.get(to).id() ^ i);
                    mode = (hash >>> 11) * 0x1.0p-53 < terrain.recipe().value("preferences", "road_continuity")
                        ? RoadMode.BRIDGE : RoadMode.CUT;
                    double deck = Math.min(318, Math.max(levels.get(i - 1), levels.get(i)) + 1);
                    if (Double.isFinite(water)) deck = Math.min(318, Math.max(deck, Math.ceil(water)));
                    if (mode == RoadMode.BRIDGE) { levels.set(i - 1, deck); levels.set(i, deck); }
                    for (var barrier:barriers)
                        relations.add(mode == RoadMode.BRIDGE
                            ? new Relation(new Ref(Kind.ROAD, road), "bridges", barrier)
                            : new Relation(barrier, "cuts", new Ref(Kind.ROAD, road)));
                }
                modes.add(mode);
            }
            roads.add(new Road(route, levels, modes, 3));
            relations.add(new Relation(new Ref(Kind.ROAD, road), "connects", new Ref(Kind.SITE, villages.get(from).id())));
            relations.add(new Relation(new Ref(Kind.ROAD, road), "connects", new Ref(Kind.SITE, villages.get(to).id())));
        }
        return terrain.withContent(relations, terrain.volumes(), roads);
    }
    private static List<Integer> route(int start, int end, int[][] neighbours, double[] heights,
                                       boolean[] passable, Grid grid) {
        record Step(int node, double cost) {}
        var costs = new double[heights.length];
        var parents = new int[heights.length];
        Arrays.fill(costs, Double.POSITIVE_INFINITY);
        Arrays.fill(parents, -1);
        var queue = new PriorityQueue<Step>(Comparator.comparingDouble(Step::cost).thenComparingInt(Step::node));
        costs[start] = 0;
        queue.add(new Step(start, 0));
        while (!queue.isEmpty()) {
            var step = queue.remove();
            if (step.cost != costs[step.node]) continue;
            if (step.node == end) break;
            for (int next : neighbours[step.node]) {
                if (!passable[next]) continue;
                double distance = grid.positions[step.node].sub(grid.positions[next]).length() * grid.size * .5;
                double rise = (heights[next] - heights[step.node]) / distance;
                double cost = step.cost + distance * (1 + 4 * rise * rise);
                if (cost < costs[next]) {
                    costs[next] = cost; parents[next] = step.node;
                    queue.add(new Step(next, cost));
                }
            }
        }
        if (!Double.isFinite(costs[end])) return List.of();
        var result = new ArrayList<Integer>();
        for (int node = end; node != -1; node = parents[node]) result.add(node);
        Collections.reverse(result);
        return result;
    }
}
