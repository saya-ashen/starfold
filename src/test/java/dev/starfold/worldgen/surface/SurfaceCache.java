package dev.starfold.worldgen.surface;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

/** Offline base layer. Owned, face-local cells clip at the edge; they never wrap into another face.
 * Immutable after publication. Level 1/2 are recomputed. Retained cells are quantized, not exact histograms. */
public final class SurfaceCache {
    public static final int VERSION = 2, MIN_LEVEL = 4, MAX_LEVEL = 64;
    private static final int MAGIC = 0x53464348, BYTES = 16;
    private final SurfaceKey key;
    private final Plane[] planes;
    public SurfaceCache(SurfaceKey key) {
        this.key = key;
        planes = new Plane[5];
        for (int i = 0; i < planes.length; i++) planes[i] = new Plane(key.size(),MIN_LEVEL<<i);
    }
    public SurfaceKey key() { return key; }
    static void level(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL || (level & (level-1)) != 0)
            throw new IllegalArgumentException("Retained level must be 4,8,16,32,64");
    }
    Plane plane(int level) { level(level); return planes[Integer.numberOfTrailingZeros(level)-2]; }
    public int width(int level) { return plane(level).width; }
    public SurfaceFunction.Cell cell(Face face, int u, int v, int level) {
        var p = plane(level);
        return p.cell(p.index(face,u,v));
    }
    public long retainedBytes() {
        long n = 0;
        for (var p : planes) n += p.data.length;
        return n; // Primitive payload; JVM array/object headers are deliberately excluded.
    }
    private record Patch(Face face, int u, int v, double distance) {}

    /** Blocking, bounded-worker build. 64x64 patch centres ordered by spherical chord distance.
     * Scheduling is nearest-first; concurrent completion order is not promised. Workers share no mutable summaries. */
    public static SurfaceCache build(SurfaceFunction f, int threads, Face nearFace, int nearU, int nearV)
            throws InterruptedException {
        if (threads < 1 || threads > 256) throw new IllegalArgumentException("Thread count");
        var cache = new SurfaceCache(SurfaceKey.of(f.terrain()));
        cache.fill(f,threads,nearFace,nearU,nearV,p -> {});
        return cache;
    }

    /** Writes disjoint patches; callback must publish each patch with a happens-before edge. */
    public void fill(SurfaceFunction f,int threads,Face nearFace,int nearU,int nearV,
                     java.util.function.IntConsumer published) throws InterruptedException {
        if(threads<1 || threads>256 || !baselineKey(f)) throw new IllegalArgumentException("Surface build policy/threads");
        var t=f.terrain();
        var cache=this;
        var near = t.unit(nearFace,nearU+.5,nearV+.5);
        var patches = new ArrayList<Patch>();
        int size = t.shape().size();
        for (var face : Face.VALUES) for (int v = 0; v < size; v += 64) for (int u = 0; u < size; u += 64) {
            var centre = t.unit(face,u+Math.min(64,size-u)/2.0,v+Math.min(64,size-v)/2.0);
            patches.add(new Patch(face,u,v,1-centre.dot(near)));
        }
        patches.sort(Comparator.comparingDouble(Patch::distance));
        var next = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(threads);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int worker = 0; worker < threads; worker++) futures.add(pool.submit(() -> {
                // Only one patch's exact summaries survive until packing. Never merge truncated parents.
                var sums = new Sum[5][];
                for (int l = 0; l < 5; l++) {
                    int width = 16 >> l;
                    sums[l] = new Sum[width*width];
                    Arrays.setAll(sums[l], i -> new Sum());
                }
                int index;
                while (!Thread.currentThread().isInterrupted() && (index = next.getAndIncrement()) < patches.size()) {
                    var patch = patches.get(index);
                    for (var row : sums) for (var s : row) s.clear();
                    for (int v = patch.v(); v < Math.min(size,patch.v()+64); v += 4)
                        for (int u = patch.u(); u < Math.min(size,patch.u()+64); u += 4) {
                            var sum = sums[0][(v-patch.v())/4*16+(u-patch.u())/4];
                            for (int dz = 0; dz < 4; dz++) for (int dx = 0; dx < 4; dx++)
                                sum.add(f.point(patch.face(),u+dx,v+dz));
                        }
                    for (int l = 0; l < 5; l++) {
                        int width = 16 >> l, level = 4 << l;
                        var p = cache.plane(level);
                        for (int z = 0; z < width && patch.v()+z*level < size; z++)
                            for (int x = 0; x < width && patch.u()+x*level < size; x++) {
                                var sum = sums[l][z*width+x];
                                p.put(p.index(patch.face(),patch.u()+x*level,patch.v()+z*level),sum);
                                if (l < 4) sums[l+1][z/2*(width/2)+x/2].add(sum);
                            }
                    }
                    published.accept(patch.face().ordinal()*1024+patch.u()/64+32*(patch.v()/64));
                }
            }));
            for (var future : futures) future.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Surface build failed",e.getCause());
        } finally {
            for (var future : futures) future.cancel(true);
            pool.shutdownNow();
            if (!pool.awaitTermination(1,TimeUnit.MINUTES)) throw new IllegalStateException("Surface workers did not stop");
        }
    }
    private boolean baselineKey(SurfaceFunction f) { return key.equals(SurfaceKey.of(f.terrain())); }

    public static SurfaceFunction.Cell estimate(SurfaceFunction f, Face face, int u, int v, int level) {
        return estimate(f,face,u,v,level,16);
    }

    /** k must be a square. One deterministic jittered sample per stratum; small cells use exact sampling. */
    public static SurfaceFunction.Cell estimate(SurfaceFunction f, Face face, int u, int v, int level, int k) {
        level(level);
        int grid = (int)Math.sqrt(k), size = f.terrain().shape().size();
        if (grid < 1 || grid*grid != k || k > 4096 || u < 0 || v < 0 || u >= size || v >= size)
            throw new IllegalArgumentException("Estimate bounds/square sample count");
        int w = Math.min(level,size-u), h = Math.min(level,size-v);
        var sum = new SurfaceFunction.Aggregate();
        if (w <= grid || h <= grid) {
            for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) sum.add(f.point(face,u+x,v+z));
        } else for (int z = 0; z < grid; z++) for (int x = 0; x < grid; x++) {
            int x0 = x*w/grid, x1 = (x+1)*w/grid, z0 = z*h/grid, z1 = (z+1)*h/grid;
            long hash = Scatter.mix(f.terrain().seed() ^ ((long)face.ordinal()<<56) ^ ((long)(u+x0)<<28) ^ (v+z0));
            int sx = x0+(int)Long.remainderUnsigned(hash,x1-x0);
            int sz = z0+(int)Long.remainderUnsigned(Scatter.mix(hash),z1-z0);
            // Weight unequal edge strata by their area, not by the number of strata.
            sum.add(f.point(face,u+sx,v+sz),(x1-x0)*(z1-z0));
        }
        return sum.cell();
    }

    /** Compressed local cache. Registry state descriptions detect numeric-ID drift across installations. */
    public void save(Path path) throws IOException {
        var temp = Files.createTempFile(path.toAbsolutePath().getParent(),"surface-cache-",".tmp");
        try {
            try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp))))) {
                out.writeInt(MAGIC); out.writeInt(VERSION);
                out.writeLong(key.seed()); out.writeUTF(key.type()); out.writeInt(key.size()); out.writeUTF(key.algorithmVersion());
                var ids = new TreeSet<Integer>();
                for (var p : planes) for (int i = 0; i < p.data.length; i += BYTES) {
                    ids.add(p.unsigned(i+5));
                    if (p.data[i+11] != 0) ids.add(p.unsigned(i+7));
                    if (p.data[i+12] != 0) ids.add(p.unsigned(i+9));
                }
                out.writeInt(ids.size());
                for (int id : ids) { out.writeInt(id); out.writeUTF(Block.stateById(id).toString()); }
                for (var p : planes) out.write(p.data);
            }
            Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
    public static SurfaceCache load(Path path, SurfaceKey expected) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Cache format mismatch");
            var key = new SurfaceKey(in.readLong(),in.readUTF(),in.readInt(),in.readUTF());
            if (!key.equals(expected)) throw new IOException("Cache key mismatch");
            int count = in.readInt();
            if (count < 0 || count > 65536) throw new IOException("Invalid state dictionary");
            var ids = new BitSet(65536);
            for (int i = 0; i < count; i++) {
                int id = in.readInt();
                if (id < 0 || id >= 65536 || ids.get(id) || !Block.stateById(id).toString().equals(in.readUTF()))
                    throw new IOException("Cache registry mismatch");
                ids.set(id);
            }
            var cache = new SurfaceCache(key);
            for (var p : cache.planes) {
                in.readFully(p.data);
                for (int i = 0; i < p.data.length; i += BYTES) {
                    int first = Byte.toUnsignedInt(p.data[i+13]), second = Byte.toUnsignedInt(p.data[i+11]);
                    if (first == 0 || second > first || first+second > 256
                            || !ids.get(p.unsigned(i+5))
                            || second != 0 && !ids.get(p.unsigned(i+7))
                            || p.data[i+12] != 0 && !ids.get(p.unsigned(i+9)))
                        throw new IOException("Invalid compact cell");
                }
            }
            if (in.read() != -1) throw new IOException("Trailing cache bytes");
            return cache;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid cache header",e); }
    }

    static final class Plane {
        final int size, level, width;
        // 0:h Q9.6, 2:linear RGB24, 5/7/9:ground1/ground2/canopy IDs,
        // 11:share2, 12:canopy coverage, 13:share1, 14:canopy top Q9.6.
        final byte[] data;
        Plane(int size, int level) {
            this.size = size; this.level = level; width = (size+level-1)/level;
            int n = 6*width*width;
            data = new byte[n*BYTES];
        }
        int index(Face face, int u, int v) {
            if (face == null || u < 0 || v < 0 || u >= size || v >= size || u%level != 0 || v%level != 0)
                throw new IllegalArgumentException("Owned aligned cache cell required");
            return (face.ordinal()*width+v/level)*width+u/level;
        }
        int area(int index) {
            int x = index%width, z = (index/width)%width;
            return Math.min(level,size-x*level)*Math.min(level,size-z*level);
        }
        int unsigned(int offset) { return (data[offset]&255)<<8 | data[offset+1]&255; }
        void shortAt(int offset, int value) { data[offset] = (byte)(value>>8); data[offset+1] = (byte)value; }
        void heightAt(int offset, double height) {
            long fixed = Math.round(height*64);
            if (fixed < Short.MIN_VALUE || fixed > Short.MAX_VALUE) throw new IllegalArgumentException("Cache height range");
            shortAt(offset,(int)fixed);
        }
        void put(int i, Sum s) {
            int o = i*BYTES;
            heightAt(o,s.height/(double)s.count);
            int rgb = SurfaceColor.pack(s.r/s.count,s.g/s.count,s.b/s.count);
            data[o+2] = (byte)(rgb>>16); data[o+3] = (byte)(rgb>>8); data[o+4] = (byte)rgb;
            var ground = s.ranked(s.material);
            shortAt(o+5,ground.getFirst().getKey());
            data[o+13] = (byte)Math.max(1,Math.round(255.*ground.getFirst().getValue()/s.count));
            if (ground.size() > 1) {
                shortAt(o+7,ground.get(1).getKey());
                data[o+11] = (byte)Math.round(255.*ground.get(1).getValue()/s.count);
            }
            data[o+12] = (byte)Math.round(255.*s.canopy/s.count);
            if (s.canopy > 0) {
                shortAt(o+9,s.ranked(s.crown).getFirst().getKey());
                heightAt(o+14,s.canopyTop/(double)s.canopy);
            }
        }
        SurfaceFunction.Cell cell(int i) {
            int o = i*BYTES;
            var materials = new ArrayList<SurfaceFunction.Coverage>(2);
            materials.add(new SurfaceFunction.Coverage(Block.stateById(unsigned(o+5)),(data[o+13]&255)/255.));
            if (data[o+11] != 0) materials.add(new SurfaceFunction.Coverage(Block.stateById(unsigned(o+7)),(data[o+11]&255)/255.));
            double canopy = (data[o+12]&255)/255.;
            return new SurfaceFunction.Cell((short)unsigned(o)/64.,materials,canopy,
                    canopy == 0 ? 0 : (short)unsigned(o+14)/64.,
                    canopy == 0 ? List.of() : List.of(new SurfaceFunction.Coverage(Block.stateById(unsigned(o+9)),1)),
                    (data[o+2]&255)<<16 | (data[o+3]&255)<<8 | data[o+4]&255);
        }
    }
    /** Patch-local exact accumulators. Histograms die at the patch boundary, not at the planet boundary. */
    static final class Sum {
        int count, height, canopyTop, canopy;
        double r, g, b;
        final Map<Integer,Integer> material = new HashMap<>(), crown = new HashMap<>();
        void clear() { count = height = canopyTop = canopy = 0; r = g = b = 0; material.clear(); crown.clear(); }
        void add(Sum s) {
            count += s.count; height += s.height; canopyTop += s.canopyTop; canopy += s.canopy;
            r += s.r; g += s.g; b += s.b;
            s.material.forEach((id,n) -> material.merge(id,n,Integer::sum));
            s.crown.forEach((id,n) -> crown.merge(id,n,Integer::sum));
        }
        List<Map.Entry<Integer,Integer>> ranked(Map<Integer,Integer> source) {
            return source.entrySet().stream().sorted(Map.Entry.<Integer,Integer>comparingByValue().reversed()
                    .thenComparing(e -> Block.stateById(e.getKey()).toString())).toList();
        }
        void add(SurfaceFunction.Cell c) {
            count++; height += (int)c.height();
            int rgb = SurfaceColor.color(c.materials().getFirst().block());
            r += SurfaceColor.linear(rgb>>16&255); g += SurfaceColor.linear(rgb>>8&255); b += SurfaceColor.linear(rgb&255);
            if (c.materials().size() != 1 || c.materials().getFirst().share() != 1 || (c.canopyCoverage() != 0 && c.canopyCoverage() != 1))
                throw new IllegalArgumentException("Expected point summary");
            addState(c.materials().getFirst().block(),material);
            if (c.canopyCoverage() == 1) {
                canopy++; canopyTop += (int)c.canopyTop();
                addState(c.canopyMaterials().getFirst().block(),crown);
            }
        }
        private void addState(BlockState state, Map<Integer,Integer> target) {
            int id = Block.getId(state);
            if (id < 0 || id > 65535) throw new IllegalArgumentException("State ID exceeds packed cache format");
            target.merge(id,1,Integer::sum);
        }
    }
}
