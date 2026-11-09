package dev.starfold.worldgen.surface;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.Anomaly;
import dev.starfold.worldgen.PlanetTerrain;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.HugeFungusConfiguration;
import java.util.*;

/** One 8-block lattice; weighted biome entries compete by raw hash including their actual template footprints. */
public final class BiomeScatter {
    public static final int VARIANTS = 8, SPACING = 8;
    public record Entry(String id, Feature<?> feature, double density, List<FeatureSandbox.Template> variants) {
        public Entry { variants = List.copyOf(variants); }
    }
    record Candidate(Face face, int u, int v, int base, long hash, FeatureSandbox.Template template) {}
    private record Neighbour(Candidate candidate, int dx, int dz, int turns) {}
    private record Address(Face face, int u, int v) {}
    private record Result(Candidate candidate) {}
    private final PlanetTerrain terrain;
    private final BiomeSource biomes;
    private final Map<Holder<Biome>,List<Entry>> entries;
    private final List<String> skipped;
    private final int radius;
    private final String identity;
    @FunctionalInterface interface Selector { net.minecraft.resources.ResourceLocation biome(Face f,int u,int v,int ground); }
    private Selector selector;
    private Map<net.minecraft.resources.ResourceLocation,List<Entry>> imported;
    private SurfacePolicy policy;
    public synchronized SurfacePolicy policy() { if(policy==null) policy=SurfacePolicy.of(this); return policy; }
    BiomeScatter(PlanetTerrain terrain,Map<net.minecraft.resources.ResourceLocation,List<Entry>> entries,String identity,Selector selector) {
        this.terrain=terrain; this.biomes=null; this.entries=Map.of(); this.skipped=List.of();
        this.imported=Map.copyOf(entries); this.selector=selector; this.identity=identity;
        this.radius=entries.values().stream().flatMap(List::stream).flatMap(e->e.variants().stream()).mapToInt(FeatureSandbox.Template::radius).max().orElse(0);
    }
    private boolean empty() { return imported == null ? entries.values().stream().allMatch(List::isEmpty) : imported.values().stream().allMatch(List::isEmpty); }
    private final ThreadLocal<Map<Candidate,Boolean>> winners=ThreadLocal.withInitial(HashMap::new);
    // Pure memo only: cache/eviction order never affects candidates. Each worker retains at most 4096 roots.
    private final ThreadLocal<LinkedHashMap<Address,Result>> roots = ThreadLocal.withInitial(() ->
            new LinkedHashMap<>(256,.75f,true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Address,Result> e) { return size() > 4096; }
            });

    public BiomeScatter(PlanetTerrain terrain, BiomeSource biomes, ChunkGenerator generator) {
        this.terrain = terrain; this.biomes = biomes;
        var byBiome = new LinkedHashMap<Holder<Biome>,List<Entry>>();
        var failures = new TreeSet<String>();
        var built = new HashMap<ConfiguredFeature<?,?>,List<FeatureSandbox.Template>>();
        var signature = new StringBuilder("biome-scatter-v1/").append(terrain.generation().recipe()).append('/');
        int bound = 0;
        for (var biome : biomes.possibleBiomes().stream().sorted(Comparator.comparing(b -> b.unwrapKey().map(k -> k.location().toString()).orElse("inline"))).toList()) {
            var selected = new ArrayList<Entry>();
            for (var step : biome.value().getGenerationSettings().features()) for (var holder : step) {
                var placed = holder.value();
                if (!VisibleFeatures.visible(placed)) continue;
                String placedId = holder.unwrapKey().map(k -> k.location().toString()).orElse("inline");
                var leaves = placed.getFeatures().filter(c -> VisibleFeatures.visible(c.feature())).distinct().toList();
                int index = 0;
                for (var configured : leaves) {
                    var feature = configured.feature();
                    String id = placedId+"/"+(index++)+"/"+BuiltInRegistries.FEATURE.getKey(feature);
                    if (!VisibleFeatures.TYPES.contains(feature)) {
                        failures.add(id+": unadapted mod feature; excluded from vanilla decoration, needs a scatter adapter");
                        continue;
                    }
                    signature.append(biome.unwrapKey().map(k -> k.location().toString()).orElse("inline")).append('/').append(id)
                            .append('/').append(VisibleFeatures.density(placed)).append('/');
                    try {
                        if(!VisibleFeatures.pointwise(feature))
                            signature.append(ConfiguredFeature.DIRECT_CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,configured).getOrThrow());
                        List<FeatureSandbox.Template> variants = built.get(configured);
                        if (variants == null) {
                            var templates = new ArrayList<FeatureSandbox.Template>();
                            for (int variant = 0; variant < VARIANTS; variant++) {
                                try {
                                    long seed=Scatter.mix(terrain.seed() ^ stableHash(id) ^ Scatter.mix(variant));
                                    templates.add(VisibleFeatures.pointwise(feature) ? FeatureSandbox.plant(configured,generator,seed)
                                            : FeatureSandbox.build(configured,generator,soil(configured),seed));
                                } catch (UnsupportedOperationException failure) {
                                    failures.add(id+" variant "+variant+": "+failure.getMessage());
                                }
                            }
                            variants = List.copyOf(templates);
                            built.put(configured,variants);
                        }
                        if (variants.isEmpty()) { failures.add(id+": all sandbox variants failed"); continue; }
                        for (var template : variants) {
                            bound = Math.max(bound,template.radius());
                            if(VisibleFeatures.pointwise(feature))signature.append(template.signature());
                        }
                        double density=VisibleFeatures.density(placed)/leaves.size();
                        if(configured.config() instanceof net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration patch)
                            density*=patch.tries()/(double)(2*patch.ySpread()+1);
                        selected.add(new Entry(id,feature,density,variants));
                    } catch (IllegalStateException | IllegalArgumentException failure) {
                        throw new IllegalStateException("Visible feature extraction: "+id,failure);
                    }
                }
            }
            byBiome.put(biome,List.copyOf(selected));
        }
        entries = Map.copyOf(byBiome); skipped = List.copyOf(failures); radius = bound;
        identity = Long.toUnsignedString(stableHash(signature.toString()),16);
    }
    public Map<Holder<Biome>,List<Entry>> entries() { return entries; }
    public List<String> skipped() { return skipped; }
    public String identity() { return identity; }
    private static long stableHash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) h = (h ^ s.charAt(i))*0x100000001b3L;
        return h;
    }
    private static BlockState soil(ConfiguredFeature<?,?> c) {
        var f = c.feature();
        if (f == Feature.ICE_SPIKE) return Blocks.SNOW_BLOCK.defaultBlockState();
        if (f == Feature.DESERT_WELL || f == Feature.BLOCK_COLUMN) return Blocks.SAND.defaultBlockState();
        if (f == Feature.HUGE_RED_MUSHROOM || f == Feature.HUGE_BROWN_MUSHROOM) return Blocks.MYCELIUM.defaultBlockState();
        if (f == Feature.CHORUS_PLANT || f == Feature.END_ISLAND) return Blocks.END_STONE.defaultBlockState();
        if (c.config() instanceof HugeFungusConfiguration fungus) return fungus.validBaseState;
        if (f == Feature.BASALT_COLUMNS || f == Feature.BASALT_PILLAR) return Blocks.NETHERRACK.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }
    private boolean soil(Entry entry, BlockState soil) {
        var f = entry.feature();
        if (f == Feature.ICEBERG) return soil.is(Blocks.WATER) || soil.is(Blocks.ICE);
        if (f == Feature.DESERT_WELL || f == Feature.BLOCK_COLUMN) return soil.is(Blocks.SAND) || soil.is(Blocks.RED_SAND);
        if (f == Feature.ICE_SPIKE) return soil.is(Blocks.SNOW_BLOCK) || soil.is(Blocks.PACKED_ICE);
        if (f == Feature.CHORUS_PLANT || f == Feature.END_ISLAND) return soil.is(Blocks.END_STONE);
        if (f == Feature.BASALT_COLUMNS || f == Feature.FOREST_ROCK || f == Feature.REPLACE_BLOBS)
            return soil.is(BlockTags.BASE_STONE_OVERWORLD) || soil.is(Blocks.BASALT) || soil.is(Blocks.NETHERRACK) || soil.is(BlockTags.DIRT);
        if (f == Feature.HUGE_FUNGUS) return soil.is(Blocks.CRIMSON_NYLIUM) || soil.is(Blocks.WARPED_NYLIUM);
        return soil.is(BlockTags.DIRT) || soil.is(Blocks.MOSS_BLOCK) || soil.is(Blocks.MYCELIUM) || soil.is(Blocks.MUD);
    }
    Candidate candidate(Face face, int u, int v) {
        var address = new Address(face,u,v);
        var memo = roots.get();
        var old = memo.get(address);
        if (old != null) return old.candidate();
        var result = compute(face,u,v);
        memo.put(address,new Result(result));
        return result;
    }
    private Candidate compute(Face face, int u, int v) {
        int size = terrain.shape().size();
        if (u < 0 || v < 0 || u >= size || v >= size) return null;
        long hash = Scatter.mix(terrain.seed() ^ ((long)face.ordinal()<<56) ^ ((long)u<<28) ^ v ^ 0x53434154544552L);
        u += (int)((hash>>>2)%7)-3;
        v += (int)((hash>>>5)%7)-3;
        if (u < 0 || v < 0 || u >= size || v >= size) return null;
        int ground = terrain.groundY(face,u,v), base = terrain.surfaceTop(face,u,v,ground);
        var list = imported == null
                ? entries.getOrDefault(biomes.getNoiseBiome((face.ordinal()*4096+u)/4,ground/4,v/4,null),List.of())
                : imported.getOrDefault(selector.biome(face,u,v,ground),List.of());
        list=list.stream().filter(e->!VisibleFeatures.pointwise(e.feature())).toList();
        double total=list.stream().mapToDouble(Entry::density).sum();
        double density = total*terrain.humidity(face,u,v);
        double choice = (hash>>>11)*0x1.0p-53;
        if (density <= 0 || choice >= Math.min(1,density*SPACING*SPACING)) return null;
        double pick = (Scatter.mix(hash)>>>11)*0x1.0p-53*total;
        Entry entry = list.getLast();
        for (var e : list) { pick -= e.density(); if (pick < 0) { entry = e; break; } }
        var template = entry.variants().get((int)Long.remainderUnsigned(Scatter.mix(hash+1),entry.variants().size()));
        int r = template.radius();
        if (base+template.height() >= PlanetTerrain.MAX_Y
                || terrain.closure(face,u,v)
                || Anomaly.distance(terrain.unit(face,u+.5,v+.5),terrain.shape()) < Anomaly.CLEAR
                || !soil(entry,terrain.surfaceState(face,u,v))) return null;
        if (entry.feature() != Feature.ICEBERG) {
            if (terrain.submerged(face,u,v,ground)) return null;
            for (int x : new int[]{-r,r}) for (int z : new int[]{-r,r}) {
                var corner = Scatter.canonical(terrain,face,u+x,v+z);
                if (Math.abs(ground-terrain.groundY(corner.face(),corner.u(),corner.v())) > 3) return null;
            }
        }
        return new Candidate(face,u,v,base,hash,template);
    }
    /** Unfold nearby sites with the topology's transported axes. Snap in the owning face, not in a ghost chart. */
    private List<Neighbour> neighbours(Face face, int u, int v, int reach) {
        var found = new ArrayList<Neighbour>();
        int size = terrain.shape().size();
        int padding = reach+SPACING;
        if (u >= padding && v >= padding && u+padding < size && v+padding < size) {
            for (int z = Math.floorDiv(v-reach-3,SPACING)*SPACING+SPACING/2; z <= v+reach+3; z += SPACING)
                for (int x = Math.floorDiv(u-reach-3,SPACING)*SPACING+SPACING/2; x <= u+reach+3; x += SPACING) {
                    var c = candidate(face,x,z);
                    if (c != null && Math.abs(u-c.u()) <= reach && Math.abs(v-c.v()) <= reach)
                        found.add(new Neighbour(c,u-c.u(),v-c.v(),0));
                }
            return found;
        }
        var seen = new HashSet<Address>();
        // A snapped owner lattice can be reflected by a seam; one extra lattice interval covers either alignment.
        for (int z = Math.floorDiv(v-padding,SPACING)*SPACING+SPACING/2; z <= v+padding; z += SPACING)
            for (int x = Math.floorDiv(u-padding,SPACING)*SPACING+SPACING/2; x <= u+padding; x += SPACING) {
                var route = terrain.shape().topology().canonical(new dev.starfold.geometry.CubeSurfaceTopology.Cell(face,x,0,z));
                var at = route.cell();
                int su = at.u()/SPACING*SPACING+SPACING/2, sv = at.v()/SPACING*SPACING+SPACING/2;
                if (su >= size || sv >= size || !seen.add(new Address(at.face(),su,sv))) continue;
                var c = candidate(at.face(),su,sv);
                if (c == null) continue;
                var delta = route.transport(new dev.starfold.geometry.CubeSurfaceTopology.Delta(u-x,0,v-z));
                int dx = at.u()+delta.u()-c.u(), dz = at.v()+delta.v()-c.v();
                if (Math.abs(dx) <= reach && Math.abs(dz) <= reach)
                    found.add(new Neighbour(c,dx,dz,Math.floorMod(-route.turns(),4)));
            }
        return found;
    }
    boolean wins(Face face, Candidate c) {
        var memo=winners.get();
        var prior=memo.get(c); if(prior!=null) return prior;
        if(memo.size()>4096) memo.clear();
        boolean result=computeWins(c); memo.put(c,result); return result;
    }
    private boolean computeWins(Candidate c) {
        for (var near : neighbours(c.face(),c.u(),c.v(),c.template().radius()+radius)) {
            var other = near.candidate();
            int order = Long.compareUnsigned(other.hash(),c.hash());
            if (order == 0) order = Integer.compare(other.face().ordinal(),c.face().ordinal());
            if (order == 0) order = other.u() != c.u() ? Integer.compare(other.u(),c.u()) : Integer.compare(other.v(),c.v());
            if (order < 0 && Math.abs(near.dx()) <= c.template().radius()+other.template().radius()
                    && Math.abs(near.dz()) <= c.template().radius()+other.template().radius()) return false;
        }
        return true;
    }
    private List<SurfaceFunction.Run> plants(Face face,int u,int v) {
        int ground=terrain.groundY(face,u,v);
        if(terrain.closure(face,u,v) || terrain.submerged(face,u,v,ground)
                || Anomaly.distance(terrain.unit(face,u+.5,v+.5),terrain.shape())<Anomaly.CLEAR) return List.of();
        var list=imported==null
                ? entries.getOrDefault(biomes.getNoiseBiome((face.ordinal()*4096+u)/4,ground/4,v/4,null),List.of())
                : imported.getOrDefault(selector.biome(face,u,v,ground),List.of());
        long hash=Scatter.mix(terrain.seed() ^ ((long)face.ordinal()<<56) ^ ((long)u<<28) ^ v ^ 0x504c414e54L);
        double pick=(hash>>>11)*0x1.0p-53;
        for(var entry:list) {
            if(!VisibleFeatures.pointwise(entry.feature()))continue;
            pick-=entry.density()*terrain.humidity(face,u,v);
            if(pick>=0)continue;
            var template=entry.variants().get((int)Long.remainderUnsigned(Scatter.mix(hash),entry.variants().size()));
            var plant=template.block(0,1,0);
            var soil=terrain.visibleBand(face,u,v,ground,ground).state(ground);
            if(plant.is(Blocks.SUGAR_CANE)) {
                boolean water=false;
                for(int[] d:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
                    var c=Scatter.canonical(terrain,face,u+d[0],v+d[1]);
                    var liquid=terrain.liquid(c.face(),c.u(),c.v());
                    water|=liquid!=null && liquid.level()>=ground
                            && terrain.groundY(c.face(),c.u(),c.v())<ground;
                }
                if(!water || !(soil.is(BlockTags.DIRT)||soil.is(BlockTags.SAND)))return List.of();
            } else if(plant.is(Blocks.DEAD_BUSH)||plant.is(Blocks.CACTUS)) {
                if(!soil.is(BlockTags.SAND)&&!soil.is(Blocks.TERRACOTTA))return List.of();
            } else if(!soil.is(BlockTags.DIRT)&&!soil.is(Blocks.MOSS_BLOCK)&&!soil.is(Blocks.MUD))return List.of();
            var states=new TreeMap<Integer,BlockState>();
            for(int y=1;y<=template.height();y++) {
                var state=template.block(0,y,0);
                if(!state.isAir())states.put(ground+y,state);
            }
            return SurfaceFunction.runs(states);
        }
        return List.of();
    }

    public List<SurfaceFunction.Run> column(Face face, int u, int v) {
        if (radius == 0 && empty()) return List.of();
        var cell = Scatter.canonical(terrain,face,u,v);
        face = cell.face(); u = cell.u(); v = cell.v();
        var blocks = new TreeMap<Integer,BlockState>();
        int cap = terrain.surfaceTop(face,u,v,terrain.groundY(face,u,v));
        for(var run:plants(face,u,v)) for(int y=run.bottom();y<=run.top();y++) blocks.put(y,run.block());
        for (var near : neighbours(face,u,v,radius)) {
            var c = near.candidate();
            if (Math.abs(near.dx()) > c.template().radius() || Math.abs(near.dz()) > c.template().radius() || !wins(c.face(),c)) continue;
            for (int y = 1; y <= c.template().height(); y++) {
                if (c.base()+y <= cap) continue;
                var block = c.template().block(near.dx(),y,near.dz());
                if (!block.isAir()) blocks.put(c.base()+y,block.rotate(net.minecraft.world.level.block.Rotation.values()[near.turns()]));
            }
        }
        return SurfaceFunction.runs(blocks);
    }
}
