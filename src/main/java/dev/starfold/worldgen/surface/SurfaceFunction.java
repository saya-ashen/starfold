package dev.starfold.worldgen.surface;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Pointwise visible surface and chunk-generation composition. Coordinates name cell minima. */
public final class SurfaceFunction {
    public record Run(int bottom, int top, BlockState block) {
        public Run { if (bottom > top || block.isAir()) throw new IllegalArgumentException("Run"); }
    }
    public record Side(int du, int dv, int bottom, int top, List<Run> runs) {
        public Side { runs = List.copyOf(runs); }
    }
    /** Top is the ground/sea/closure cap; objects include vegetation and planned structures. */
    public record Column(int top, BlockState block, List<Side> sides, List<Run> objects) {
        public Column { sides = List.copyOf(sides); objects = List.copyOf(objects); }
    }
    public record Coverage(BlockState block, double share) {}
    public record Cell(double height, List<Coverage> materials, double canopyCoverage, double canopyTop,
                       List<Coverage> canopyMaterials, int linearRgb) {
        public Cell { materials = List.copyOf(materials); canopyMaterials = List.copyOf(canopyMaterials); }
        public Cell(double height, List<Coverage> materials, double canopyCoverage, double canopyTop,
                    List<Coverage> canopyMaterials) {
            this(height, materials, canopyCoverage, canopyTop, canopyMaterials, -1);
        }
    }
    private final PlanetTerrain terrain;
    private final dev.starfold.worldgen.plan.PlanetPlan plan;
    private final BiomeScatter scatter;
    private volatile java.util.concurrent.CompletableFuture<SurfaceSummary> coarse;
    private static final java.util.concurrent.ExecutorService preparation =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                var thread = new Thread(r, "starfold-surface-inputs");
                thread.setDaemon(true);
                return thread;
            });

    /** Callers on render/tick threads poll PlanetTerrain.readySurface instead of waiting. */
    public static java.util.concurrent.CompletableFuture<SurfaceFunction> prepare(PlanetTerrain terrain) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> new SurfaceFunction(terrain), preparation)
                .thenCompose(function -> function.summary().thenApply(ready -> function));
    }

    public synchronized java.util.concurrent.CompletableFuture<SurfaceSummary> summary() {
        if (coarse == null) coarse = SurfaceSummary.prepare(this);
        return coarse;
    }
    private static final int[][] OFFSETS = {{-1,0},{1,0},{0,-1},{0,1}};

    public SurfaceFunction(PlanetTerrain terrain) {
        this.terrain = Objects.requireNonNull(terrain);
        this.plan=terrain.plan();
        this.scatter = terrain.scatter();
    }
    public PlanetTerrain terrain() { return terrain; }

    private int ground(Face f,int u,int v) { return Math.clamp(plan.ground(f,u,v,terrain.groundY(f,u,v)),terrain.floor()+1,PlanetTerrain.MAX_Y-2); }
    private boolean vegetation(Face f,int u,int v) { return !plan.occupied(f,u,v); }
    private PlanetTerrain.Column column(Face f,int u,int v,int ground,int bottom) {
        if(plan.sites().isEmpty())return bottom<=terrain.floor() ? terrain.column(f,u,v) : terrain.visibleBand(f,u,v,ground,bottom);
        return bottom<=terrain.floor() ? terrain.plannedColumn(f,u,v,ground,vegetation(f,u,v))
                : terrain.visibleBand(f,u,v,ground,bottom,vegetation(f,u,v));
    }
    public int groundTop(Face f, int u, int v) {
        var c = Scatter.canonical(terrain,f,u,v);
        int ground=ground(c.face(),c.u(),c.v());
        int top=terrain.closure(c.face(),c.u(),c.v()) ? terrain.closureTop()
                : terrain.surfaceTop(c.face(),c.u(),c.v(),ground);
        var structures=plan.column(c.face(),c.u(),c.v());
        if(!structures.containsKey(top) || !structures.get(top).isAir())return top;
        var substrate=column(c.face(),c.u(),c.v(),ground,terrain.floor());
        while(top>terrain.floor() && structures.getOrDefault(top,substrate.state(top)).isAir())top--;
        return top;
    }

    public Column visible(Face face, int u, int v) { return visible(face,u,v,false); }

    /** Frozen pre-cache reference path for offline regression comparisons. */
    public Column visibleExact(Face face, int u, int v) { return visible(face,u,v,true); }

    private Column visible(Face face, int u, int v, boolean reference) {
        var owner = Scatter.canonical(terrain,face,u,v);
        face = owner.face(); u = owner.u(); v = owner.v();
        if (terrain.terrainPlan().localContent(face, u, v)) {
            var blocks = blocks(face, u, v);
            int top = groundTop(face, u, v);
            var states = new TreeMap<Integer, BlockState>();
            for (int y = terrain.floor(); y <= top; y++)
                if (!blocks[y - terrain.floor()].isAir()) states.put(y, blocks[y - terrain.floor()]);
            var shown = runs(states);
            var sides = new ArrayList<Side>();
            for (var offset : OFFSETS) sides.add(new Side(offset[0], offset[1], terrain.floor(), top, shown));
            states.clear();
            for (int y = top + 1; y < PlanetTerrain.MAX_Y; y++)
                if (!blocks[y - terrain.floor()].isAir()) states.put(y, blocks[y - terrain.floor()]);
            return new Column(top, blocks[top - terrain.floor()], sides, runs(states));
        }
        int ground = ground(face,u,v);
        int top = groundTop(face,u,v);
        var sides = new ArrayList<Side>(4);
        int[][] offsets = OFFSETS;
        int min = top;
        var bottoms = new int[4];
        int originalGround = terrain.visibleBottom(face,u,v,ground);
        for (int i = 0; i < 4; i++) {
            bottoms[i] = Math.min(originalGround,groundTop(face,u+offsets[i][0],v+offsets[i][1])+1);
            min = Math.min(min,bottoms[i]);
        }
        // Caves cannot breach the four-block cap, but exposed cliffs can reach caves and ores.
        var col = reference && plan.sites().isEmpty() ? (min < ground-3 ? terrain.column(face,u,v) : terrain.surfaceColumn(face,u,v))
                : column(face,u,v,ground,min);
        for (int i = 0; i < 4; i++) {
            if (bottoms[i] > top) {
                sides.add(new Side(offsets[i][0],offsets[i][1],bottoms[i],top,List.of()));
                continue;
            }
            var states = new TreeMap<Integer,BlockState>();
            for (int y = bottoms[i]; y <= top; y++) if (!col.state(y).isAir()) states.put(y,col.state(y));
            for(var e:plan.column(face,u,v).entrySet())if(e.getKey()>=bottoms[i] && e.getKey()<=top)states.put(e.getKey(),e.getValue());
            sides.add(new Side(offsets[i][0],offsets[i][1],bottoms[i],top,runs(states)));
        }
        var structures=plan.column(face,u,v);
        return new Column(top,structures.getOrDefault(top,col.state(top)),sides,objects(face,u,v,col,top,reference));
    }

    private List<Run> objects(Face f, int u, int v, PlanetTerrain.Column col, int top) {
        return objects(f,u,v,col,top,false);
    }
    private List<Run> objects(Face f, int u, int v, PlanetTerrain.Column col, int top, boolean reference) {
        if(plan.sites().isEmpty())return ecologyObjects(f,u,v,col,top,reference);
        var states=new TreeMap<Integer,BlockState>();
        if(vegetation(f,u,v))for(var run:ecologyObjects(f,u,v,col,top,reference))
            for(int y=run.bottom();y<=run.top();y++)states.put(y,run.block());
        for(var e:plan.column(f,u,v).entrySet())if(e.getKey()>top)states.put(e.getKey(),e.getValue());
        return runs(states);
    }
    private List<Run> ecologyObjects(Face f, int u, int v, PlanetTerrain.Column col, int top, boolean reference) {
        var states = new TreeMap<Integer,BlockState>();
        if(col!=null && !col.state(top+1).isAir())states.put(top+1,col.state(top+1));
        for (var run : scatter == null ? List.<Run>of() : scatter.column(f,u,v)) for (int y = Math.max(top+1,run.bottom()); y <= run.top(); y++)
            states.put(y,run.block());
        return runs(states);
    }

    /** Full local generation: callers write only their chunk's owned columns, including overlapping templates.
     * Underground L1/L3 rules are unchanged. */
    public net.minecraft.world.level.block.state.BlockState[] blocks(Face face, int u, int v) {
        return blocks(face,u,v,terrain.floor());
    }

    /** Exact comparison band from bottom upward, including exposed caves, vegetation and planned structures. */
    public BlockState[] blocks(Face face, int u, int v, int bottom) {
        var owner = Scatter.canonical(terrain,face,u,v);
        face = owner.face(); u = owner.u(); v = owner.v();
        var column = column(face,u,v,ground(face,u,v),bottom);
        var result = new BlockState[PlanetTerrain.MAX_Y-terrain.floor()];
        int top = groundTop(face,u,v);
        for (int y = terrain.floor(); y < PlanetTerrain.MAX_Y; y++)
            result[y-terrain.floor()] = y <= top ? column.state(y) : Blocks.AIR.defaultBlockState();
        for (var run : objects(face,u,v,column,top))
            for (int y = Math.max(top+1,run.bottom()); y <= Math.min(PlanetTerrain.MAX_Y-1,run.top()); y++) result[y-terrain.floor()] = run.block();
        for(var e:plan.column(face,u,v).entrySet())if(e.getKey()>=terrain.floor() && e.getKey()<PlanetTerrain.MAX_Y)
            result[e.getKey()-terrain.floor()]=e.getValue();
        return result;
    }

    /** Coarse LOD reads four bounded grid summaries; fine LOD integrates exact columns. */
    public Cell sample(Face face, int u, int v, int level) {
        if (level < 1 || level > 64 || (level & (level-1)) != 0) throw new IllegalArgumentException("LOD level");
        if(level>=16)return terrain.surfaceSummary(this).sample(face,u,v,level);
        var sum = new Aggregate();
        for (int x = 0; x < level; x++) for (int z = 0; z < level; z++) sum.add(point(face,u+x,v+z));
        return sum.cell();
    }

    public Cell point(Face f, int u, int v) {
        var c = Scatter.canonical(terrain,f,u,v);
        f = c.face(); u = c.u(); v = c.v();
        int raw=terrain.groundY(f,u,v),ground=Math.clamp(plan.ground(f,u,v,raw),terrain.floor()+1,PlanetTerrain.MAX_Y-2);
        var surface = plan.sites().isEmpty() ? terrain.surface(f,u,v,ground) : null;
        int cap=surface != null ? surface.top() : terrain.closure(f,u,v) ? terrain.closureTop() : terrain.surfaceTop(f,u,v,ground);
        var structures=plan.column(f,u,v);int top=cap;
        if(structures.containsKey(top) && structures.get(top).isAir())top=groundTop(f,u,v);
        BlockState material;
        List<Run> objects;
        if (plan.sites().isEmpty()) {
            material = surface.state();
            objects = scatter == null ? List.of() : scatter.column(f,u,v);
        } else if(ground==raw && top==cap) {
            material=structures.getOrDefault(top,terrain.surfaceState(f,u,v));
            // With no ecology, object composition needs only scatter and L6, not a terrain band.
            objects=objects(f,u,v,null,top);
        } else {
            var col = column(f,u,v,ground,top);
            material = structures.getOrDefault(top,col.state(top));
            objects = objects(f,u,v,col,top);
        }
        int canopy = 0;
        BlockState canopyBlock = Blocks.AIR.defaultBlockState();
        for (var r : objects) {
            var b = r.block();
            if (b.is(Blocks.SHORT_GRASS) || b.is(Blocks.DEAD_BUSH) || b.is(Blocks.DANDELION) || b.is(Blocks.POPPY)) continue;
            if (r.top() > canopy && r.top() > top) { canopy = r.top(); canopyBlock = b; }
        }
        return new Cell(top,List.of(new Coverage(material,1)),canopy == 0 ? 0 : 1,canopy,
                canopy == 0 ? List.of() : List.of(new Coverage(canopyBlock,1)));
    }

    /** Also used by the evaluator's streaming mip construction: no planet-wide cache in the function. */
    public static final class Aggregate {
        private int count;
        private double height, canopy, canopyTop;
        private final Map<BlockState,Double> materials = new LinkedHashMap<>();
        private final Map<BlockState,Double> canopyMaterials = new LinkedHashMap<>();
        public void add(Cell c) { add(c,1); }
        public void add(Cell c, int weight) {
            if (weight < 1) throw new IllegalArgumentException("Aggregate weight");
            count += weight; height += c.height()*weight; canopy += c.canopyCoverage()*weight;
            canopyTop += c.canopyTop()*c.canopyCoverage()*weight;
            for (var m : c.materials()) materials.merge(m.block(),m.share()*weight,Double::sum);
            for (var m : c.canopyMaterials()) canopyMaterials.merge(m.block(),m.share()*c.canopyCoverage()*weight,Double::sum);
        }
        public Cell cell() {
            if (count == 0) throw new IllegalStateException("Empty aggregate");
            var shares = materials.entrySet().stream()
                    .sorted(Map.Entry.<BlockState,Double>comparingByValue().reversed()
                            .thenComparing(e -> e.getKey().toString()))
                    .map(e -> new Coverage(e.getKey(),e.getValue()/count)).toList();
            // Keep all nonzero blocks: exact top-k with k equal to the number of observed materials.
            var crowns = canopy == 0 ? List.<Coverage>of() : canopyMaterials.entrySet().stream()
                    .sorted(Map.Entry.<BlockState,Double>comparingByValue().reversed()
                            .thenComparing(e -> e.getKey().toString()))
                    .map(e -> new Coverage(e.getKey(),e.getValue()/canopy)).toList();
            return new Cell(height/count,shares,canopy/count,canopy == 0 ? 0 : canopyTop/canopy,crowns);
        }
    }

    public static List<Run> runs(SortedMap<Integer,BlockState> blocks) {
        var result = new ArrayList<Run>();
        int bottom = 0, top = -2;
        BlockState last = null;
        for (var e : blocks.entrySet()) {
            if (e.getValue().isAir()) continue;
            if (e.getKey() != top+1 || e.getValue() != last) {
                if (last != null) result.add(new Run(bottom,top,last));
                bottom = e.getKey(); last = e.getValue();
            }
            top = e.getKey();
        }
        if (last != null) result.add(new Run(bottom,top,last));
        return List.copyOf(result);
    }
}
