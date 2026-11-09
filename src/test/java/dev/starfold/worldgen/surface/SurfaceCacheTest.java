package dev.starfold.worldgen.surface;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worlds.fixtures.ExperienceFixtures;
import dev.starfold.universe.PlanetType;
import dev.starfold.worldgen.*;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceCacheTest {
    static SurfaceFunction function;
    static SurfaceCache cache;
    @BeforeAll static void boot() throws Exception {
        ExperienceFixtures.boot();
        SurfaceTestTags.bind();
        function = new SurfaceFunction(new PlanetTerrain(42,dev.starfold.worldgen.PlanetTerrain.VERSION,new PlanetGeneration("temperate",PlanetGeneration.SURFACE_VERSION,dev.starfold.worldgen.PlanetGeneration.defaults(dev.starfold.worldgen.PlanetTerrain.VERSION).recipe(),416,191,"core_shell",true,false,false,dev.starfold.worldgen.PlanetEnvironment.defaults(true,416))));
        cache = SurfaceCache.build(function,4,Face.NORTH,200,200);
    }
    @Test void productionIdentityIncludesCurrentSurfaceAndPlan() {
        var key=SurfaceKey.of(function.terrain());
        assertEquals(function.terrain().seed(),key.seed());
        assertEquals(function.terrain().shape().size(),key.size());
        assertTrue(key.algorithmVersion().contains("/surface-"+PlanetGeneration.SURFACE_VERSION));
        assertTrue(key.algorithmVersion().contains("/plan-"+function.terrain().plan().identity()));
        assertEquals(key,SurfaceKey.of(function.terrain()));
        assertEquals(key.hashCode(),SurfaceKey.of(function.terrain()).hashCode());
    }
    @AfterAll static void releaseFixtures() { cache = null; function = null; }
    static void equal(SurfaceFunction.Cell a, SurfaceFunction.Cell b) {
        assertEquals(a.height(),b.height(),1e-10);
        assertEquals(a.canopyCoverage(),b.canopyCoverage(),1e-10);
        assertEquals(a.canopyTop(),b.canopyTop(),1e-10);
        assertEquals(a.materials().size(),b.materials().size());
        for (int i = 0; i < a.materials().size(); i++) {
            assertEquals(a.materials().get(i).block(),b.materials().get(i).block());
            assertEquals(a.materials().get(i).share(),b.materials().get(i).share(),1e-10);
        }
        assertEquals(a.canopyMaterials().size(),b.canopyMaterials().size());
        for (int i = 0; i < a.canopyMaterials().size(); i++) {
            assertEquals(a.canopyMaterials().get(i).block(),b.canopyMaterials().get(i).block());
            assertEquals(a.canopyMaterials().get(i).share(),b.canopyMaterials().get(i).share(),1e-10);
        }
    }
    static void compactEqual(SurfaceFunction.Cell exact, SurfaceFunction.Cell compact) {
        assertEquals(exact.height(),compact.height(),1/128.);
        assertEquals(exact.canopyCoverage(),compact.canopyCoverage(),.5/255.);
        if (compact.canopyCoverage() > 0) {
            assertEquals(exact.canopyTop(),compact.canopyTop(),1/128.);
            assertEquals(exact.canopyMaterials().getFirst().block(),compact.canopyMaterials().getFirst().block());
        }
        assertTrue(compact.materials().size() <= 2);
        for (int i = 0; i < compact.materials().size(); i++) {
            assertEquals(exact.materials().get(i).block(),compact.materials().get(i).block());
            assertEquals(exact.materials().get(i).share(),compact.materials().get(i).share(),.5/255.);
        }
        for (int i = 0; i < 3; i++) assertEquals(SurfaceColor.channel(exact,i),SurfaceColor.channel(compact,i),.5/255.+1e-12);
    }
    @Test void everyCellEqualsIndependentPointAggregationIncludingPartialEdges() {
        assertEquals(16L*(6*104*104+6*52*52+6*26*26+6*13*13+6*7*7),cache.retainedBytes());
        int size = function.terrain().shape().size();
        for (int level = 4; level <= 64; level *= 2) for (var face : Face.values())
            for (int v = 0; v < size; v += level) for (int u = 0; u < size; u += level) {
                var sum = new SurfaceFunction.Aggregate();
                for (int z = v; z < Math.min(v+level,size); z++) for (int x = u; x < Math.min(u+level,size); x++)
                    sum.add(function.point(face,x,z));
                compactEqual(sum.cell(),cache.cell(face,u,v,level));
            }
    }
    @Test void parallelBuildIsDeterministic() throws Exception {
        var serial = SurfaceCache.build(function,1,Face.SOUTH,0,0);
        for (int level = 4; level <= 64; level *= 2) for (var face : Face.values())
            for (int v = 0; v < 416; v += level) for (int u = 0; u < 416; u += level)
                assertEquals(cache.cell(face,u,v,level),serial.cell(face,u,v,level));
    }
    @Test void fileRoundtripRejectsAllWrongKeysAndTruncation(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("base.gz");
        cache.save(file);
        var loaded = SurfaceCache.load(file,cache.key());
        for (int level = 4; level <= 64; level *= 2) for (var face : Face.values())
            for (int v = 0; v < 416; v += level) for (int u = 0; u < 416; u += level)
                assertEquals(cache.cell(face,u,v,level),loaded.cell(face,u,v,level));
        var k = cache.key();
        for (var bad : List.of(new SurfaceKey(43,k.type(),k.size(),k.algorithmVersion()),
                new SurfaceKey(k.seed(),"wrong",k.size(),k.algorithmVersion()),
                new SurfaceKey(k.seed(),k.type(),432,k.algorithmVersion()),
                new SurfaceKey(k.seed(),k.type(),k.size(),"next")))
            assertThrows(java.io.IOException.class,() -> SurfaceCache.load(file,bad));
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file,Arrays.copyOf(bytes,bytes.length/2));
        assertThrows(java.io.IOException.class,() -> SurfaceCache.load(file,k));
    }
    static SurfaceFunction.Cell[] edit(SurfaceFunction f, SurfaceOverlay.Chunk c) {
        var points = new SurfaceFunction.Cell[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            var old = f.point(c.face(),c.u()+x,c.v()+z);
            int h = x < 4 && z < 4 ? 200 : (int)old.height()-20;
            points[z*16+x] = new SurfaceFunction.Cell(h,List.of(new SurfaceFunction.Coverage(
                    (x < 4 && z < 4 ? Blocks.STONE_BRICKS : Blocks.STONE).defaultBlockState(),1)),0,0,List.of());
        }
        return points;
    }
    @Test void multipleEditsAndRemovalChangeOnlyIntersectingCellsAndEqualRebuild() {
        var overlay = new SurfaceOverlay(cache,function);
        var a = new SurfaceOverlay.Chunk(Face.NORTH,32,48);
        var b = new SurfaceOverlay.Chunk(Face.NORTH,48,48);
        for (var chunk : List.of(a,b,a,b)) {
            boolean remove = chunk == a && overlay.point(a.face(),a.u(),a.v()).height() == 200
                    || chunk == b && overlay.point(b.face(),b.u(),b.v()).height() == 200;
            var changed = overlay.replace(chunk,remove ? null : edit(function,chunk));
            assertEquals(23,changed.size());
            assertEquals(3,changed.stream().filter(c -> c.level() >= 16).count());
            for (int level = 4; level <= 64; level *= 2) for (var face : Face.values())
                for (int v = 0; v < 416; v += level) for (int u = 0; u < 416; u += level) {
                    boolean affected = face == Face.NORTH && u < 64 && u+level > 32 && v < 64 && v+level > 48;
                    if (!affected) assertEquals(cache.cell(face,u,v,level),overlay.cell(face,u,v,level));
                    else {
                        var sum = new SurfaceFunction.Aggregate();
                        for (int z = v; z < Math.min(v+level,416); z++) for (int x = u; x < Math.min(u+level,416); x++)
                            sum.add(overlay.point(face,x,z));
                        var actual = overlay.cell(face,u,v,level);
                        if (actual.linearRgb() >= 0) compactEqual(sum.cell(),actual);
                        else equal(sum.cell(),actual);
                    }
                }
        }
    }
    @Test void fastVisibleEqualsPreviousExactAndActualGenerationOnEveryType() {
        var random = new Random(71);
        for (var holder : ExperienceFixtures.registryLookup().lookupOrThrow(PlanetType.KEY).listElements()
                .filter(h -> h.key().location().getNamespace().equals("starfold_worlds")).toList()) {
            var type = holder.value();
            var t = type.planet(42,Optional.of(type.minSize())).terrain();
            var f = new SurfaceFunction(t);
            for (int i = 0; i < 400; i++) {
                var face = Face.values()[i%6];
                int u = i < 80 ? (i%2 == 0 ? 0 : t.shape().size()-1) : random.nextInt(t.shape().size());
                int v = i < 20 ? i%2*(t.shape().size()-1) : random.nextInt(t.shape().size());
                var fast = f.visible(face,u,v);
                assertEquals(f.visibleExact(face,u,v),fast,holder.key()+" "+face+" "+u+","+v);
                assertEquals(t.column(face,u,v).state(fast.top()),fast.block());
                var point = f.point(face,u,v);
                assertEquals(fast.top(),point.height());
                assertEquals(fast.block(),point.materials().getFirst().block());
            }
        }
    }
    @Test void ecologyCanopyMipsEqualIndependentExactAggregation() throws Exception {
        var planet = SurfaceGenerationTest.generator("home");
        var json = dev.starfold.universe.PlanetChunkGenerator.CODEC.codec()
                .encodeStart(ExperienceFixtures.ops(), planet).getOrThrow().getAsJsonObject();
        json.getAsJsonObject("planet_generation").addProperty("size", 416);
        json.getAsJsonObject("planet_generation").addProperty("depth", 191);
        var f = new SurfaceFunction(dev.starfold.universe.PlanetChunkGenerator.CODEC.codec()
                .parse(ExperienceFixtures.ops(), json).getOrThrow().terrain());
        var c = SurfaceCache.build(f,4,Face.NORTH,200,200);
        int canopyCells = 0;
        for (int level = 4; level <= 64; level *= 2) for (var face : Face.values())
            for (int v = 0; v < 416; v += level) for (int u = 0; u < 416; u += level) {
                // Compare every retained cell, including conditional crown state proportions and partial edges.
                var sum = new SurfaceFunction.Aggregate();
                for (int z = v; z < Math.min(v+level,416); z++) for (int x = u; x < Math.min(u+level,416); x++)
                    sum.add(f.point(face,x,z));
                compactEqual(sum.cell(),c.cell(face,u,v,level));
                if (c.cell(face,u,v,level).canopyCoverage() > 0) canopyCells++;
            }
        assertTrue(canopyCells > 0);
    }
    @Test void concurrentReadsAndInvalidBuildParameters() {
        java.util.stream.IntStream.range(0,1000).parallel().forEach(i -> {
            int level = 4 << (i%5), width = cache.width(level);
            var face = Face.values()[i%6];
            int u = (i*193%width)*level, v = (i*307%width)*level;
            var sum = new SurfaceFunction.Aggregate();
            for (int z = v; z < Math.min(v+level,416); z++) for (int x = u; x < Math.min(u+level,416); x++)
                sum.add(function.point(face,x,z));
            compactEqual(sum.cell(),cache.cell(face,u,v,level));
        });
        assertThrows(IllegalArgumentException.class,() -> SurfaceCache.build(function,0,Face.NORTH,0,0));
    }
    @Test void placeholderIsDeterministicAndExactAtLevelFour() {
        compactEqual(SurfaceCache.estimate(function,Face.NORTH,32,48,4),cache.cell(Face.NORTH,32,48,4));
        assertEquals(SurfaceCache.estimate(function,Face.NORTH,32,48,64,16),SurfaceCache.estimate(function,Face.NORTH,32,48,64,16));
        assertThrows(IllegalArgumentException.class,() -> SurfaceCache.estimate(function,Face.NORTH,0,0,64,15));
        assertThrows(IllegalArgumentException.class,() -> cache.cell(Face.NORTH,1,0,4));
    }
}
