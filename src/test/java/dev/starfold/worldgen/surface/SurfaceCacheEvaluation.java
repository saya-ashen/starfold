package dev.starfold.worldgen.surface;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worlds.fixtures.ExperienceFixtures;
import dev.starfold.universe.PlanetType;
import net.minecraft.world.level.block.state.BlockState;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Full-size offline benchmark; deliberately outside check. All measured builds actually evaluate every column. */
public final class SurfaceCacheEvaluation {
    private static final Path OUTPUT = Path.of("build/surface-cache-eval");
    private static volatile double sink;
    public static void run() throws Exception {
        long started = System.nanoTime();
        ExperienceFixtures.boot();
        SurfaceTestTags.bind();
        Files.createDirectories(OUTPUT);
        var report = new StringBuilder("# Whole-planet surface-cache measurements\n\nSeed 42; largest size of every starfold_worlds type (home 2048). Registry-only bootstrap; no game started.\n\n")
                .append("Build wall time includes allocation, priority sorting, every base point and child merging; excludes GC requested before each build, save/load and images. Fixed 64x64 patch work items, nearest to NORTH centre first. JVM max heap ")
                .append(Runtime.getRuntime().maxMemory()).append(" bytes; available processors ").append(Runtime.getRuntime().availableProcessors())
                .append(". Heap peak is absolute used heap sampled every 5ms, including registry baseline and transient garbage (a sampled lower bound, not RSS). Retained bytes are exact primitive-array payload, excluding small array/object headers and key. No retained level 1/2. Save is gzip, load includes validation and allocation; filesystem is warm, not a cold disk test. One build per thread count; not statistically independent repetitions.\n\n")
                .append("Visible timing: 250ms warmup, then three warm and five measured 1024-query batches, median ns/column; every invocation includes side/object/volume runs. Point timing is the cap/canopy-only projection used by the cache; it intentionally does not compute unused side intervals. Timings include allocation/GC and consume results. Generation checks: 10000 columns/type at smallest size, against independent full generated columns.\n\n")
                .append("Placeholder k=16, deterministic jittered 4x4 strata, 128 uniformly selected aligned cells per level across all faces. Error includes clipped edge cells. Coverage L1 uses cap materials; canopy coverage/top errors and final canopy-blended RGB channel MAE are separate. RGB excludes hillshade. L4 placeholder is exact. Images use identical height shading, exact left / placeholder right; NORTH whole-face at L16/L64.\n\n");
        var holders = ExperienceFixtures.registryLookup().lookupOrThrow(PlanetType.KEY).listElements()
                .filter(h -> h.key().location().getNamespace().equals("starfold_worlds"))
                .sorted(Comparator.comparing(h -> h.key().location().getPath())).toList();
        String only = System.getProperty("surfaceCacheType","");
        for (var holder : holders) {
            String name = holder.key().location().getPath();
            if (!only.isEmpty() && !only.equals(name)) continue;
            var type = holder.value();
            System.out.println("surfaceCacheEval: "+name+" size="+type.maxSize());
            int sectionStart = report.length();
            report.append("## ").append(name).append(" (size ").append(type.maxSize()).append(")\n\n");
            var small = type.planet(42,Optional.of(type.minSize()));
            report.append(SurfaceEvaluation.consistency(small)).append('\n');
            var f = new SurfaceFunction(type.planet(42,Optional.of(type.maxSize())).terrain());
            int size = f.terrain().shape().size();
            long until = System.nanoTime()+250_000_000;
            int warm = 0;
            do { sink += f.visible(Face.values()[warm%6],(warm*193+37)%size,(warm*307+91)%size).top(); warm++; }
            while (System.nanoTime() < until);
            long ns = SurfaceEvaluation.median(1024,i -> {
                var c = f.visible(Face.values()[i%6],(i*193+37)%size,(i*307+91)%size);
                sink += c.top()+c.sides().size()+c.objects().size();
            });
            long point = SurfaceEvaluation.median(1024,i -> sink += f.point(Face.values()[i%6],(i*193+37)%size,(i*307+91)%size).height());
            report.append("Full visible median: **").append(ns).append(" ns/column**; cap/canopy point median: ").append(point).append(" ns/column. Target <=2000 ns: ").append(ns <= 2000 ? "met" : "MISSED").append(".\n\n")
                    .append("| Threads | Build wall s | Sampled peak heap MiB | Retained bytes |\n|---:|---:|---:|---:|\n");
            var path = OUTPUT.resolve(name);
            Files.createDirectories(path);
            for (int threads : new int[]{1,4,8,12}) {
                System.gc();
                var peak = new AtomicLong(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                var sampler = Executors.newSingleThreadScheduledExecutor();
                sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max),0,5,TimeUnit.MILLISECONDS);
                SurfaceCache cache;
                long start = System.nanoTime();
                double seconds;
                try {
                    cache = SurfaceCache.build(f,threads,Face.NORTH,size/2,size/2);
                    seconds = (System.nanoTime()-start)/1e9;
                    peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max);
                } finally {
                    sampler.shutdownNow();
                    if (!sampler.awaitTermination(10,TimeUnit.SECONDS)) throw new IllegalStateException("Heap sampler did not stop");
                }
                report.append(String.format(Locale.ROOT,"| %d | %.3f | %.2f | %d |%n",threads,seconds,peak.get()/1048576.,cache.retainedBytes()));
                System.out.printf(Locale.ROOT,"  %d threads: %.3fs, peak %.1fMiB, retained %d%n",threads,seconds,peak.get()/1048576.,cache.retainedBytes());
                Files.writeString(OUTPUT.resolve("summary.md"),report);
                if (threads == 12) {
                    var file = path.resolve("base.sfc.gz");
                    long saveStart = System.nanoTime(); cache.save(file);
                    double save = (System.nanoTime()-saveStart)/1e9;
                    long bytes = Files.size(file);
                    long loadStart = System.nanoTime();
                    var loaded = SurfaceCache.load(file,cache.key());
                    double load = (System.nanoTime()-loadStart)/1e9;
                    for (int level = 4; level <= 64; level *= 2) for (var face : Face.values())
                        for (int v = 0; v < size; v += level) for (int u = 0; u < size; u += level)
                            if (!cache.cell(face,u,v,level).equals(loaded.cell(face,u,v,level))) throw new AssertionError("File roundtrip");
                    report.append(String.format(Locale.ROOT,"%nFile size: %d bytes; save %.3f s; load %.3f s. Every loaded cell equals built cache.%n%n",bytes,save,load));
                    errors(f,cache,report);
                    for (int level : new int[]{16,64}) image(f,cache,level,path.resolve("top-"+level+".png"));
                    edit(f,cache,path,report);
                }
            }
            Files.writeString(path.resolve("results.md"),report.substring(sectionStart));
            Files.writeString(OUTPUT.resolve("summary.md"),report);
        }
        report.append(String.format(Locale.ROOT,"\nTotal evaluator wall time including registry bootstrap: %.3f s.\n",(System.nanoTime()-started)/1e9));
        Files.writeString(OUTPUT.resolve("summary.md"),report);
        System.out.println("surfaceCacheEval complete: "+OUTPUT.resolve("summary.md"));
    }
    private static void errors(SurfaceFunction f, SurfaceCache cache, StringBuilder report) {
        report.append("| Level | Estimate ns/cell | Cache read ns/cell | Height MAE | Height max | Cap coverage L1 | RGB MAE | Canopy coverage MAE | Canopy top MAE |\n|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (int level = 4; level <= 64; level *= 2) {
            final int l = level, width = cache.width(l);
            long ns = SurfaceEvaluation.median(128,i -> sink += SurfaceCache.estimate(f,Face.values()[i%6],(i*193%width)*l,(i*307%width)*l,l,16).height());
            long read = SurfaceEvaluation.median(1024,i -> sink += cache.cell(Face.values()[i%6],(i*193%width)*l,(i*307%width)*l,l).height());
            var random = new Random(917+l);
            double height = 0, max = 0, coverage = 0, rgb = 0, canopy = 0, canopyTop = 0;
            double compactGround = 0, compactDisplay = 0;
            for (int i = 0; i < 128; i++) {
                int u = random.nextInt(width)*l, v = random.nextInt(width)*l;
                var face = Face.values()[i%6];
                var a = cache.cell(face,u,v,l);
                // Independently rebuild the old exact histogram, never a truncated child merge.
                var exact = new SurfaceFunction.Aggregate();
                for (int z = v; z < Math.min(size(f),v+l); z++)
                    for (int x = u; x < Math.min(size(f),u+l); x++) exact.add(f.point(face,x,z));
                var ecell = exact.cell();
                SurfaceCacheTest.compactEqual(ecell,a);
                for (int channel = 0; channel < 3; channel++)
                    compactGround += Math.abs(SurfaceColor.channel(a,channel)-SurfaceColor.channel(ecell,channel))*255/3.;
                int ca = SurfaceEvaluation.color(a), ce = SurfaceEvaluation.color(ecell);
                compactDisplay += (Math.abs((ca>>16&255)-(ce>>16&255))+Math.abs((ca>>8&255)-(ce>>8&255))+Math.abs((ca&255)-(ce&255)))/3.;
                var b = SurfaceCache.estimate(f,face,u,v,l,16);
                double e = Math.abs(a.height()-b.height());
                height += e; max = Math.max(max,e);
                var delta = new HashMap<BlockState,Double>();
                for (var m : a.materials()) delta.merge(m.block(),m.share(),Double::sum);
                for (var m : b.materials()) delta.merge(m.block(),-m.share(),Double::sum);
                coverage += delta.values().stream().mapToDouble(Math::abs).sum();
                int ac = SurfaceEvaluation.color(a), bc = SurfaceEvaluation.color(b);
                rgb += (Math.abs((ac>>16&255)-(bc>>16&255))+Math.abs((ac>>8&255)-(bc>>8&255))+Math.abs((ac&255)-(bc&255)))/3.;
                canopy += Math.abs(a.canopyCoverage()-b.canopyCoverage());
                canopyTop += Math.abs(a.canopyTop()-b.canopyTop());
            }
            report.append(String.format(Locale.ROOT,"| %d | %d | %d | %.5f | %.5f | %.5f | %.5f | %.5f | %.5f |%n",l,ns,read,height/128,max,coverage/128,rgb/128,canopy/128,canopyTop/128));
            report.append(String.format(Locale.ROOT,"Compact vs old exact histogram L%d: ground linear UNORM8 channel MAE %.5f; canopy-blended sRGB channel MAE %.5f (128 cells).%n",l,compactGround/128,compactDisplay/128));
        }
        report.append('\n');
    }
    private static int size(SurfaceFunction f) { return f.terrain().shape().size(); }
    private static void image(SurfaceFunction f, SurfaceCache cache, int level, Path file) throws Exception {
        int width = cache.width(level);
        var exact = new SurfaceFunction.Cell[width*width];
        var estimate = new SurfaceFunction.Cell[width*width];
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            exact[z*width+x] = cache.cell(Face.NORTH,x*level,z*level,level);
            estimate[z*width+x] = SurfaceCache.estimate(f,Face.NORTH,x*level,z*level,level,16);
        }
        var image = new BufferedImage(2*width,width,BufferedImage.TYPE_INT_RGB);
        for (int half = 0; half < 2; half++) {
            var cells = half == 0 ? exact : estimate;
            for (int z = 0; z < width; z++) for (int x = 0; x < width; x++)
                image.setRGB(half*width+x,z,shaded(cells,width,x,z,level));
        }
        ImageIO.write(image,"png",file.toFile());
    }
    private static int shaded(SurfaceFunction.Cell[] cells, int width, int x, int z, int level) {
        double dx = (cells[z*width+Math.min(width-1,x+1)].height()-cells[z*width+Math.max(0,x-1)].height())/(2*level);
        double dz = (cells[Math.min(width-1,z+1)*width+x].height()-cells[Math.max(0,z-1)*width+x].height())/(2*level);
        double shade = Math.clamp((.85-.4*dx-.3*dz)/Math.sqrt(1+dx*dx+dz*dz),.3,1.2);
        int rgb = SurfaceEvaluation.color(cells[z*width+x]);
        return (int)Math.min(255,(rgb>>16&255)*shade)<<16 | (int)Math.min(255,(rgb>>8&255)*shade)<<8 | (int)Math.min(255,(rgb&255)*shade);
    }
    private static void edit(SurfaceFunction f, SurfaceCache cache, Path path, StringBuilder report) throws Exception {
        int centre = cache.key().size()/2/16*16;
        var chunk = new SurfaceOverlay.Chunk(Face.NORTH,centre,centre);
        var overlay = new SurfaceOverlay(cache,f);
        var points = SurfaceCacheTest.edit(f,chunk);
        var times = new long[9];
        Set<SurfaceOverlay.Address> changed = Set.of();
        for (int run = 0; run < times.length; run++) {
            long start = System.nanoTime(); changed = overlay.replace(chunk,points); times[run] = System.nanoTime()-start;
        }
        Arrays.sort(times);
        int checked = 0;
        for (var a : changed) {
            var sum = new SurfaceFunction.Aggregate();
            for (int z = a.v(); z < Math.min(cache.key().size(),a.v()+a.level()); z++)
                for (int x = a.u(); x < Math.min(cache.key().size(),a.u()+a.level()); x++) sum.add(overlay.point(a.face(),x,z));
            SurfaceCacheTest.equal(sum.cell(),overlay.cell(a.face(),a.u(),a.v(),a.level())); checked++;
        }
        var image = new BufferedImage(128,64,BufferedImage.TYPE_INT_RGB);
        var before = new SurfaceFunction.Cell[64*64];
        var after = new SurfaceFunction.Cell[64*64];
        for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) {
            int u = centre-24+x, v = centre-24+z;
            before[z*64+x] = f.point(Face.NORTH,u,v); after[z*64+x] = overlay.point(Face.NORTH,u,v);
        }
        for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) {
            image.setRGB(x,z,shaded(before,64,x,z,1)); image.setRGB(64+x,z,shaded(after,64,x,z,1));
        }
        ImageIO.write(image,"png",path.resolve("edit-before-after.png").toFile());
        report.append("Edit update median (nine replacements, excludes input construction): ").append(times[4]).append(" ns; ")
                .append(checked).append(" affected cells verified against scratch aggregation; 16 L4 + 4 L8 + 1 L16 + 1 L32 + 1 L64. Only three chunk-level ancestors.\n\n")
                .append("Images: [top-16](").append(path.getFileName()).append("/top-16.png), [top-64](").append(path.getFileName()).append("/top-64.png), [edit](").append(path.getFileName()).append("/edit-before-after.png).\n\n");
    }
}
