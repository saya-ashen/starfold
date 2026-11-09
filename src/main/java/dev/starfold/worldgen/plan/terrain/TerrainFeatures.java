package dev.starfold.worldgen.plan.terrain;

import static dev.starfold.worldgen.plan.terrain.TerrainPlan.*;

import com.google.gson.JsonObject;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.noise.TerrainNoise;
import java.io.IOException;
import java.util.*;

/** Derived feature indices, queries and reference validation for an immutable terrain description. */
final class TerrainFeatures {
    private final int size;
    private final Point[] positions;
    private final TerrainNoise noise;
    private final List<Landform> landforms;
    private final List<Path> paths;
    private final List<Relation> relations;
    private final List<Volume> volumes;
    private record VolumeShape(Point u,Point v,TerrainRegistries.VolumeType shape) {}
    private final Map<Volume,VolumeShape> volumeShapes=new IdentityHashMap<>();
    private final List<Road> roads;
    private final List<Volume>[] volumeIndex;
    private record RoadSegment(Road road, int edge) {}
    private final List<RoadSegment>[] roadIndex;
    private final List<Landform>[] landformIndex;
    private final List<Path>[] pathIndex;
    private final Map<Path, double[][]> segments = new IdentityHashMap<>();
    private final Map<Path,Integer> pathIds = new IdentityHashMap<>();
    private static final class RiverSample {
        java.lang.ref.WeakReference<TerrainFeatures> owner = new java.lang.ref.WeakReference<>(null);
        Point point;
        double level;
    }
    private static final ThreadLocal<RiverSample> RIVER_SAMPLE = ThreadLocal.withInitial(RiverSample::new);
    @SuppressWarnings("unchecked")
    TerrainFeatures(TerrainPlan plan, Point[] positions, TerrainNoise noise) {
        size = plan.size();
        this.positions = positions;
        this.noise = noise;
        landforms = plan.landforms();
        paths = plan.paths();
        relations = plan.relations();
        volumes = plan.volumes();
        roads = plan.roads();
        for(int i=0;i<paths.size();i++)pathIds.put(paths.get(i),i);
        for(var volume:volumes) {
            var axis=Math.abs(volume.center().y())<.8?Face.TOP.normal:Face.EAST.normal;
            var u=volume.center().cross(axis);u=u.scale(1/u.length());var v=volume.center().cross(u);
            volumeShapes.put(volume,new VolumeShape(u,v,TerrainRegistries.VOLUMES.get(volume.type()).implementation()));
        }
        int buckets = (size + 63) / 64;
        landformIndex = new List[6 * buckets * buckets];
        int pathBuckets = size / 16;
        pathIndex = new List[6 * pathBuckets * pathBuckets];
        for (int i = 0; i < pathIndex.length; i++) pathIndex[i] = new ArrayList<>();
        for (var face : Face.VALUES)
            for (int u = 0; u < buckets; u++)
                for (int v = 0; v < buckets; v++) {
                    var center = PlanetTerrain.direction(new PlanetShape(size), face,
                        Math.min(size, u * 64 + 32), Math.min(size, v * 64 + 32));
                    double halo = 128;
                    int key = (face.ordinal() * buckets + u) * buckets + v;
                    var local = new ArrayList<Landform>();
                    for (var form : landforms)
                        if (form.center().sub(center).length() * size / 2 <= form.radius() + halo)
                            local.add(form);
                    landformIndex[key] = List.copyOf(local);

                }
        var neighbours = new ArrayList<SortedSet<Integer>>();
        for (int i = 0; i < positions.length; i++) neighbours.add(new TreeSet<>());
        var upstream = new int[positions.length];
        var downstream = new int[positions.length];
        var incomingWidth = new double[positions.length];
        var outgoingWidth = new double[positions.length];
        Arrays.fill(upstream, -1);
        Arrays.fill(downstream, -1);
        for (var path : paths)
            for (int j = 1; j < path.nodes().size(); j++) {
                int a = path.nodes().get(j - 1), b = path.nodes().get(j);
                neighbours.get(a).add(b);
                neighbours.get(b).add(a);
                if (path.width() > incomingWidth[b] || path.width() == incomingWidth[b] && a < upstream[b]) {
                    upstream[b] = a;
                    incomingWidth[b] = path.width();
                }
                if (path.width() > outgoingWidth[a] || path.width() == outgoingWidth[a] && b < downstream[a]) {
                    downstream[a] = b;
                    outgoingWidth[a] = path.width();
                }
            }
        var centers = positions.clone();
        for (int i = 0; i < centers.length; i++)
            if (neighbours.get(i).size() == 2) {
                var adjacent = neighbours.get(i);
                var p = positions[i].scale(.5).add(positions[adjacent.first()].scale(.25))
                    .add(positions[adjacent.last()].scale(.25));
                var tangent = positions[adjacent.last()].sub(positions[adjacent.first()]);
                var sideways = p.cross(tangent);
                double bend = noise.sample(positions[i].scale(size * .5), 72, 904) * 6;
                p = p.add(sideways.scale(bend * 2 / (size * sideways.length())));
                centers[i] = p.scale(1 / p.length());
            }
        var tangents = new Point[positions.length];
        for (int i = 0; i < tangents.length; i++) {
            int a = upstream[i], b = downstream[i];
            if (a < 0 && b < 0) continue;
            var tangent = centers[b < 0 ? i : b].sub(centers[a < 0 ? i : a]);
            tangents[i] = tangent.scale(1 / tangent.length());
        }
        // Tributaries share the main flow tangent at a fixed confluence, rather than making grid elbows.
        for (var path : paths) {
            var lines = new ArrayList<double[]>();
            for (int j = 1; j < path.nodes().size(); j++) {
                int a = path.nodes().get(j - 1), b = path.nodes().get(j);
                Point start = centers[a], end = centers[b];
                double reach = end.sub(start).length() / 3;
                Point controlA = start.add(tangents[a].scale(reach)),
                      controlB = end.sub(tangents[b].scale(reach));
                Point previous = start;
                for (int step = 1; step <= 8; step++) {
                    double t = step / 8.0, q = 1 - t;
                    var next = start.scale(q * q * q).add(controlA.scale(3 * q * q * t))
                        .add(controlB.scale(3 * q * t * t)).add(end.scale(t * t * t));
                    next = next.scale(1 / next.length());
                    var delta = next.sub(previous);
                    lines.add(new double[] {previous.x(), previous.y(), previous.z(), delta.x(), delta.y(),
                        delta.z(), 1 / delta.dot(delta)});
                    previous = next;
                }
            }
            segments.put(path, lines.toArray(double[][]::new));
            var keys = new HashSet<Integer>();
            // Sample every tessellated endpoint so long paths and rounded bends have complete bounds.
            for (var line : segments.get(path)) {
                Point p = new Point(line[0], line[1], line[2]);
                for (var face : Face.VALUES) {
                    double dot = p.dot(face.normal);
                    if (dot <= 0)
                        continue;
                    double u = size / 2.0 * (1 + p.dot(face.u) / dot),
                           v = size / 2.0 * (1 + p.dot(face.v) / dot);
                    if (u < -64 || v < -64 || u > size + 64 || v > size + 64)
                        continue;
                    int reach = (int) Math.ceil(path.width() * 3 / 16) + 1;
                    for (int a = Math.max(0, (int) u / 16 - reach);
                        a <= Math.min(pathBuckets - 1, (int) u / 16 + reach); a++)
                        for (int b = Math.max(0, (int) v / 16 - reach);
                            b <= Math.min(pathBuckets - 1, (int) v / 16 + reach); b++) {
                            keys.add((face.ordinal() * pathBuckets + a) * pathBuckets + b);
                        }
                }
            }
            for (int key : keys) {
                int face = key / (pathBuckets * pathBuckets), cell = key % (pathBuckets * pathBuckets);
                var center = PlanetTerrain.direction(new PlanetShape(size), Face.VALUES[face],
                    cell / pathBuckets * 16 + 8, cell % pathBuckets * 16 + 8);
                if (pathDistance(center, path) <= path.width() + 12)
                    pathIndex[key].add(path);
            }
        }
        for (int i = 0; i < pathIndex.length; i++) pathIndex[i] = List.copyOf(pathIndex[i]);
        volumeIndex = new List[landformIndex.length];
        roadIndex = new List[landformIndex.length];
        for (var face : Face.VALUES)
            for (int u = 0; u < buckets; u++)
                for (int v = 0; v < buckets; v++) {
                    var center = PlanetTerrain.direction(new PlanetShape(size), face, u * 64 + 32, v * 64 + 32);
                    int key = (face.ordinal() * buckets + u) * buckets + v;
                    var shapes = new ArrayList<Volume>();
                    for (var shape : volumes)
                        if (shape.center().sub(center).length() * size * .5 <= volumeReach(shape.radiusU(), shape.radiusV(), size) + 48)
                            shapes.add(shape);
                    volumeIndex[key] = List.copyOf(shapes);
                    roadIndex[key] = new ArrayList<>();
                }

        for(var road:roads)
            for(int edge=1;edge<road.nodes().size();edge++) {
                var keys=new HashSet<Integer>();
                for(int node:List.of(road.nodes().get(edge-1),road.nodes().get(edge))) {
                    var point=positions[node];
                    for(var face:Face.VALUES) {
                        double dot=point.dot(face.normal);
                        if(dot<=0)continue;
                        double u=size*.5*(1+point.dot(face.u)/dot),v=size*.5*(1+point.dot(face.v)/dot);
                        if(u < -64 || v < -64 || u > size+64 || v > size+64)continue;
                        for(int x=Math.max(0,(int)u/64-1);x<=Math.min(buckets-1,(int)u/64+1);x++)
                            for(int z=Math.max(0,(int)v/64-1);z<=Math.min(buckets-1,(int)v/64+1);z++)
                                keys.add((face.ordinal()*buckets+x)*buckets+z);
                    }
                }
                for(int key:keys)roadIndex[key].add(new RoadSegment(road,edge));
            }
        for(int i=0;i<roadIndex.length;i++)roadIndex[i]=List.copyOf(roadIndex[i]);
    }
    List<Volume> localVolumes(Face face, double u, double v) {
        return volumeIndex[bucket(face, u, v)];
    }
    boolean localContent(Face face, int u, int v) {
        int bucket=bucket(face,u,v);
        if(volumeIndex[bucket].isEmpty() && roadIndex[bucket].isEmpty())return false;
        var p = PlanetTerrain.direction(new PlanetShape(size), face, u + .5, v + .5);
        for (var volume : volumeIndex[bucket])
            if (p.sub(volume.center()).length() * size * .5 <= volumeReach(volume.radiusU(), volume.radiusV(), size)) return true;
        return road(face, u + .5, v + .5, p) != null;
    }
    // Convert the tangent-box diagonal to a spherical chord so every registered box corner is covered.
    static double volumeReach(double u, double v, int size) {
        return size*Math.sin(.5*Math.asin(2*Math.hypot(u,v)/size));
    }
    boolean contains(Volume volume, JsonObject parameters, Point p, int y) {
        if (p.dot(volume.center()) <= 0 || Math.abs(y - volume.level()) > volume.radiusY()) return false;
        var geometry=volumeShapes.get(volume);
        var u=geometry.u();var v=geometry.v();
        double physicalX=p.dot(u)*size*.5,physicalZ=p.dot(v)*size*.5;
        if(parameters.has("wall_x")) {
            double x=parameters.get("wall_x").getAsDouble(),z=parameters.get("wall_z").getAsDouble();
            double along=physicalX*x+physicalZ*z;
            physicalZ=-physicalX*z+physicalZ*x;physicalX=along;
        }
        double x=physicalX/volume.radiusU(),z=physicalZ/volume.radiusV();
        double shrink = 1 - .08 * Math.abs(noise.sample(p.scale(size * .5 + y - 128), 12, 905));
        return Math.abs(x) <= 1 && Math.abs(z) <= 1 && geometry.shape.contains(x / shrink, (y - volume.level()) / (volume.radiusY() * shrink),
                z / shrink, parameters);
    }
    /** Shared spherical cubic: planning crossings, road lookup and offline overlays use these same arcs. */
    static Point roadPoint(Point[] positions, List<Integer> nodes, int edge, double t) {
        var a=positions[nodes.get(edge-1)];var b=positions[nodes.get(edge)];
        var before=positions[nodes.get(Math.max(0,edge-2))];
        var after=positions[nodes.get(Math.min(nodes.size()-1,edge+1))];
        var m0=b.sub(before).scale(.5);var m1=after.sub(a).scale(.5);
        double t2=t*t,t3=t2*t;
        var p=a.scale(2*t3-3*t2+1).add(m0.scale(t3-2*t2+t))
            .add(b.scale(-2*t3+3*t2)).add(m1.scale(t3-t2));
        return p.scale(1/p.length());
    }
    Point roadPoint(Road road,int edge,double t) { return roadPoint(positions,road.nodes(),edge,t); }
    private RoadSample roadSegment(Road road, int edge, Point p) {
        var a=roadPoint(road,edge,0);
        for(int part=1;part<=8;part++) {
            var b=roadPoint(road,edge,part/8.0);var delta=b.sub(a);
            double local=Math.clamp(p.sub(a).dot(delta)/delta.dot(delta),0,1);
            if(p.sub(a.add(delta.scale(local))).length()*size*.5<=road.width()) {
                double t=(part-1+local)/8;
                return new RoadSample(road.levels().get(edge-1)*(1-t)+road.levels().get(edge)*t,road.modes().get(edge-1));
            }
            a=b;
        }
        return null;
    }
    RoadSample road(Face face, double u, double v, Point p) {
        RoadSample result = null;
        for (var segment : roadIndex[bucket(face, u, v)]) {
            var sample=roadSegment(segment.road,segment.edge,p);
            if(sample!=null)result=sample;
        }
        return result;
    }
    private int bucket(Face f, double u, double v) {
        int n = (size + 63) / 64;
        return (f.ordinal() * n + Math.clamp((int) u / 64, 0, n - 1)) * n
            + Math.clamp((int) v / 64, 0, n - 1);
    }
    double landformHeight(Point p, Face f, double u, double v) {
        double h = 0;
        for (var form : landformIndex[bucket(f, u, v)]) {
            double d = p.sub(form.center()).length() * size * .5 / form.radius();
            if (d >= 1)
                continue;
            double delta = form.amplitude()
                * TerrainRegistries.LANDFORMS.get(form.type()).implementation().evaluate(p, form, size, noise);
            h += form.operation() == Operation.LOWER ? -delta : delta;
        }
        return h;
    }
    private double pathDistance(Point p, Path path) {
        double best = Double.POSITIVE_INFINITY;
        for (var s : segments.get(path)) {
            double x = p.x() - s[0], y = p.y() - s[1], z = p.z() - s[2];
            double t = Math.clamp((x * s[3] + y * s[4] + z * s[5]) * s[6], 0, 1);
            x -= t * s[3]; y -= t * s[4]; z -= t * s[5];
            best = Math.min(best, x * x + y * y + z * z);
        }
        return Math.sqrt(best) * size * .5;
    }
    private int pathBucket(Face f, double u, double v) {
        int n = size / 16;
        return (f.ordinal() * n + Math.clamp((int) u / 16, 0, n - 1)) * n
            + Math.clamp((int) v / 16, 0, n - 1);
    }
    double pathHeight(double height, Face f, double u, double v, Point p) {
        var sample = RIVER_SAMPLE.get();
        if (sample.owner.get() != this) sample.owner = new java.lang.ref.WeakReference<>(this); sample.point = p; sample.level = Double.NEGATIVE_INFINITY;
        for (var path : pathIndex[pathBucket(f, u, v)]) {
            double d = pathDistance(p, path) / path.width();
            if (path.operation() == Operation.CARVE && d < .7)
                sample.level = Math.max(sample.level, path.level());
            if (d >= 1)
                continue;
            double profile = 1 - TerrainRegistries.smooth(d);
            height = switch (path.operation()) {
                case CARVE -> Math.min(height, path.level() - path.depth() * profile);
                case FILL -> Math.max(height, path.level());
                default -> throw new IllegalStateException("Path operation");
            };
        }
        return height;
    }
    List<Ref> riverCrossings(Face face,double u,double v,Point point) {
        var result=new ArrayList<Ref>();
        for(var path:pathIndex[pathBucket(face,u,v)])
            if(path.operation()==Operation.CARVE && pathDistance(point,path)<path.width())
                result.add(new Ref(Kind.RIVER,pathIds.get(path)));
        return result;
    }
    double riverLevel(Face f, double u, double v, Point p) {
        var sample = RIVER_SAMPLE.get();
        // chart -> direction round trips differ by at most a few ulps.
        if (sample.owner.get() == this && p.sub(sample.point).length() < 1e-14) return sample.level;
        double level = Double.NEGATIVE_INFINITY;
        for (var path : pathIndex[pathBucket(f, u, v)])
            if (path.operation() == Operation.CARVE && pathDistance(p, path) < path.width() * .7)
                level = Math.max(level, path.level());
        return level;
    }
    boolean validReference(Ref ref, Set<Long> sites, boolean includeSites) {
        long count = switch (ref.kind()) {
            case LANDFORM -> landforms.size(); case VOLUME -> volumes.size();
            case RIVER -> paths.size(); case ROAD -> roads.size(); case SITE -> -1;
        };
        return ref.kind() == Kind.SITE ? !includeSites || sites.contains(ref.id()) : ref.id() >= 0 && ref.id() < count;
    }
    void validateReferences(Set<Long> sites, boolean includeSites) throws IOException {
        for (var link : relations) {
            if (!validReference(link.source(), sites, includeSites) || !validReference(link.target(), sites, includeSites))
                throw new IOException("Missing terrain relation reference");
            boolean valid = switch (link.relation()) {
                case "connects" -> link.source().kind() == Kind.ROAD && link.target().kind() == Kind.SITE;
                case "bridges" -> link.source().kind() == Kind.ROAD
                    && Set.of(Kind.RIVER,Kind.VOLUME,Kind.LANDFORM).contains(link.target().kind());
                case "cuts" -> Set.of(Kind.RIVER,Kind.VOLUME,Kind.LANDFORM,Kind.ROAD).contains(link.source().kind())
                    && Set.of(Kind.ROAD,Kind.SITE).contains(link.target().kind());
                case "buries", "damages" -> link.source().kind() == Kind.SITE && link.target().equals(link.source());
                case "overlaps" -> link.source().kind() == Kind.SITE && link.target().kind() == Kind.SITE && link.source().id() != link.target().id();
                default -> false;
            };
            if (!valid) throw new IOException("Invalid terrain relation kinds");
        }
        if(includeSites)for(int road=0;road<roads.size();road++) {
            var ends=new HashSet<Long>();
            for(var link:relations)
                if(link.relation().equals("connects") && link.source().equals(new Ref(Kind.ROAD,road)))ends.add(link.target().id());
            if(ends.size()!=2)throw new IOException("Road requires two distinct settlement references");
        }
    }
    boolean cuts(TerrainPlan plan, Ref ref, Point point, int y) {
        return switch (ref.kind()) {
            case VOLUME -> {
                var shape = volumes.get((int) ref.id());
                yield shape.operation() == Operation.CARVE && plan.contains(shape, point, y);
            }
            case RIVER -> {
                var river = paths.get((int) ref.id());
                double d = pathDistance(point, river) / river.width();
                yield river.operation() == Operation.CARVE && d < 1 && y > Math.floor(river.level() - river.depth() * (1 - TerrainRegistries.smooth(d)));
            }
            case LANDFORM -> {
                var form = landforms.get((int) ref.id());
                double value = TerrainRegistries.LANDFORMS.get(form.type()).implementation().evaluate(point, form, size, noise);
                var c = plan.chart(point);
                yield (form.operation() == Operation.LOWER ? -value : value) < 0 && y > Math.floor(plan.height(c.face(), c.u(), c.v()));
            }
            case ROAD -> {
                var route=roads.get((int)ref.id());
                RoadSample sample=null;
                for(int edge=1;edge<route.nodes().size();edge++) {
                    var current=roadSegment(route,edge,point);
                    if(current!=null)sample=current;
                }
                yield sample != null && sample.mode() != RoadMode.CUT && y > sample.height() && y <= sample.height() + 4;
            }
            case SITE -> throw new IllegalArgumentException("Site cannot cut terrain");
        };
    }
}
