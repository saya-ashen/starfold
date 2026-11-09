package dev.starfold.worldgen.plan;

import static dev.starfold.worldgen.plan.terrain.TerrainPlan.*;

import com.google.gson.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.*;
import dev.starfold.worldgen.plan.terrain.*;
import dev.starfold.worldgen.surface.SurfaceFunction;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.*;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.*;

/** Explicit synthetic composition atlas plus a real vanilla buried pyramid, all registry-only CPU work. */
final class TerrainPlanS2Evaluation {
    static void roadMeasurement(TerrainPlan fixed, java.io.Writer csv) throws Exception {
        int size=fixed.size();
        var a=TerrainPlanS2BTest.site(10,Face.TOP,size/3,size/2,161,0,PlanetPlan.Effects.NONE);
        var b=TerrainPlanS2BTest.site(20,Face.EAST,size/3,size/2,161,0,PlanetPlan.Effects.NONE);
        long start=System.nanoTime();
        var routes=TerrainRoadPlanner.connect(fixed,java.util.List.of(a,b),new ArrayList<>());
        double ms=(System.nanoTime()-start)/1e6;
        var template=new PlanetPlan.Template(java.util.List.of(new PlanetPlan.Voxel(0,0,0,Blocks.STONE.defaultBlockState())));
        var plan=new PlanetPlan(size,routes,java.util.List.of(a,b),java.util.List.of(template));
        int nodes=routes.roads().stream().mapToInt(road->road.nodes().size()).sum();
        csv.write(String.format(Locale.ROOT,"%d,%.3f,%d,%d,%d%n",size,ms,routes.roads().size(),nodes,plan.compressed().length));
    }
    static void scenes(java.nio.file.Path output) throws Exception {
        var base=TerrainPlanS2BTest.flat(1);
        var fields=new float[6][base.nodeCount()];
        for(int f=0;f<6;f++)for(int i=0;i<base.nodeCount();i++)fields[f][i]=base.field(f,i);
        for(int u=8;u<=11;u++)for(int v=6;v<=10;v++)fields[HEIGHT][base.node(Face.TOP,u,v)]=180;
        var rivers=new ArrayList<TerrainPlan.Path>();
        for(int u:java.util.List.of(10,16,22)) {
            var nodes=new ArrayList<Integer>();
            for(int v=8;v<=24;v++)nodes.add(base.node(Face.TOP,u,v));
            rivers.add(new TerrainPlan.Path(nodes,6,3,159,Operation.CARVE));
        }
        base=new TerrainPlan(42,512,base.recipe(),fields,new short[base.nodeCount()],new byte[base.nodeCount()],java.util.List.of(),rivers);
        var shapes=java.util.List.of(
            wallVolume(base,192,128),
            TerrainPlanS2BTest.volume("arch",point(256,128),169,28,22,10,Operation.FILL),
            TerrainPlanS2BTest.volume("undercut",point(320,128),151,24,6,12,Operation.CARVE),
            TerrainPlanS2BTest.volume("cave_mouth",point(384,128),152,20,12,14,Operation.CARVE));
        base=base.withContent(java.util.List.of(),shapes,java.util.List.of());
        var a=house(10,112,208,PlanetPlan.Effects.NONE);
        var b=house(20,400,208,new PlanetPlan.Effects(8,.25,java.util.List.of()));
        var c=house(30,112,304,PlanetPlan.Effects.NONE);
        var d=house(40,400,304,new PlanetPlan.Effects(0,.4,java.util.List.of()));
        var bridged=TerrainRoadPlanner.connect(base,java.util.List.of(a,b),new ArrayList<>());
        var brokenJson=base.recipe().json();brokenJson.getAsJsonObject("preferences").addProperty("road_continuity",0);
        var brokenBase=new TerrainPlan(42,512,new TerrainRecipe(brokenJson),fields,new short[base.nodeCount()],new byte[base.nodeCount()],java.util.List.of(),rivers,
            java.util.List.of(),shapes,java.util.List.of());
        var broken=TerrainRoadPlanner.connect(brokenBase,java.util.List.of(c,d),new ArrayList<>());
        if(bridged.roads().stream().noneMatch(road->road.modes().contains(RoadMode.BRIDGE))
            || broken.roads().stream().noneMatch(road->road.modes().contains(RoadMode.CUT)))
            throw new IllegalStateException("S2 scene must contain actual bridge and broken road segments");
        var roads=new ArrayList<>(bridged.roads());roads.addAll(broken.roads());
        var links=new ArrayList<>(bridged.relations());
        for(var link:broken.relations())links.add(new Relation(rebaseRoad(link.source()),link.relation(),rebaseRoad(link.target())));
        links.add(new Relation(new Ref(Kind.SITE,b.id()),"buries",new Ref(Kind.SITE,b.id())));
        links.add(new Relation(new Ref(Kind.SITE,b.id()),"damages",new Ref(Kind.SITE,b.id())));
        links.add(new Relation(new Ref(Kind.SITE,d.id()),"damages",new Ref(Kind.SITE,d.id())));
        var fixed=base.withContent(links,shapes,roads);
        var blocks=new ArrayList<PlanetPlan.Voxel>();
        for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)for(int y=0;y<=10;y++)
            blocks.add(new PlanetPlan.Voxel(x,y,z,y==0?Blocks.COBBLESTONE.defaultBlockState():y>=9?Blocks.OAK_PLANKS.defaultBlockState():
                Math.abs(x)==4 || Math.abs(z)==4?Blocks.STONE_BRICKS.defaultBlockState():Blocks.AIR.defaultBlockState()));
        var plan=new PlanetPlan(512,fixed,java.util.List.of(a,b,c,d),java.util.List.of(new PlanetPlan.Template(blocks)));
        var terrain=TerrainPlanS2BTest.terrain(fixed);terrain.initializePlan(()->plan);
        var function=new SurfaceFunction(terrain);
        section(function,Face.TOP,128,output.resolve("s3-overhang-arch-section.png"));
        volumeSection(function,fixed,shapes.get(0),output.resolve("s3-overhang-section.png"));
        volumeSection(function,fixed,shapes.get(1),output.resolve("s3-arch-section.png"));
        section(function,Face.TOP,208,output.resolve("s3-bridge-section.png"));
        section(function,Face.TOP,304,output.resolve("s3-broken-road-section.png"));
        var image=new BufferedImage(840,552,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.setColor(new Color(0x182028));g.fillRect(0,0,840,552);
        for(int u=0;u<512;u++)for(int v=0;v<512;v++) {
            var cell=function.point(Face.TOP,u,v);
            int shade=(int)Math.clamp((cell.height()-130)*3,20,180);
            image.setRGB(u,v+32,(shade<<16)|(shade<<8)|shade);
        }
        g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,12));g.setColor(Color.WHITE);
        g.drawString("Synthetic composition fixture: evaluated TOP face, seed 42, 512 blocks",8,20);
        for(int i=0;i<rivers.size();i++) {
            g.setColor(Color.CYAN);g.drawString("river "+i,(i*6+10)*16+4,180);
        }
        for(int i=0;i<shapes.size();i++) {
            var chart=fixed.chart(shapes.get(i).center());g.setColor(Color.ORANGE);
            g.drawOval((int)chart.u()-26,(int)chart.v()+6,52,52);
            g.drawString(shapes.get(i).type().replace("starfold:",""),(int)chart.u()-28,(int)chart.v()+3);
        }
        for(int road=0;road<roads.size();road++) {
            var route=roads.get(road);
            for(int i=1;i<route.nodes().size();i++) {
                g.setColor(route.modes().get(i-1)==RoadMode.CUT?Color.RED:route.modes().get(i-1)==RoadMode.BRIDGE?Color.GREEN:Color.LIGHT_GRAY);
                for(int sample=1;sample<=8;sample++) {
                    var start=fixed.chart(fixed.roadPoint(route,i,(sample-1)/8.0));
                    var end=fixed.chart(fixed.roadPoint(route,i,sample/8.0));
                    g.drawLine((int)start.u(),(int)start.v()+32,(int)end.u(),(int)end.v()+32);
                }
            }
        }
        for(var site:plan.sites()) { g.setColor(Color.YELLOW);g.drawRect(site.u()-6,site.v()+26,12,12);g.drawString("site "+site.id(),site.u()-24,site.v()+18); }
        g.setColor(new Color(0x80b0ff));
        for(var link:links) {
            var source=position(fixed,plan,link.source());var target=position(fixed,plan,link.target());
            g.drawLine(source.x,source.y+32,target.x,target.y+32);
        }
        g.setColor(Color.WHITE);int y=40;
        for(String text:java.util.List.of("Explicit synthetic houses/roads/volumes.","Gray: evaluated surface height.","Green: saved bridge segments.","Red: saved broken road segments.","Blue lines: fixed typed relations.","Yellow: house footprints.","Site 20: moved down 8, backfilled, damaged.","Site 40: damaged.","All blocks use SurfaceFunction.","Separate buried section: real vanilla pyramid.")) {g.drawString(text,524,y);y+=20;}
        y+=20;
        for(var link:links) {if(y>535)break;g.drawString(link.source().kind()+" "+link.source().id()+" "+link.relation()+" "+link.target().kind()+" "+link.target().id(),524,y);y+=16;}
        g.dispose();ImageIO.write(image,"png",output.resolve("s3-features-relations.png").toFile());
        var metadata=new JsonObject();metadata.addProperty("synthetic",true);metadata.addProperty("description","Controlled cliff field, houses, volumes and two independently planned routes; upper continuity=1, lower continuity=0. Not a naturally selected village arrangement.");
        metadata.addProperty("roads",roads.size());metadata.addProperty("volumes",shapes.size());metadata.addProperty("relations",links.size());
        Files.writeString(output.resolve("s3-scene.json"),new GsonBuilder().setPrettyPrinting().create().toJson(metadata));
        vanillaBurial(output);
        naturalHome(output);
        var desert=TerrainPlanTest.planet("desert").terrain();
        var cap=desert.terrainPlan().volumes().stream().filter(volume->volume.type().equals("starfold:overhang")).findFirst().orElseThrow();
        volumeSection(new SurfaceFunction(desert),desert.terrainPlan(),cap,output.resolve("s3-natural-overhang-section.png"));
    }
    static void naturalHome(java.nio.file.Path output) throws Exception { naturalHome(output,false); }
    static void naturalHome(java.nio.file.Path output,boolean benchmark) throws Exception {
        var source=TerrainPlanTest.planet("home");var original=source.generation();
        var generation=new PlanetGeneration(original.type(),PlanetGeneration.SURFACE_VERSION,original.recipe(),2048,191,
            original.shell(),false,false,true,PlanetEnvironment.defaults(true,2048));
        var palette=original.recipe().biomeIds().stream().sorted(Comparator.comparing(ResourceLocation::toString))
            .map(id->(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>)PlanetPlanFixtures.context.registries()
                .registryOrThrow(net.minecraft.core.registries.Registries.BIOME).getHolderOrThrow(
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,id))).toList();
        var planet=new dev.starfold.universe.PlanetChunkGenerator(source.settings(),42,PlanetTerrain.VERSION,
            Optional.of(new PlanetBiomes(42,palette)),Optional.of(generation));
        PlanetPlan reference=null;
        if(benchmark) {
            var root=net.minecraft.nbt.NbtIo.readCompressed(java.nio.file.Path.of("build/terrain-plan/s3-before/s2-natural-home.dat"),net.minecraft.nbt.NbtAccounter.create(64_000_000));
            reference=PlanetPlan.decode(root.getByteArray("plan"));
            planet.terrain().initializeTerrain(reference.terrain().withContent(java.util.List.of(),reference.terrain().volumes(),java.util.List.of()));
            Files.write(output.resolve("reference-plan.bin"),reference.compressed());
        }
        long start=System.nanoTime();
        var planner=new TraditionalPlanetPlanner(planet,PlanetPlanFixtures.context);
        var archive=planner.generate();
        double elapsed=(System.nanoTime()-start)/1e6;
        if(reference!=null) {
            var normalized=new PlanetPlan(2048,reference.terrain(),archive.plan().sites(),archive.plan().templates());
            Files.write(output.resolve("optimized-plan.bin"),normalized.compressed());
            if(!Arrays.equals(reference.compressed(),normalized.compressed())) {
                for(int i=0;i<Math.min(reference.sites().size(),normalized.sites().size());i++)if(!reference.sites().get(i).equals(normalized.sites().get(i))) {
                    System.out.println("S3_FIRST_SITE_DIFFERENCE "+i+" old="+reference.sites().get(i)+" new="+normalized.sites().get(i));break;
                }
                for(int i=0;i<Math.min(reference.templates().size(),normalized.templates().size());i++)if(!reference.templates().get(i).equals(normalized.templates().get(i))) {
                    System.out.println("S3_FIRST_TEMPLATE_DIFFERENCE "+i+" old="+reference.templates().get(i).blocks().size()+" new="+normalized.templates().get(i).blocks().size());
                    var old=reference.templates().get(i).blocks();var now=normalized.templates().get(i).blocks();
                    for(int j=0;j<Math.min(old.size(),now.size());j++)if(!old.get(j).equals(now.get(j))){System.out.println("S3_FIRST_VOXEL "+j+" "+old.get(j)+" / "+now.get(j));break;}break;
                }
                throw new IllegalStateException("Structure optimization changed saved site/template bytes");
            }
        }
        if(archive.plan().sites().isEmpty())throw new IllegalStateException("Natural home scene must contain planned vanilla structures");
        planet.terrain().initializePlan(archive::plan);
        PlanetPlanStore.write(output.resolve("s3-natural-home.dat"),planet,archive);
        var metadata=new JsonObject();metadata.addProperty("synthetic",false);metadata.addProperty("size",2048);metadata.addProperty("seed",42);
        metadata.addProperty("description","Unmodified shipped home recipe, normal planner and vanilla structure sets; creatures/features disabled for terrain visibility, structures enabled.");
        metadata.addProperty("plan_sha256",archive.plan().identity());
        metadata.add("timings_ns",new Gson().toJsonTree(planner.timings()));
        metadata.addProperty("planning_ms",elapsed);metadata.addProperty("roads",archive.plan().terrain().roads().size());
        metadata.addProperty("volumes",archive.plan().terrain().volumes().size());metadata.addProperty("relations",archive.plan().terrain().relations().size());
        var sites=new JsonArray();
        for(var site:archive.plan().sites()) {
            var json=new JsonObject();json.addProperty("id",site.id());json.addProperty("structure",site.structure().toString());
            json.addProperty("face",site.face().toString());json.addProperty("u",site.u());json.addProperty("v",site.v());
            json.addProperty("burial",site.effects().burial());json.addProperty("damage",site.effects().damage());sites.add(json);
        }
        metadata.add("sites",sites);
        var focus=archive.plan().sites().stream().max(Comparator.comparingInt((PlanetPlan.Site site)->
            site.bounds().maxY()-site.effects().burial()-planet.terrain().surfaceY(site.face(),site.u(),site.v()))).orElseThrow();
        metadata.addProperty("focus_site",focus.id());metadata.addProperty("focus_face",focus.face().toString());
        metadata.addProperty("focus_u",focus.u());metadata.addProperty("focus_v",focus.v());
        Files.writeString(output.resolve("s3-natural-home.json"),new GsonBuilder().setPrettyPrinting().create().toJson(metadata));
        if(benchmark)return;
        var function=new SurfaceFunction(planet.terrain());
        var image=new BufferedImage(512,540,BufferedImage.TYPE_INT_RGB);
        for(int x=0;x<512;x++)for(int z=0;z<512;z++) {
            var cell=function.point(focus.face(),focus.u()-256+x,focus.v()-256+z);
            image.setRGB(x,z+28,0x404040|(cell.materials().getFirst().block().toString().hashCode()&0xbfbfbf));
        }
        var g=image.createGraphics();g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,12));
        g.drawString("Natural home 2048 / seed 42 / "+focus.face()+" / "+focus.structure(),6,18);
        for(var site:archive.plan().sites())if(site.face()==focus.face()) {
            int x=site.u()-focus.u()+256,z=site.v()-focus.v()+284;
            g.setColor(Color.YELLOW);g.drawRect(x-6,z-6,12,12);g.drawString(site.structure().getPath(),x+8,z);
        }
        g.dispose();ImageIO.write(image,"png",output.resolve("s3-natural-home.png").toFile());
        if(!archive.plan().terrain().roads().isEmpty()) {
            var fixed=archive.plan().terrain();var route=fixed.roads().getFirst();
            var middle=fixed.chart(fixed.roadPoint(route,Math.max(1,route.nodes().size()/2),.5));
            int originU=(int)middle.u()-256,originV=(int)middle.v()-256;
            var roadsImage=new BufferedImage(512,540,BufferedImage.TYPE_INT_RGB);
            for(int x=0;x<512;x++)for(int z=0;z<512;z++) {
                var cell=function.point(middle.face(),originU+x,originV+z);
                int shade=(int)Math.clamp((cell.height()+64)*.6,20,220);
                int color=cell.materials().getFirst().block().is(Blocks.DIRT_PATH)?0xd8ad70:
                    cell.materials().getFirst().block().is(Blocks.OAK_PLANKS)?0x77cc66:(shade<<16)|(shade<<8)|shade;
                roadsImage.setRGB(x,z+28,color);
            }
            var draw=roadsImage.createGraphics();draw.setColor(Color.WHITE);draw.drawString("Natural home 2048: evaluated curved settlement roads",6,18);draw.dispose();
            ImageIO.write(roadsImage,"png",output.resolve("s3-roads.png").toFile());
        }
    }
    private static Volume wallVolume(TerrainPlan terrain,int u,int v) throws Exception {
        var center=point(u,v);var tangent=center.cross(Face.EAST.normal);tangent=tangent.scale(1/tangent.length());
        var across=center.cross(tangent);var direction=Face.TOP.u.sub(center.scale(center.dot(Face.TOP.u)));direction=direction.scale(1/direction.length());
        var parameters=TerrainPlanS2BTest.parameters("fill");parameters.addProperty("wall_x",direction.dot(tangent));parameters.addProperty("wall_z",direction.dot(across));
        return new Volume("starfold:overhang",parameters,center,175,24,8,16,Operation.FILL);
    }
    private static Ref rebaseRoad(Ref ref) { return ref.kind()==Kind.ROAD?new Ref(Kind.ROAD,ref.id()+1):ref; }
    private static Point point(int u,int v) { return PlanetTerrain.direction(new dev.starfold.chart.PlanetShape(512),Face.TOP,u,v); }
    private static PlanetPlan.Site house(long id,int u,int v,PlanetPlan.Effects effects) {
        var bounds=new PlanetPlan.Bounds(u-4,161,v-4,u+4,171,v+4);
        var piece=new PlanetPlan.Piece(0,new BlockPos(u,161,v),Rotation.NONE,Mirror.NONE,bounds,bounds,TerrainAdjustment.NONE);
        return new PlanetPlan.Site(id,ResourceLocation.parse("test:synthetic_house"),Face.TOP,u,v,bounds,java.util.List.of(piece),effects);
    }
    private static java.awt.Point position(TerrainPlan terrain,PlanetPlan plan,Ref ref) {
        TerrainPlan.Chart chart=switch(ref.kind()) {
            case VOLUME -> terrain.chart(terrain.volumes().get((int)ref.id()).center());
            case RIVER -> {var nodes=terrain.paths().get((int)ref.id()).nodes();yield terrain.chart(terrain.position(nodes.get(nodes.size()/2)));}
            case ROAD -> {var nodes=terrain.roads().get((int)ref.id()).nodes();yield terrain.chart(terrain.position(nodes.get(nodes.size()/2)));}
            case SITE -> {var site=plan.site(ref.id());yield new TerrainPlan.Chart(site.face(),site.u(),site.v());}
            case LANDFORM -> terrain.chart(terrain.landforms().get((int)ref.id()).center());
        };
        return new java.awt.Point((int)chart.u(),(int)chart.v());
    }
    private static void section(SurfaceFunction function,Face face,int v,java.nio.file.Path path) throws Exception {
        var image=new BufferedImage(512,384,BufferedImage.TYPE_INT_RGB);
        for(int u=0;u<512;u++) {
            var blocks=function.blocks(face,u,v);
            for(int y=-64;y<320;y++)image.setRGB(u,319-y,blocks[y+64].isAir()?0x101820:0x404040|(blocks[y+64].toString().hashCode()&0xbfbfbf));
        }
        ImageIO.write(image,"png",path.toFile());
    }
    private static void volumeSection(SurfaceFunction function,TerrainPlan terrain,Volume volume,java.nio.file.Path path) throws Exception {
        var center=volume.center();var axis=Math.abs(center.y())<.8?Face.TOP.normal:Face.EAST.normal;
        var tangent=center.cross(axis);tangent=tangent.scale(1/tangent.length());
        if(volume.parameters().has("wall_x"))tangent=tangent.scale(volume.parameters().get("wall_x").getAsDouble())
            .add(center.cross(tangent).scale(volume.parameters().get("wall_z").getAsDouble()));
        var image=new BufferedImage(512,384,BufferedImage.TYPE_INT_RGB);
        for(int x=0;x<512;x++) {
            var direction=center.add(tangent.scale((x-256)/(terrain.size()*2.0)));direction=direction.scale(1/direction.length());
            var chart=terrain.chart(direction);var blocks=function.blocks(chart.face(),(int)chart.u(),(int)chart.v());
            for(int y=-64;y<320;y++)image.setRGB(x,319-y,blocks[y+64].isAir()?0x101820:0x404040|(blocks[y+64].toString().hashCode()&0xbfbfbf));
        }
        ImageIO.write(image,"png",path.toFile());
    }
    private static void vanillaBurial(java.nio.file.Path output) throws Exception {
        var planet=PlanetPlanFixtures.planet(512,"desert");
        var pieces=PlanetPlanTest.generate(planet,"desert_pyramid",3);
        var bounds=BoundingBox.encapsulatingBoxes(pieces.stream().map(StructurePiece::getBoundingBox).toList()).orElseThrow();
        int dx=256-(bounds.minX()+bounds.maxX())/2,dz=256-(bounds.minZ()+bounds.maxZ())/2;
        for(var piece:pieces)piece.move(dx,0,dz);
        var original=TraditionalPlanetPlanner.record(planet,PlanetPlanFixtures.context,3,ResourceLocation.withDefaultNamespace("desert_pyramid"),Face.NORTH,256,256,pieces);
        var site=original.plan().sites().getFirst();
        var buried=new PlanetPlan.Site(site.id(),site.structure(),site.face(),site.u(),site.v(),site.bounds(),site.pieces(),new PlanetPlan.Effects(10,.25,java.util.List.of()));
        var plan=new PlanetPlan(512,original.plan().terrain(),java.util.List.of(buried),original.plan().templates());
        planet.terrain().initializePlan(()->plan);
        section(new SurfaceFunction(planet.terrain()),Face.NORTH,256,output.resolve("s3-buried-section.png"));
        PlanetPlanStore.write(output.resolve("s3-vanilla-pyramid.dat"),planet,new TraditionalPlanetPlanner.Archive(plan,original.recipes(),original.skipped()));
    }
}
