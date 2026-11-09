package dev.starfold.render;

import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.worldgen.PlanetTerrain;

import java.util.*;
import java.util.function.LongFunction;

/** Render-thread voxel base readiness and immutable capture snapshots. */
final class GroundMipCache {
    private GroundBaseCache.Entry base;
    private final Map<Long,GroundSurface> edited=new HashMap<>();
    private final BitSet seenBase=new BitSet(),overlayChanges=new BitSet();
    private final Map<Long,Long> captureVersions=new HashMap<>();
    private Point camera;

    void clear() {
        base = null;
        edited.clear(); seenBase.clear(); captureVersions.clear(); overlayChanges.clear();
        polledOverlays.clear(); camera = null;
    }

    void focus(Point camera) { this.camera = camera; }

    long fineBytes() { return base == null ? 0 : base.fineBytes(); }

    long coarseBytes() {
        var cache = base == null ? null : base.cache;
        return cache == null ? 0 : cache.retainedBytes();
    }

    /**
     * Ready notifications name consumers, including their four edge halos, never the whole planet.
     */
    private BitSet polledOverlays=new BitSet();

    /** The capture-overlay share of the last {@link #poll()}. */
    BitSet polledOverlays() {
        var out=polledOverlays; polledOverlays=new BitSet(); return out;
    }

    BitSet poll() {
        if (base != null) {
            var patches=new BitSet();
            for(int p=0;p<base.ready.length();p++) {
                if(base.ready.get(p)==0 || seenBase.get(p)) continue;
                seenBase.set(p);
                int face=p/1024,u=(p%32)*64,v=(p%1024/32)*64;
                patches.set(face*64+u/256+8*(v/256));
                // Halo consumers also depend on this patch's boundary.
                for(int side=0;side<4;side++) {
                    int x=u+(side==0 ? -1 : side==1 ? 64 : 0);
                    int z=v+(side==2 ? -1 : side==3 ? 64 : 0);
                    var c=base.function.terrain().shape().topology().canonical(new Cell(Face.VALUES[face],x,0,z)).cell();
                    patches.set(c.face().ordinal()*64+c.u()/256+8*(c.v()/256));
                }
            }
            polledOverlays=(BitSet)overlayChanges.clone();
            patches.or(overlayChanges); overlayChanges.clear();
            return patches;
        }
        return new BitSet();
    }

    /** No optional mip jobs on the critical path to the first planet-wide coverage. */
    GroundFarMesh.Field initialField(PlanetTerrain terrain) {
        if (terrain != null) {
            var source=GroundBaseCache.get(terrain,camera);
            if(base!=source) {
                seenBase.clear(); edited.clear(); captureVersions.clear(); overlayChanges.clear();
            }
            base=source;
            return (f,x,z,s)->source.surface(f,x,z,s);
        }
        throw new IllegalStateException("Terrain profile required before meshing");
    }

    GroundFarMesh.Field field(
            PlanetShape shape,
            PlanetTerrain terrain,
            GroundCoverSnapshot cover,
            int patch) {
        return field(shape, terrain, cover::surface, patch);
    }

    /** Lookup is consumed only here on the render thread, never retained by a worker. */
    GroundFarMesh.Field field(
            PlanetShape shape,
            PlanetTerrain terrain,
            LongFunction<GroundSurface> capturesAt,
            int patch) {
        if (terrain != null) {
            var source=GroundBaseCache.get(terrain,camera);
            if(base!=source) {
                base=source; seenBase.clear(); edited.clear(); captureVersions.clear(); overlayChanges.clear();
            }
            var captures=new HashMap<Long,GroundSurface>();
            var touched=new HashSet<Long>();
            var face=Face.VALUES[GroundViewPolicy.face(patch)];
            int a=GroundViewPolicy.x(patch),b=GroundViewPolicy.z(patch);
            for(int v=b-64;v<Math.min(b+320,shape.size()+64);v+=16) for(int u=a-64;u<Math.min(a+320,shape.size()+64);u+=16) {
                var at=shape.topology().canonical(new Cell(face,u,0,v)).cell();
                long key=GroundOwnership.key(shape,at.face().ordinal(),at.u(),at.v());
                touched.add(key);
                var capture=capturesAt.apply(key);
                if(capture!=null) captures.put(key,capture);
            }
            var changes=new ArrayList<Long>();
            for(long key:touched) {
                var old=edited.get(key); var next=captures.get(key);
                if(next!=null && next.revision()<captureVersions.getOrDefault(key,-1L)) continue;
                if(old==null ? next!=null : next==null || !old.sameColumns(next)) changes.add(key);
            }
            if(!changes.isEmpty()) {
                for(long key:changes) {
                    var next=captures.get(key);
                    captureVersions.put(key,next==null ? edited.get(key).revision()+1 : next.revision());
                    overlayChanges.set(GroundViewPolicy.patch(key));
                    if(next!=null) edited.put(key,next); else edited.remove(key);
                }
            }
            Map<Long,GroundSurface> near=edited.isEmpty() ? Map.of() : new HashMap<>(edited);
            // Immutable edit snapshot and worker-local derived ancestors; no averaged colour layer.
            return new GroundFarMesh.Field() {
                final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<dev.starfold.worldgen.lod.VoxelColumn> derived=
                        new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
                dev.starfold.worldgen.lod.VoxelColumn column(Face f,int x,int z,int s) {
                    boolean edit=false;
                    for(int v=z/16*16;v<z+s && !edit;v+=16) for(int u=x/16*16;u<x+s;u+=16)
                        if(near.containsKey(GroundOwnership.key(shape,f.ordinal(),u,v))) { edit=true; break; }
                    if(!edit) return source.voxel(f,x,z,s);
                    long key=((long)f.ordinal()*shape.size()+z)*shape.size()+x;
                    key=key*128+s;
                    var prior=derived.get(key); if(prior!=null) return prior;
                    dev.starfold.worldgen.lod.VoxelColumn result;
                    if(s==1) {
                        var capture=near.get(GroundOwnership.key(shape,f.ordinal(),x,z));
                        if(capture==null) result=source.voxel(f,x,z,1);
                        else {
                            var runs=new ArrayList<dev.starfold.worldgen.lod.VoxelColumn.Run>();
                            for(int i=capture.runCount(x&15,z&15)-1;i>=0;i--) {
                                var r=capture.run(x&15,z&15,i);
                                if(!r.material().isAir()) runs.add(new dev.starfold.worldgen.lod.VoxelColumn.Run(r.bottom(),r.top()+1,r.material()));
                            }
                            result=dev.starfold.worldgen.lod.VoxelColumn.compose(runs);
                        }
                    } else {
                        var children=new ArrayList<dev.starfold.worldgen.lod.VoxelColumn>(4);
                        for(int dz=0;dz<2;dz++) for(int dx=0;dx<2;dx++)
                            if(x+dx*s/2<shape.size() && z+dz*s/2<shape.size()) children.add(column(f,x+dx*s/2,z+dz*s/2,s/2));
                        result=dev.starfold.worldgen.lod.VoxelColumn.reduce(children,s);
                    }
                    derived.put(key,result); return result;
                }
                public GroundFarMesh.Surface cell(Face f,int x,int z,int s) { return GroundFarMesh.Surface.voxel(column(f,x,z,s)); }
            };
        }
        throw new IllegalStateException("Terrain profile required before meshing");
    }
}
