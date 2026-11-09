package dev.starfold.worldgen.surface;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import java.util.*;

/** Replacement point summaries. Fork on the owner thread before editing a published snapshot. */
public final class SurfaceOverlay {
    public record Chunk(Face face, int u, int v) {}
    public record Address(Face face, int u, int v, int level) {}
    private final SurfaceKey key;
    private final SurfaceFunction function;
    private final Map<Chunk,SurfaceFunction.Cell[]> chunks = new HashMap<>();
    private final Map<Address,SurfaceFunction.Cell> merged = new java.util.concurrent.ConcurrentHashMap<>();
    @FunctionalInterface public interface Cells { SurfaceFunction.Cell cell(Face face,int u,int v,int level); }
    private Cells fallback;
    public SurfaceOverlay(SurfaceCache base, SurfaceFunction function) {
        if (!base.key().equals(SurfaceKey.of(function.terrain()))) throw new IllegalArgumentException("Overlay base key");
        this.key = base.key(); this.function = function; this.fallback=base::cell;
    }
    public SurfaceOverlay(SurfaceCache base,SurfaceFunction function,Cells fallback) {
        this(base,function); this.fallback=fallback;
    }
    private SurfaceOverlay(SurfaceKey key,SurfaceFunction function,Cells fallback) {
        this.key=key; this.function=function; this.fallback=fallback;
    }
    public SurfaceOverlay fork() {
        var next=new SurfaceOverlay(key,function,fallback);
        next.chunks.putAll(chunks); next.merged.putAll(merged); return next;
    }
    public SurfaceFunction.Cell point(Face face, int u, int v) {
        var chunk = new Chunk(face,u/16*16,v/16*16);
        var edit = chunks.get(chunk);
        return edit == null ? function.point(face,u,v) : edit[(v%16)*16+u%16];
    }
    public SurfaceFunction.Cell cell(Face face, int u, int v, int level) {
        SurfaceCache.level(level);
        var a=new Address(face,u,v,level);
        if(!intersectsEdit(a)) return fallback.cell(face,u,v,level);
        return merged.computeIfAbsent(a,ignored->{
            var sum=new SurfaceFunction.Aggregate(); int size=key.size();
            for(int z=0;z<level && v+z<size;z++) for(int x=0;x<level && u+x<size;x++) sum.add(point(face,u+x,v+z));
            return sum.cell();
        });
    }
    /** Replacing a chunk affects 16 L4 cells, four L8 cells, and one each at L16/32/64.
     * null removes an edit. Derived cells are evaluated lazily by the meshing worker. */
    public Set<Address> replace(Chunk chunk, SurfaceFunction.Cell[] points) {
        if (chunk.face() == null || chunk.u() < 0 || chunk.v() < 0 || chunk.u()%16 != 0 || chunk.v()%16 != 0
                || chunk.u()+16 > key.size() || chunk.v()+16 > key.size())
            throw new IllegalArgumentException("Owned aligned chunk required");
        if (points != null) {
            if (points.length != 256) throw new IllegalArgumentException("256 point summaries required");
            points = points.clone();
            for (var p : points) {
                Objects.requireNonNull(p);
                if (!Double.isFinite(p.height()) || p.materials().size() != 1 || p.materials().getFirst().share() != 1
                        || (p.canopyCoverage() != 0 && p.canopyCoverage() != 1)
                        || (p.canopyCoverage() == 1 && (p.canopyMaterials().size() != 1 || p.canopyMaterials().getFirst().share() != 1)))
                    throw new IllegalArgumentException("Point summary required");
            }
            chunks.put(chunk,points);
        } else chunks.remove(chunk);
        var changed = new LinkedHashSet<Address>();
        int size = key.size();
        for (int level = 4; level <= 64; level *= 2) {
            int u0 = chunk.u()/level*level, v0 = chunk.v()/level*level;
            for (int v = v0; v < chunk.v()+16; v += level) for (int u = u0; u < chunk.u()+16; u += level) {
                var address = new Address(chunk.face(),u,v,level);
                // Compact base cells discarded rare materials. Lazily re-integrate this footprint, never lossy children.
                merged.remove(address);
                changed.add(address);
            }
        }
        // A fully restored region needs no derived override.
        merged.entrySet().removeIf(e -> changed.contains(e.getKey()) && !intersectsEdit(e.getKey()));
        return Set.copyOf(changed);
    }
    private boolean intersectsEdit(Address a) {
        // At L64 there are at most 16 chunk lookups, regardless of the planet's total edit count.
        for(int v=a.v()/16*16;v<a.v()+a.level();v+=16)
            for(int u=a.u()/16*16;u<a.u()+a.level();u+=16)
                if(chunks.containsKey(new Chunk(a.face(),u,v))) return true;
        return false;
    }
}
