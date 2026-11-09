package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.surface.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicIntegerArray;

/** Small client-only planet LRU. A single coordinator bounds concurrent planet builds. */
public final class GroundBaseCache {
    /** Non-blocking view of the renderer's shared coarse cache; missing patches stay unpublished. */
    public static dev.starfold.api.PlanetMapApi.Surface surface(PlanetTerrain terrain, int step) {
        var entry = get(terrain, null);
        var cache = entry.cache;
        int width = (terrain.shape().size() + step - 1) / step;
        return new dev.starfold.api.PlanetMapApi.Surface() {
            public int width() { return width; }
            public int step() { return step; }
            public double progress() {
                int count = 0, n = (terrain.shape().size() + 63) / 64;
                for (int f = 0; f < 6; f++) for (int v = 0; v < n; v++) for (int u = 0; u < n; u++)
                    count += entry.ready.get(f * 1024 + u + v * 32);
                return (double) count / (6 * n * n);
            }
            public int waterDepth(int face, int u, int v) {
                var column = cache == null ? null : cache.column(Face.VALUES[face], u * step, v * step, step);
                if (column == null || column.runs().isEmpty()) return -1;
                var top = column.runs().getLast();
                var fluid = top.block().getFluidState().getType();
                return fluid == net.minecraft.world.level.material.Fluids.WATER
                        || fluid == net.minecraft.world.level.material.Fluids.FLOWING_WATER ? top.top() - top.bottom() : -1;
            }
            public dev.starfold.api.PlanetMapApi.Cell cell(int face, int u, int v) {
                var column = cache == null ? null : cache.column(Face.VALUES[face], u * step, v * step, step);
                if (column == null) return null;
                var runs = column.runs();
                var top = runs.isEmpty() ? null : runs.getLast();
                return new dev.starfold.api.PlanetMapApi.Cell(top == null ? 0xff101820
                        : 0xff000000 | SurfaceColor.color(top.block()), top == null ? -128 : top.top() - 128);
            }
        };
    }
    // A byte budget, not a planet count: a sky can hold ten small bodies, and a count below that evicts and refills them in a loop.
    static final long BUDGET=256L*1024*1024;
    // Whole-planet background work must leave CPU for nearby chunks, even on a fresh cache.
    static final int THREADS=Math.clamp(Integer.getInteger("starfold.surfaceThreads",
            Math.min(2,Math.max(1,Runtime.getRuntime().availableProcessors()/2-1))),1,256);
    private static final ExecutorService coordinator=Executors.newSingleThreadExecutor(r->{
        var t=new Thread(r,"starfold-surface-build"); t.setDaemon(true); return t;
    });
    private static SurfaceKey observed;
    static synchronized void observe(PlanetTerrain t) { observed=t==null ? null : SurfaceKey.of(t); }
    private static final LinkedHashMap<SurfaceKey,Entry> planets=new LinkedHashMap<>(4,.75f,true);
    static synchronized Entry get(PlanetTerrain terrain,Point camera) {
        var key=SurfaceKey.of(terrain);
        var entry=planets.get(key);
        if(entry!=null) return entry;
        entry=new Entry(terrain);
        planets.put(key,entry);
        for(var it=planets.entrySet().iterator(); it.hasNext() && retainedBytes()>BUDGET;) {
            var victim=it.next();
            if(victim.getKey().equals(observed) || victim.getValue()==entry) continue;
            if (Boolean.getBoolean("starfold.chunkPerf"))
                org.slf4j.LoggerFactory.getLogger(GroundBaseCache.class).info("PROBE PERF base-evict {} retained {} budget {}", victim.getKey(), retainedBytes(), BUDGET);
            victim.getValue().close();
            it.remove();
        }
        var e=entry;
        var face=Face.TOP; int u=key.size()/2,v=u;
        if(camera!=null) {
            face=Arrays.stream(Face.VALUES).max(Comparator.comparingDouble(f->camera.dot(f.normal))).orElseThrow();
            double n=Math.max(1,camera.dot(face.normal)), half=key.size()/2.;
            u=(int)Math.clamp(half+camera.dot(face.u)*half/n,0,key.size()-1);
            v=(int)Math.clamp(half+camera.dot(face.v)*half/n,0,key.size()-1);
        }
        final Face focus=face; final int x=u,z=v;
        e.future=coordinator.submit(()->e.build(()->{
            var cache=e.cache;
            if(cache!=null && !GroundBaseDisk.load(key,cache,p->e.ready.set(p,1))) {
                cache.fill(e.function,THREADS,focus,x,z,p->e.ready.set(p,1));
                if(cache.complete()) GroundBaseDisk.save(key,cache);
            }
            return null;
        }));
        return e;
    }
    static synchronized void clear() {
        if (Boolean.getBoolean("starfold.chunkPerf"))
            org.slf4j.LoggerFactory.getLogger(GroundBaseCache.class).info("PROBE PERF base-clear entries {} retained {}", planets.size(), retainedBytes(), new Exception("cache clear caller"));
        planets.values().forEach(Entry::close); planets.clear(); observed=null;
    }
    static synchronized long fineBytes() { return planets.values().stream().mapToLong(Entry::fineBytes).sum(); }
    static synchronized long retainedBytes() {
        return planets.values().stream().mapToLong(e->(e.cache==null ? 0:e.cache.retainedBytes())+e.fineBytes()).sum();
    }
    static final class Entry {
        enum State { QUEUED, BUILDING, READY, FAILED, CLOSED }
        volatile State state = State.QUEUED;
        volatile int attempts;
        volatile Exception failure;
        static final int MAX_ATTEMPTS = 3;
        final SurfaceFunction function;

        void build(Callable<Void> work) {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                synchronized (this) {
                    if (state == State.CLOSED) return;
                    attempts = attempt;
                    state = State.BUILDING;
                }
                try {
                    work.call();
                    synchronized (this) {
                        if (state != State.CLOSED) { failure = null; state = State.READY; }
                    }
                    return;
                } catch (InterruptedException cancelled) {
                    state = State.CLOSED;
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception failed) {
                    synchronized (this) {
                        failure = failed;
                        if (state == State.CLOSED) return;
                        state = State.FAILED;
                    }
                    org.slf4j.LoggerFactory.getLogger(GroundBaseCache.class).error(
                            "Client surface build failed: seed {} attempt {}/{}", function.terrain().seed(), attempt, MAX_ATTEMPTS, failed);
                }
            }
        }
        static final long FINE_LIMIT = 64L * 1024 * 1024;
        final LinkedHashMap<Long,long[]> fine = new LinkedHashMap<>(4096,.75f,true);
        long finePayload;
        synchronized long fineBytes() { return finePayload; }
        synchronized dev.starfold.worldgen.lod.VoxelColumn fine(Face f,int u,int v) {
            long key = ((long)f.ordinal()*2048+u)*2048+v;
            var packed = fine.get(key);
            if (packed == null) {
                packed = dev.starfold.worldgen.lod.VoxelColumn.visible(function,f,u,v).packed();
                fine.put(key,packed);
                finePayload += 80+8L*packed.length;
                while (finePayload > FINE_LIMIT) {
                    var first = fine.pollFirstEntry();
                    finePayload -= 80+8L*first.getValue().length;
                }
            }
            return dev.starfold.worldgen.lod.VoxelColumn.unpack(packed,0,packed.length);
        }
        volatile dev.starfold.worldgen.lod.VoxelSurfaceCache cache;
        final AtomicIntegerArray ready=new AtomicIntegerArray(6144);
        Future<?> future;
        Entry(PlanetTerrain terrain) { function=new SurfaceFunction(terrain); cache=new dev.starfold.worldgen.lod.VoxelSurfaceCache(terrain.shape().size()); }
        synchronized void close() { state=State.CLOSED; if(future!=null) future.cancel(true); cache=null; }
        dev.starfold.worldgen.lod.VoxelColumn voxel(Face f,int u,int v,int step) {
            if(step>=4) {
                var built=cache;
                var c=built==null ? null : built.column(f,u,v,step);
                if(c!=null) return c;
                // Temporary, explicitly coarse startup coverage; replaced on patch publication.
                return dev.starfold.worldgen.lod.VoxelColumn.reduce(List.of(
                        dev.starfold.worldgen.lod.VoxelColumn.visible(function,f,
                                Math.min(function.terrain().shape().size()-1,u+step/2),
                                Math.min(function.terrain().shape().size()-1,v+step/2))),step);
            }
            if(step==1) return fine(f,u,v);
            var children=new ArrayList<dev.starfold.worldgen.lod.VoxelColumn>(4);
            for(int z=0;z<2;z++) for(int x=0;x<2;x++) children.add(voxel(f,u+x,v+z,1));
            return dev.starfold.worldgen.lod.VoxelColumn.reduce(children,2);
        }
        GroundFarMesh.Surface surface(Face f,int u,int v,int step) {
            return GroundFarMesh.Surface.voxel(voxel(f,u/step*step,v/step*step,step));
        }
    }
    private GroundBaseCache() {}
}
