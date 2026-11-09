package dev.starfold.worldgen.plan;

import dev.starfold.geometry.CubeSurfaceTopology.*;
import static dev.starfold.worldgen.plan.terrain.TerrainPlan.*;
import dev.starfold.planet.Anomaly;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.worldgen.surface.Scatter;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.*;

/** Final-terrain candidate scoring, fixed site effects, and minimum-cost settlement routes. */
public final class TraditionalPlanetPlanner {
    public static final net.minecraft.tags.TagKey<Structure> RIVER_SETTLEMENTS = net.minecraft.tags.TagKey.create(
        Registries.STRUCTURE, ResourceLocation.parse("starfold:river_settlements"));
    public static final net.minecraft.tags.TagKey<Structure> HIGHLAND_RUINS = net.minecraft.tags.TagKey.create(
        Registries.STRUCTURE, ResourceLocation.parse("starfold:highland_ruins"));
    public record Archive(PlanetPlan plan, Map<Long,CompoundTag> recipes, Map<String,String> skipped) {
        public Archive { recipes=Map.copyOf(recipes);skipped=Map.copyOf(skipped); }
    }
    private record Recorded(PlanetPlan.Site site,List<PlanetPlan.Template> templates,CompoundTag recipe) {}
    private record Assembly(List<StructurePiece> pieces,String skipped) {}
    private record Candidate(long id, ResourceLocation structure, Face face, int u, int v, double score) {}
    public record Timings(long selection, long assembly, long recording, long conflict, long indexing, long compression) {}
    private long selectionNanos,assemblyNanos,recordingNanos,conflictNanos;
    private PlanetPlan measuredPlan;
    public Timings timings() { return new Timings(selectionNanos,assemblyNanos,recordingNanos,conflictNanos,measuredPlan.indexNanos(),measuredPlan.compressionNanos()); }
    private final PlanetChunkGenerator planet;
    private final StructureSandbox.Context context;
    private final Map<String,String> skipped=new TreeMap<>();
    private final BitSet clearanceChecked=new BitSet(),cornerCells=new BitSet();
    public TraditionalPlanetPlanner(PlanetChunkGenerator planet,StructureSandbox.Context context) {
        this.planet=planet;this.context=context;
    }
    public Archive generate() {
        return generate(context.registries().registryOrThrow(Registries.STRUCTURE_SET).getTagOrEmpty(SystemChunkGenerator.STRUCTURES));
    }
    public Archive generate(Iterable<Holder<StructureSet>> sets) {
        return StructureSandbox.cacheSources(()->generateCached(sets));
    }
    private Archive generateCached(Iterable<Holder<StructureSet>> sets) {
        long selectionStart=System.nanoTime();
        var candidates=new ArrayList<Candidate>();
        int size=planet.terrain().shape().size();
        for(var holder:sets) {
            var set=holder.value();
            if(!(set.placement() instanceof RandomSpreadStructurePlacement placement)) {
                skipped.put(holder.unwrapKey().orElseThrow().location().toString(),"unsupported placement");continue;
            }
            var entries=set.structures().stream().filter(e->{
                boolean eligible=e.structure().value().biomes().stream().anyMatch(planet.getBiomeSource().possibleBiomes()::contains);
                if(!eligible)skipped.put(e.structure().unwrapKey().orElseThrow().location().toString(),"no planet biome belongs to structure.biomes");
                return eligible;
            }).toList();
            if(entries.isEmpty())continue;
            int[] boundaries=boundaries(size,placement.spacing()*16);
            long salt=Scatter.mix(holder.unwrapKey().orElseThrow().location().toString().hashCode());
            for(var face:Face.VALUES) for(int iu=0;iu<boundaries.length-1;iu++) for(int iv=0;iv<boundaries.length-1;iv++) {
                int gu=boundaries[iu],gv=boundaries[iv];
                if(!placement.applyAdditionalChunkRestrictions(face.ordinal()*256+(gu>>4),gv>>4,planet.seed()))continue;
                long hash=Scatter.mix(planet.seed() ^ salt ^ (long)face.ordinal()<<56 ^ (long)gu<<28 ^ gv);
                if ((Scatter.mix(hash ^ 0x6465736f6c617465L) >>> 11) * 0x1.0p-53
                    < planet.generation().recipe().value("preferences", "desolation")) continue;
                Candidate best=null;
                for(int sample=0;sample<8;sample++) {
                    long h=Scatter.mix(hash+sample);
                    int u=gu+(int)Long.remainderUnsigned(h,boundaries[iu+1]-gu);
                    int v=gv+(int)Long.remainderUnsigned(Scatter.mix(h),boundaries[iv+1]-gv);
                    int rawGround=planet.terrain().groundY(face,u,v);
                    int ground=planet.terrain().foundationTop(face,u,v,rawGround);
                    var biome=planet.getBiomeSource().getNoiseBiome((face.ordinal()*4096+u)/4,ground/4,v/4,context.random().sampler());
                    var eligible=entries.stream().filter(e->e.structure().value().biomes().contains(biome)).toList();
                    if(eligible.isEmpty())continue;
                    int weight=eligible.stream().mapToInt(StructureSet.StructureSelectionEntry::weight).sum();
                    int pick=(int)Long.remainderUnsigned(h,weight);
                    var structure=eligible.getFirst().structure();
                    for(var e:eligible) { pick-=e.weight();if(pick<0){structure=e.structure();break;} }
                    int[] heights=new int[9];int i=0;
                    for(int x:new int[]{-8,0,8})for(int z:new int[]{-8,0,8}) {
                        var c=StructureSandbox.canonical(planet.terrain(),face,u+x,v+z);
                        int raw=planet.terrain().groundY(c.face(),c.u(),c.v());
                        heights[i++]=planet.terrain().foundationTop(c.face(),c.u(),c.v(),raw);
                    }
                    Arrays.sort(heights);double roughness=0;
                    for(int height:heights)roughness+=Math.abs(height-heights[4])/9.;
                    int edge=Math.min(Math.min(u,v),Math.min(size-1-u,size-1-v));
                    double score=roughness+Math.max(0,8-edge)*.125+supportPenalty(planet.terrain(),face,u,v,rawGround,ground);
                    var fixed = planet.terrain().terrainPlan();
                    var point = planet.terrain().unit(face,u+.5,v+.5);
                    if (structure.is(RIVER_SETTLEMENTS)) {
                        double riverDistance = 128;
                        for (var path : fixed.paths())
                            for (int node : path.nodes()) riverDistance = Math.min(riverDistance,
                                fixed.position(node).sub(point).length()*size*.5);
                        score += riverDistance * .04;
                    }
                    if (structure.is(HIGHLAND_RUINS)) score -= ground * .025;
                    double overlap = planet.generation().recipe().value("preferences", "overlap");
                    double nearest = 128;
                    for (var prior : candidates) {
                        var p = planet.terrain().unit(prior.face,prior.u+.5,prior.v+.5);
                        nearest = Math.min(nearest,p.sub(point).length()*size*.5);
                    }
                    score += (overlap-.5) * nearest * .03;
                    long id=Scatter.mix(hash ^ h ^ structure.unwrapKey().orElseThrow().location().toString().hashCode());
                    var c=new Candidate(id,structure.unwrapKey().orElseThrow().location(),face,u,v,score);
                    if(best==null || score<best.score || score==best.score && Long.compareUnsigned(id,best.id)<0)best=c;
                }
                if(best!=null)candidates.add(best);
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).thenComparing((a,b)->Long.compareUnsigned(a.id,b.id)));
        selectionNanos=System.nanoTime()-selectionStart;
        var sites=new ArrayList<PlanetPlan.Site>();
        var footprints=new ArrayList<int[]>();
        var templates=new ArrayList<PlanetPlan.Template>();
        var templateIds=new HashMap<PlanetPlan.Template,Integer>();
        var recipes=new TreeMap<Long,CompoundTag>();
        var links = new ArrayList<Relation>();
        long references=0,indexColumns=0,footprintColumns=0;int pieceCount=0;
        // Vanilla jigsaw rotation caches are lazy and not safe for parallel assembly.
        for(var candidate:candidates) {
            long assemblyStart=System.nanoTime();Assembly assembly;
            try { assembly=assemble(candidate); }
            catch(RuntimeException e) {unsupported(skipped,candidate,e);continue;}
            finally { assemblyNanos+=System.nanoTime()-assemblyStart; }
            if(assembly.skipped!=null) {skipped.put(candidate.structure.toString(),assembly.skipped);continue;}
            var pieces=assembly.pieces;
            Recorded recorded;
            try {
                if(pieceCount+pieces.size()>PlanetPlan.MAX_TOTAL_PIECES) {
                    skipped.put(candidate.structure.toString(),"planet piece expansion budget reached; whole candidate rejected");continue;
                }
                var initial=describe(planet,context,candidate.id,candidate.structure,candidate.face,candidate.u,candidate.v,pieces);
                if(footprintColumns+PlanetPlan.footprintColumns(initial)>4_000_000) {
                    skipped.put(candidate.structure.toString(),"planet footprint enumeration budget reached; whole candidate rejected");continue;
                }
                var preview=footprint(initial);
                if(preview==null) {skipped.put(candidate.structure.toString(),"piece bbox/adaptation intersects corner anomaly");continue;}
                long recordingStart=System.nanoTime();
                try { recorded=recordPieces(planet,context,candidate.id,candidate.structure,candidate.face,candidate.u,candidate.v,pieces); }
                finally { recordingNanos+=System.nanoTime()-recordingStart; }}
            catch(IllegalArgumentException e) {skipped.put(candidate.structure.toString(),e.getMessage());continue;}
            catch(RuntimeException e) {unsupported(skipped,candidate,e);continue;}
            var recordedSite=recorded.site;
            var footprint=footprint(recordedSite);
            if(footprint==null) {skipped.put(candidate.structure.toString(),"piece bbox/adaptation intersects corner anomaly");continue;}
            var extent=PlanetPlan.extent(recordedSite);
            long columns=(long)(extent.maxU()-extent.minU()+1)*(extent.maxV()-extent.minV()+1);
            long blocks=recorded.templates.stream().mapToLong(t->t.blocks().size()).sum();
            long work=PlanetPlan.footprintColumns(recordedSite);
            if(references+blocks>4_000_000 || indexColumns+columns>4_000_000 || footprintColumns+work>4_000_000 || pieceCount+recordedSite.pieces().size()>PlanetPlan.MAX_TOTAL_PIECES) {
                skipped.put(candidate.structure.toString(),"planet recording/index expansion budget reached; whole candidate rejected");continue;
            }
            references+=blocks;indexColumns+=columns;footprintColumns+=work;pieceCount+=recordedSite.pieces().size();
            var planned=new ArrayList<PlanetPlan.Piece>();
            for(var piece:recordedSite.pieces()) {
                var template=recorded.templates.get(piece.template());
                int templateId=templateIds.getOrDefault(template,templates.size());
                if(templateId==templates.size()) {templateIds.put(template,templateId);templates.add(template);}
                planned.add(new PlanetPlan.Piece(templateId,piece.origin(),piece.rotation(),piece.mirror(),piece.bounds(),piece.terrain(),piece.adaptation()));
            }
            var site = new PlanetPlan.Site(recordedSite.id(),recordedSite.structure(),recordedSite.face(),recordedSite.u(),recordedSite.v(),recordedSite.bounds(),planned);
            site = effects(site, links);
            for (int prior=0;prior<sites.size();prior++)
                if (intersects(footprints.get(prior),footprint))
                    links.add(new Relation(
                        new Ref(Kind.SITE,sites.get(prior).id()),
                        "overlaps",new Ref(Kind.SITE,site.id())));
            sites.add(site);
            footprints.add(footprint.stream().toArray());
            recipes.put(recordedSite.id(),recorded.recipe);
        }
        var structureRegistry = context.registries().registryOrThrow(Registries.STRUCTURE);
        var villages = sites.stream().filter(site -> structureRegistry.getHolderOrThrow(
            net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE,site.structure())).is(RIVER_SETTLEMENTS)).toList();
        var terrain = TerrainRoadPlanner.connect(planet.terrain().terrainPlan(),villages,links);
        measuredPlan=new PlanetPlan(size,terrain,sites,templates);
        planet.terrain().initializeTerrain(terrain);
        return new Archive(measuredPlan,recipes,skipped);
    }
    private static boolean intersects(int[] columns,BitSet footprint) {
        for(int column:columns)if(footprint.get(column))return true;
        return false;
    }
    static double supportPenalty(dev.starfold.worldgen.PlanetTerrain terrain,Face face,int u,int v,int ground,int surface) {
        if(surface<=ground+4)return 0;
        var column=terrain.foundationBand(face,u,v,ground,ground+1,surface);
        for(int y=ground+1;y<surface;y++)
            if(column.state(y).isAir() || !column.state(y).getFluidState().isEmpty())return 32;
        return 0;
    }
    PlanetPlan.Site effects(PlanetPlan.Site site,
        List<Relation> links) {
        var terrain = planet.terrain().terrainPlan();
        double burial = terrain.recipe().value("preferences", "burial");
        long hash = Scatter.mix(planet.seed() ^ site.id());
        int depth = (hash >>> 11) * 0x1.0p-53 < burial
            ? Math.min(64, Math.min(site.bounds().minY()-planet.terrain().floor()-1, 4+(int)Math.round(burial*24))) : 0;
        double damage = terrain.recipe().value("preferences", "damage")
            * (.5 + .5 * (Scatter.mix(hash) >>> 11) * 0x1.0p-53);
        var cuts = new ArrayList<Ref>();
        var center = planet.terrain().unit(site.face(),site.u()+.5,site.v()+.5);
        double radius = Math.hypot(site.bounds().maxU()-site.bounds().minU(),site.bounds().maxV()-site.bounds().minV())*.5+16;
        for (int i=0;i<terrain.paths().size() && cuts.size()<64;i++) {
            var path=terrain.paths().get(i);
            if(path.nodes().stream().anyMatch(node -> terrain.position(node).sub(center).length()*terrain.size()*.5 <= radius+path.width()))
                cuts.add(new Ref(Kind.RIVER,i));
        }
        for (int i=0;i<terrain.volumes().size() && cuts.size()<64;i++) {
            var volume=terrain.volumes().get(i);
            if(volume.operation()==Operation.CARVE
                && volume.center().sub(center).length()*terrain.size()*.5 <= radius+volumeReach(volume.radiusU(),volume.radiusV(),terrain.size()))
                cuts.add(new Ref(Kind.VOLUME,i));
        }
        for (int i=0;i<terrain.landforms().size() && cuts.size()<64;i++) {
            var form=terrain.landforms().get(i);
            if(form.center().sub(center).length()*terrain.size()*.5 <= radius+form.radius())
                cuts.add(new Ref(Kind.LANDFORM,i));
        }
        var ref = new Ref(Kind.SITE,site.id());
        if(depth>0)links.add(new Relation(ref,"buries",ref));
        if(damage>0)links.add(new Relation(ref,"damages",ref));
        for(var cut:cuts)links.add(new Relation(cut,"cuts",ref));
        return new PlanetPlan.Site(site.id(),site.structure(),site.face(),site.u(),site.v(),site.bounds(),site.pieces(),
            new PlanetPlan.Effects(Math.max(0,depth),damage,cuts));
    }
    /** Structures come from any data pack and may call level methods the sandbox lacks; skip that one, keep the planet. */
    private static void unsupported(Map<String,String> skipped,Candidate candidate,RuntimeException e) {
        org.slf4j.LoggerFactory.getLogger("starfold").warn("Planet plan skipped {}",candidate.structure,e);
        skipped.put(candidate.structure.toString(),String.valueOf(e));
    }
    private Assembly assemble(Candidate candidate) {
        var structure=context.registries().registryOrThrow(Registries.STRUCTURE).get(candidate.structure);
        var raw=StructureSandbox.generator(planet,candidate.face);
        var chunk=new ChunkPos(candidate.u>>4,candidate.v>>4);
        var generation=new Structure.GenerationContext(context.registries(),raw,raw.getBiomeSource(),context.random(),context.templates(),
                Scatter.mix(planet.seed() ^ candidate.id),chunk,LevelHeightAccessor.create(planet.terrain().floor(),320-planet.terrain().floor()),b->true);
        var point=structure.findValidGenerationPoint(generation);
        if(point.isEmpty())return new Assembly(List.of(),"vanilla findValidGenerationPoint returned empty in L1 context");
        var pieces=point.get().getPiecesBuilder().build().pieces();
        if(pieces.isEmpty())return new Assembly(List.of(),"vanilla generation returned no pieces");
        var fullBox=BoundingBox.encapsulatingBoxes(pieces.stream().map(StructurePiece::getBoundingBox).toList()).orElseThrow();
        int dx=candidate.u-(fullBox.minX()+fullBox.maxX())/2,dz=candidate.v-(fullBox.minZ()+fullBox.maxZ())/2;
        for(var piece:pieces)piece.move(dx,0,dz);
        return new Assembly(pieces,null);
    }
    /** Record every vanilla piece in generation order, retaining AIR and overlaps. */
    static Archive record(PlanetChunkGenerator planet,StructureSandbox.Context context,long id,ResourceLocation structureId,
                          Face face,int u,int v,List<StructurePiece> pieces) {
        var recorded=recordPieces(planet,context,id,structureId,face,u,v,pieces);
        return new Archive(new PlanetPlan(planet.terrain().shape().size(),planet.terrain().terrainPlan(),List.of(recorded.site),recorded.templates),Map.of(id,recorded.recipe),Map.of());
    }
    private static Recorded recordPieces(PlanetChunkGenerator planet,StructureSandbox.Context context,long id,ResourceLocation structureId,
                                         Face face,int u,int v,List<StructurePiece> pieces) {
        var initial=describe(planet,context,id,structureId,face,u,v,pieces);
        var descriptors=initial.pieces();
        PlanetPlan.Bounds full;
        var recipes=StructureSandbox.recipes(pieces,context);
        var results=StructureSandbox.recordPieces(planet,context,face,initial,recipes,pieces,id);
        recipes.put("entities",StructureEntities.save(results));
        var templates=new ArrayList<PlanetPlan.Template>();var planned=new ArrayList<PlanetPlan.Piece>();full=null;
        for(int i=0;i<pieces.size();i++) {
            var descriptor=descriptors.get(i);var blocks=results.get(i).blocks();
            var origin=descriptor.origin();
            PlanetPlan.Bounds bounds=null;var voxels=new ArrayList<PlanetPlan.Voxel>();
            for(var e:blocks.entrySet()) {
                var p=e.getKey();
                if(p.getY()<=planet.terrain().floor())throw new IllegalArgumentException("vanilla writes intersect PlanetCore shell; whole structure rejected");
                voxels.add(new PlanetPlan.Voxel(p.getX()-origin.getX(),p.getY()-origin.getY(),p.getZ()-origin.getZ(),e.getValue()));
                var b=new PlanetPlan.Bounds(p.getX(),p.getY(),p.getZ(),p.getX(),p.getY(),p.getZ());
                bounds=bounds==null ? b : PlanetPlan.union(bounds,b);
            }
            bounds=bounds==null ? descriptor.bounds() : PlanetPlan.union(bounds,descriptor.bounds());
            full=full==null ? bounds : PlanetPlan.union(full,bounds);
            templates.add(new PlanetPlan.Template(voxels));
            planned.add(new PlanetPlan.Piece(i,origin,descriptor.rotation(),descriptor.mirror(),bounds,descriptor.terrain(),descriptor.adaptation()));
        }
        var site=new PlanetPlan.Site(id,structureId,face,u,v,full,planned);
        var extent=PlanetPlan.extent(site);
        if(extent.maxU()-extent.minU()>512 || extent.maxV()-extent.minV()>512)
            throw new IllegalArgumentException("vanilla result exceeds bounded 512-column chart span");
        return new Recorded(site,List.copyOf(templates),recipes);
    }
    private static PlanetPlan.Site describe(PlanetChunkGenerator planet,StructureSandbox.Context context,long id,ResourceLocation structureId,
                                            Face face,int u,int v,List<StructurePiece> pieces) {
        if(pieces.size()>PlanetPlan.MAX_PIECES)throw new IllegalArgumentException("vanilla result exceeds 1024-piece recording bound");
        var structure=context.registries().registryOrThrow(Registries.STRUCTURE).get(structureId);
        var descriptors=new ArrayList<PlanetPlan.Piece>();
        PlanetPlan.Bounds full=null;
        for(var piece:pieces) {
            var box=piece.getBoundingBox();
            var bounds=new PlanetPlan.Bounds(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ());
            var adaptation=structure.terrainAdaptation();
            if(adaptation!=TerrainAdjustment.NONE && !(piece instanceof PoolElementStructurePiece))
                throw new IllegalArgumentException("non-pool piece with non-default terrain_adaptation requires L1 dynamic-height relocation (unsupported)");
            if(piece instanceof PoolElementStructurePiece pool) {
                if(pool.getElement().getProjection()==net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.Projection.TERRAIN_MATCHING)
                    adaptation=TerrainAdjustment.NONE;
                else bounds=new PlanetPlan.Bounds(bounds.minU(),bounds.minY()+pool.getGroundLevelDelta(),bounds.minV(),bounds.maxU(),Math.max(bounds.minY()+pool.getGroundLevelDelta(),bounds.maxY()),bounds.maxV());
            }
            if(box.getXSpan()>256 || box.getZSpan()>256 || box.minY()<planet.terrain().floor() || box.maxY()>=320)
                throw new IllegalArgumentException("vanilla piece intersects core shell/build limit or exceeds recording span");
            full=full==null ? bounds : PlanetPlan.union(full,bounds);
            descriptors.add(new PlanetPlan.Piece(0,box.getCenter(),piece.getRotation(),
                    piece instanceof TemplateStructurePiece template ? template.placeSettings().getMirror() : net.minecraft.world.level.block.Mirror.NONE,
                    bounds,bounds,adaptation));
        }
        return new PlanetPlan.Site(id,structureId,face,u,v,full,descriptors);
    }
    // Equal chart partitions reflect onto the same edge lattice, including non-divisible face sizes.
    static int[] boundaries(int size,int spacing) {
        int n=(size+spacing-1)/spacing;
        int[] edges=new int[n+1];
        for(int i=0;i<=n;i++)edges[i]=(int)Math.rint(i*(double)size/n);
        return edges;
    }
    private BitSet footprint(PlanetPlan.Site site) {
        long start=System.nanoTime();
        try {
        int size=planet.terrain().shape().size();
        if(PlanetPlan.footprintColumns(site)>4_000_000)throw new IllegalArgumentException("piece footprint enumeration budget");
        var cells=PlanetPlan.footprintCells(site,size);
        for(int key=cells.nextSetBit(0);key>=0;key=cells.nextSetBit(key+1)) {
            int v=key%size,u=key/size%size;var face=Face.VALUES[key/(size*size)];
            if(!clearanceChecked.get(key)) {
                clearanceChecked.set(key);
                if(Anomaly.distance(planet.terrain().unit(face,u+.5,v+.5),planet.terrain().shape())<Anomaly.CLEAR)cornerCells.set(key);
            }
            if(cornerCells.get(key))return null;
        }
        return cells;
        } finally { conflictNanos+=System.nanoTime()-start; }
    }
}
