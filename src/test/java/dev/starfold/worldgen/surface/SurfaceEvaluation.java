package dev.starfold.worldgen.surface;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worlds.fixtures.ExperienceFixtures;
import dev.starfold.universe.PlanetType;
import dev.starfold.worldgen.PlanetTerrain;
import net.minecraft.world.level.block.state.BlockState;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntConsumer;

/** Reproducible single-thread evaluator; ordinary unit-test discovery does not execute this class. */
public final class SurfaceEvaluation {
    private static volatile double sink;
    private static final Face FACE = Face.NORTH;
    private static final int[] LEVELS = {1,2,4,8,16,32,64};
    private static final Path OUTPUT = Path.of("build/surface-eval");

    public static void run() throws Exception {
        long start = System.nanoTime();
        ExperienceFixtures.boot();
        SurfaceTestTags.bind();
        Files.createDirectories(OUTPUT);
        var report = new StringBuilder("# Offline surface-function evaluation\n\n")
                .append("Seed 42; each starfold_worlds type's smallest size; NORTH face images; random checks span all six faces. Single thread, 250 ms mixed-query warm-up per type plus three per-query warm batches; median of five measured batches, ns/op; allocation and GC included. No Minecraft client/server/world was started.\n\n")
                .append("Ground cap means ground/sea/closure, not tree height. Side bounds use canonical neighbour caps. The versioned SystemChunkGenerator.fillFromNoise path is run into actual ProtoChunks; every column in forty mixed-face chunks is checked independently against visible, including all object runs and empty gaps. Vanilla block tags are loaded from shipped resources before sandbox execution. No later small biome features or structures are executed.\n\n")
                .append("Coverage retains every nonzero material (top-k with k = observed count), sorted by share. Height is mean cap height, canopy coverage counts scatter-object columns (excluding small ground plants), canopy top and material shares are conditional on covered columns. LOD errors compare direct sample to independent level-1 aggregation at eight random cells per level; coverage error is L1 share distance, colour error is mean RGB-channel absolute difference in [0,255]. Exact aggregation should give zero (up to rounding), not a quality claim about a cheaper estimator.\n\n")
                .append("Images: top-1 is a 512x512 face-centre region (small faces wrap canonically); top-4/16/64 cover the whole face (ceil at edges, final cell may cross a seam). Mips are streamed from exact 4x4 samples, not point picks. MapColor only, no biome tint/textures; height hillshade and canopy overlay.\n\n");
        for (var holder : ExperienceFixtures.registryLookup().lookupOrThrow(PlanetType.KEY).listElements()
                .filter(h -> h.key().location().getNamespace().equals("starfold_worlds"))
                .sorted(Comparator.comparing(h -> h.key().location().getPath())).toList()) {
            String name = holder.key().location().getPath();
            var type = holder.value();
            System.out.println("surfaceEval: "+name);
            var generator = type.planet(42,Optional.of(type.minSize()));
            var terrain = generator.terrain();
            var function = new SurfaceFunction(terrain);
            var path = OUTPUT.resolve(name);
            Files.createDirectories(path);
            long warmUntil = System.nanoTime()+250_000_000L;
            int warm = 0;
            do {
                sink += function.visible(FACE,(warm*193+37)%type.minSize(),(warm*307+91)%type.minSize()).top();
                sink += function.sample(FACE,(warm*193+37)%type.minSize(),(warm*307+91)%type.minSize(),4).height();
                warm++;
            } while (System.nanoTime() < warmUntil);
            long visible = median(512,i -> {
                int size = terrain.shape().size();
                var c = function.visible(Face.values()[i%6],(i*193+37)%size,(i*307+91)%size);
                sink += c.top()+c.objects().size();
            });
            int sectionStart = report.length();
            report.append("## ").append(name).append(" (size ").append(type.minSize()).append(")\n\n")
                    .append("| Query | Median ns/op | Mean height error | Coverage L1 error | RGB error |\n|---|---:|---:|---:|---:|\n")
                    .append("| visible | ").append(visible).append(" | — | — | — |\n");
            for (int level : LEVELS) {
                int batch = Math.max(4,2048/(level*level));
                long ns = median(batch,i -> {
                    int size = terrain.shape().size();
                    var c = function.sample(FACE,(i*193+37)%size,(i*307+91)%size,level);
                    sink += c.height();
                });
                double[] error = errors(function,level);
                report.append(String.format(Locale.ROOT,"| sample %d | %d | %.9g | %.9g | %.9g |%n",level,ns,error[0],error[1],error[2]));
            }
            report.append('\n').append(consistency(generator));
            report.append(featureReport(terrain));
            images(function,path);
            report.append("\nImages: ");
            for (String file : List.of("top-1.png","top-4.png","top-16.png","top-64.png"))
                report.append('[').append(file).append("](").append(name).append('/').append(file).append(") ");
            report.append("\n\n");
            Files.writeString(path.resolve("results.md"),report.substring(sectionStart));
            Files.writeString(OUTPUT.resolve("summary.md"),report);
        }
        double seconds = (System.nanoTime()-start)/1e9;
        report.append("## Cost and next decision\n\n")
                .append("visible reuses existing material/column rules, including surfaceColumn allocations and a full-column fallback for deep exposed sides. This proves consistency, not the proposed microsecond target. sample is O(level²) and exact: level 64 evaluates 4096 base columns per call, so it is not a cheap far-LOD solution. Image generation reuses 4x4 results for the higher mips; timings do not.\n\n")
                .append("Generation now uses surface algorithm version 2 for every pack type. Visible features are biome-derived deterministic scatter, including home/archipelago biome tree templates. Small biome features and vanilla structures remain outside the pointwise guarantee; L6 and renderer/network integration are not implemented.\n\n")
                .append(String.format(Locale.ROOT,"Evaluator wall time including registry bootstrap: %.3f s.%n",seconds));
        Files.writeString(OUTPUT.resolve("summary.md"),report);
        System.out.println("surfaceEval completed in "+seconds+" s: "+OUTPUT.resolve("summary.md"));
    }

    static long median(int count, IntConsumer operation) {
        for (int warm = 0; warm < 3; warm++) for (int i = 0; i < count; i++) operation.accept(i+warm*count);
        long[] times = new long[5];
        for (int run = 0; run < times.length; run++) {
            long start = System.nanoTime();
            for (int i = 0; i < count; i++) operation.accept(i+run*count);
            times[run] = (System.nanoTime()-start)/count;
        }
        Arrays.sort(times);
        return times[2];
    }

    private static double[] errors(SurfaceFunction f, int level) {
        var random = new Random(917+level);
        double height = 0, coverage = 0, rgb = 0;
        for (int i = 0; i < 8; i++) {
            int u = random.nextInt(f.terrain().shape().size()), v = random.nextInt(f.terrain().shape().size());
            Face face = Face.values()[i%6];
            var actual = f.sample(face,u,v,level);
            var aggregate = new SurfaceFunction.Aggregate();
            for (int x = 0; x < level; x++) for (int z = 0; z < level; z++) aggregate.add(f.sample(face,u+x,v+z,1));
            var expected = aggregate.cell();
            height += Math.abs(actual.height()-expected.height());
            var delta = new HashMap<BlockState,Double>();
            for (var m : actual.materials()) delta.merge(m.block(),m.share(),Double::sum);
            for (var m : expected.materials()) delta.merge(m.block(),-m.share(),Double::sum);
            coverage += delta.values().stream().mapToDouble(Math::abs).sum();
            int a = color(actual), b = color(expected);
            rgb += (Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))+Math.abs((a&255)-(b&255)))/3.0;
        }
        return new double[]{height/8,coverage/8,rgb/8};
    }

    static String consistency(dev.starfold.universe.PlanetChunkGenerator generator) {
        var t = generator.terrain();
        var f = new SurfaceFunction(t);
        var random = new Random(42);
        int objectBlocks = 0;
        // Forty real chunks, every column checked. Mixed faces, seams and corners, not an array oracle.
        for (int i = 0; i < 40; i++) {
            var face = Face.values()[i%6];
            int width = t.shape().chunks();
            int cx = i < 12 ? (i%2 == 0 ? 0 : width-1) : random.nextInt(width);
            int cz = i < 6 ? (i%2 == 0 ? 0 : width-1) : random.nextInt(width);
            var chunk = SurfaceGenerationTest.chunk(generator,face.ordinal()*256+cx,cz);
            objectBlocks += SurfaceGenerationTest.verify(chunk,f,face,0,0);
        }
        return "Real ProtoChunk comparison: **10,240 columns / 40 chunks**, all six faces including face edges/corners; "
                +"**0 mismatches** in cap blocks, exposed-side runs and every above-cap block (including empty gaps). "
                +"Object block checks: **"+objectBlocks+"**. Small vanilla features/structures are not executed.\n\n";
    }

    static String featureReport(PlanetTerrain t) {
        if (t.scatter() == null) return "Biome scatter disabled by this planet's features flag.\n\n";
        var report = new StringBuilder("### Biome-derived visible entries\n\n");
        t.scatter().entries().entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().unwrapKey().map(k -> k.location().toString()).orElse("inline")))
                .forEach(b -> {
                    report.append(b.getKey().unwrapKey().map(k -> k.location().toString()).orElse("inline")).append(":\n");
                    if (b.getValue().isEmpty()) report.append("- none.\n");
                    for (var e : b.getValue()) report.append("- `").append(e.id()).append("`: expected attempts/column ").append(e.density())
                            .append(", variants ").append(e.variants().size()).append(", template blocks ")
                            .append(e.variants().stream().mapToInt(FeatureSandbox.Template::blocks).sum()).append(".\n");
                });
        report.append("\nSkipped sandbox variants/features: ");
        if (t.scatter().skipped().isEmpty()) report.append("none.\n\n");
        else { report.append('\n'); for (var skipped : t.scatter().skipped()) report.append("- ").append(skipped).append('\n'); }
        return report.toString();
    }

    static int color(SurfaceFunction.Cell c) {
        return SurfaceColor.display(c);
    }
    private static void images(SurfaceFunction f, Path path) throws Exception {
        int size = f.terrain().shape().size();
        var near = new SurfaceFunction.Cell[512*512];
        for (int z = 0; z < 512; z++) for (int x = 0; x < 512; x++) near[x+512*z] = f.sample(FACE,size/2-256+x,size/2-256+z,1);
        image(near,512,1,path.resolve("top-1.png"));
        int width = (size+3)/4;
        var cells = new SurfaceFunction.Cell[width*width];
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) cells[x+width*z] = f.sample(FACE,x*4,z*4,4);
        image(cells,width,4,path.resolve("top-4.png"));
        for (int level : new int[]{16,64}) {
            int out = (width+3)/4;
            var coarse = new SurfaceFunction.Cell[out*out];
            for (int z = 0; z < out; z++) for (int x = 0; x < out; x++) {
                var a = new SurfaceFunction.Aggregate();
                for (int dz = 0; dz < 4; dz++) for (int dx = 0; dx < 4; dx++) {
                    int px = x*4+dx, pz = z*4+dz;
                    a.add(px < width && pz < width ? cells[px+width*pz] : f.sample(FACE,px*(level/4),pz*(level/4),level/4));
                }
                coarse[x+out*z] = a.cell();
            }
            cells = coarse; width = out;
            image(cells,width,level,path.resolve("top-"+level+".png"));
        }
    }
    private static void image(SurfaceFunction.Cell[] cells, int width, int level, Path path) throws Exception {
        var image = new BufferedImage(width,width,BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            var cell = cells[x+width*z];
            double dx = (cells[Math.min(width-1,x+1)+width*z].height()-cells[Math.max(0,x-1)+width*z].height())/(2*level);
            double dz = (cells[x+width*Math.min(width-1,z+1)].height()-cells[x+width*Math.max(0,z-1)].height())/(2*level);
            double shade = Math.clamp((.85-.4*dx-.3*dz)/Math.sqrt(1+dx*dx+dz*dz),.3,1.2);
            int rgb = color(cell);
            int r = (int) Math.min(255,(rgb>>16&255)*shade), g = (int) Math.min(255,(rgb>>8&255)*shade), b = (int) Math.min(255,(rgb&255)*shade);
            image.setRGB(x,z,r<<16|g<<8|b);
        }
        ImageIO.write(image,"png",path.toFile());
    }
}
