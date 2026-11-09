package dev.starfold.worldgen.surface;

import static dev.starfold.worldgen.surface.SurfaceFunction.*;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.plan.PlanetPlan;
import java.util.*;
import net.minecraft.world.level.block.state.BlockState;

/** Fixed 16/32/64-block field summaries. Construction is linear in grid/feature size; reads use four cells. */
public final class SurfaceSummary {
    // Separate from worldgen and input preparation: either may wait for a coarse sample.
    private static final java.util.concurrent.ExecutorService builder =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                var thread = new Thread(r, "starfold-surface-summary");
                thread.setDaemon(true);
                return thread;
            });
    private static final Map<SurfaceKey, java.lang.ref.WeakReference<java.util.concurrent.CompletableFuture<SurfaceSummary>>> shared = new HashMap<>();

    static synchronized java.util.concurrent.CompletableFuture<SurfaceSummary> prepare(SurfaceFunction function) {
        var key = SurfaceKey.of(function.terrain());
        var reference = shared.get(key);
        var future = reference == null ? null : reference.get();
        if (future == null) {
            shared.values().removeIf(value -> value.get() == null);
            future = java.util.concurrent.CompletableFuture.supplyAsync(() -> new SurfaceSummary(function), builder);
            shared.put(key, new java.lang.ref.WeakReference<>(future));
        }
        return future;
    }

    private final PlanetTerrain terrain;
    private final Cell[][] levels=new Cell[3][];
    public SurfaceSummary(SurfaceFunction function) {
        terrain=function.terrain();int n=terrain.shape().size()/16;
        levels[0]=new Cell[6*n*n];
        for(var face:Face.VALUES)for(int u=0;u<n;u++)for(int v=0;v<n;v++) {
            var sum=new Aggregate();
            for(int x:new int[]{4,12})for(int z:new int[]{4,12})sum.add(function.point(face,u*16+x,v*16+z));
            levels[0][key(face,u,v,n)]=compact(sum.cell());
        }
        coverVolumes(n);
        // A narrow roof can miss quadrature points. Rasterize its saved bounds once, including seam aliases.
        for(var site:terrain.plan().sites())cover(site,n);
        for(int lod=1;lod<3;lod++) {
            int previous=n;n=(n+1)/2;levels[lod]=new Cell[6*n*n];
            for(var face:Face.VALUES)for(int u=0;u<n;u++)for(int v=0;v<n;v++) {
                var sum=new Aggregate();
                for(int x=0;x<2;x++)for(int z=0;z<2;z++)sum.add(levels[lod-1][key(face,Math.min(previous-1,u*2+x),Math.min(previous-1,v*2+z),previous)]);
                levels[lod][key(face,u,v,n)]=compact(sum.cell());
            }
        }
    }
    private void coverVolumes(int n) {
        var fixed=terrain.terrainPlan();
        for(var face:Face.VALUES)for(int u=0;u<n;u++)for(int v=0;v<n;v++) {
            int key=key(face,u,v,n);var base=levels[0][key];
            var point=terrain.unit(face,u*16+8,v*16+8);
            for(var volume:fixed.localVolumes(face,u*16+8,v*16+8)) {
                if(volume.operation()!=dev.starfold.worldgen.plan.terrain.TerrainPlan.Operation.FILL || point.dot(volume.center())<=0)continue;
                var axis=Math.abs(volume.center().y())<.8?Face.TOP.normal:Face.EAST.normal;
                var along=volume.center().cross(axis);along=along.scale(1/along.length());
                var across=volume.center().cross(along);
                double x=point.dot(along)*fixed.size()*.5,z=point.dot(across)*fixed.size()*.5;
                var parameters=volume.parameters();
                if(parameters.has("wall_x")) {
                    double a=parameters.get("wall_x").getAsDouble(),b=parameters.get("wall_z").getAsDouble(),next=x*a+z*b;
                    z=-x*b+z*a;x=next;
                }
                double share=Math.clamp((volume.radiusU()+8-Math.abs(x))/16,0,1)
                    *Math.clamp((volume.radiusV()+8-Math.abs(z))/16,0,1);
                double top=volume.level()+volume.radiusY();
                if(share>base.canopyCoverage() && top>base.height()+1) {
                    var chart=fixed.chart(volume.center());
                    base=new Cell(base.height(),base.materials(),share,top,
                        List.of(new Coverage(terrain.surfaceState(chart.face(),Math.clamp((int)chart.u(),0,fixed.size()-1),Math.clamp((int)chart.v(),0,fixed.size()-1)),1)));
                }
            }
            levels[0][key]=base;
        }
    }
    private void cover(PlanetPlan.Site site,int n) {
        BlockState roof=null;int top=Integer.MIN_VALUE;
        for(var piece:site.pieces())for(var voxel:terrain.plan().templates().get(piece.template()).blocks()) {
            int y=piece.origin().getY()+voxel.y()-site.effects().burial();
            if(!voxel.state().isAir() && y>top) {top=y;roof=voxel.state();}
        }
        if(roof==null)return;
        var bounds=site.bounds();
        for(int u=Math.floorDiv(bounds.minU(),16)*16;u<=bounds.maxU();u+=16)
            for(int v=Math.floorDiv(bounds.minV(),16)*16;v<=bounds.maxV();v+=16) {
                int width=Math.min(u+16,bounds.maxU()+1)-Math.max(u,bounds.minU());
                int depth=Math.min(v+16,bounds.maxV()+1)-Math.max(v,bounds.minV());
                var owner=Scatter.canonical(terrain,site.face(),u+8,v+8);
                int key=key(owner.face(),owner.u()/16,owner.v()/16,n);var base=levels[0][key];
                if(top<=base.height())continue;
                double share=width*depth/256.0*(1-site.effects().damage());
                if(share>base.canopyCoverage())levels[0][key]=new Cell(base.height(),base.materials(),share,top,List.of(new Coverage(roof,1)));
            }
    }
    Cell sample(Face face,int u,int v,int level) {
        int lod=Integer.numberOfTrailingZeros(level)-4,n=(terrain.shape().size()+level-1)/level;
        int a=Math.floorDiv(u,level),b=Math.floorDiv(v,level);
        int x=Math.floorMod(u,level),z=Math.floorMod(v,level);
        var sum=new Aggregate();
        for(int i=0;i<2;i++)for(int j=0;j<2;j++) {
            int weight=(i==0?level-x:x)*(j==0?level-z:z);
            if(weight==0)continue;
            var owner=Scatter.canonical(terrain,face,(a+i)*level+level/2,(b+j)*level+level/2);
            sum.add(levels[lod][key(owner.face(),Math.min(n-1,owner.u()/level),Math.min(n-1,owner.v()/level),n)],weight);
        }
        return compact(sum.cell());
    }
    private static int key(Face face,int u,int v,int n) { return (face.ordinal()*n+u)*n+v; }
    private static List<Coverage> compact(List<Coverage> source) {
        if(source.size()<=4)return source;
        double tail=source.subList(4,source.size()).stream().mapToDouble(Coverage::share).sum();
        var result=new ArrayList<>(source.subList(0,4));
        result.set(0,new Coverage(result.getFirst().block(),result.getFirst().share()+tail));return result;
    }
    private static Cell compact(Cell cell) {
        return new Cell(cell.height(),compact(cell.materials()),cell.canopyCoverage(),cell.canopyTop(),compact(cell.canopyMaterials()));
    }
}
