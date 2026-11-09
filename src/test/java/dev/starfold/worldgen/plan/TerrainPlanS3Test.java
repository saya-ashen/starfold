package dev.starfold.worldgen.plan;

import static dev.starfold.worldgen.plan.terrain.TerrainPlan.*;
import static org.junit.jupiter.api.Assertions.*;
import dev.starfold.api.PlanetSeasons;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.plan.terrain.*;
import dev.starfold.worldgen.surface.SurfaceFunction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import org.junit.jupiter.api.*;

/** CPU S3 contracts: curved roads, regional coverage, attached caps and the shared climate atlas. */
class TerrainPlanS3Test {
    static final List<String> TYPES=List.of("home","archipelago","lush","desert","ice","rocky","asteroid","basalt","crystal","end","mushroom","warden","flesh");
    @BeforeAll static void boot() throws Exception { PlanetPlanFixtures.boot(); }
    @AfterAll static void close() throws Exception { PlanetPlanFixtures.close(); }
    static TerrainRecipe recipe(String type) throws Exception {
        var path=Path.of(type.equals("flesh")?"biotech/src/main/resources/data/starfold_biotech/starfold/planet_type/flesh.json":
            "worlds/src/main/resources/data/starfold_worlds/starfold/planet_type/"+type+".json");
        return new TerrainRecipe(com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("terrain"));
    }
    @Test void foundationCachedBandsRetainOneCellSemanticsThroughColdUndergroundCaves() throws Exception {
        var base=TerrainPlanS2BTest.flat(1);var fields=new float[6][base.nodeCount()];
        for(int f=0;f<6;f++)for(int n=0;n<base.nodeCount();n++)fields[f][n]=base.field(f,n);
        Arrays.fill(fields[TEMPERATURE],-30);
        for(boolean snowMaterial:new boolean[]{false,true}) {
            var json=base.recipe().json();
            if(snowMaterial) {
                var rule=json.getAsJsonArray("materials").get(4).getAsJsonObject().deepCopy();
                var p=rule.getAsJsonObject("parameters");p.addProperty("block","minecraft:snow");
                p.addProperty("depth_min",0);p.addProperty("depth_max",1024);
                var materials=new com.google.gson.JsonArray();materials.add(rule);json.add("materials",materials);
            }
            var fixed=new TerrainPlan(42,512,new TerrainRecipe(json),fields,new short[base.nodeCount()],new byte[base.nodeCount()],List.of(),List.of());
            var terrain=TerrainPlanS2BTest.terrain(fixed);
            for(var face:Face.VALUES)for(int u:new int[]{160,256,384}) {
                int ground=terrain.groundY(face,u,256);
                var cache=new StructureSandbox.Foundation(terrain,face,u,256,null);
                for(int y=terrain.floor()+1;y<ground+8;y++)assertSame(terrain.foundationBand(face,u,256,ground,y,y+1).state(y),cache.state(y),face+" "+u+" "+y);
                if(snowMaterial)assertTrue(cache.state(ground).is(Blocks.SNOW),"declared snow material survives caching");
            }
        }
    }
    @Test void roadsUseDiagonalStepsAndContinuousTangentsIncludingSeams() throws Exception {
        var base=TerrainPlanS2BTest.flat(1);
        var a=TerrainPlanS2BTest.site(1,Face.TOP,96,144,161,0,PlanetPlan.Effects.NONE);
        var b=TerrainPlanS2BTest.site(2,Face.EAST,192,320,161,0,PlanetPlan.Effects.NONE);
        var fixed=TerrainRoadPlanner.connect(base,List.of(a,b),new ArrayList<>());
        var road=fixed.roads().getFirst();boolean diagonal=false;
        for(int i=1;i<road.nodes().size();i++) {
            var start=fixed.chart(fixed.position(road.nodes().get(i-1)));
            var end=fixed.chart(fixed.position(road.nodes().get(i)));
            if(start.face()==end.face() && Math.abs(start.u()-end.u())>1 && Math.abs(start.v()-end.v())>1)diagonal=true;
            for(int j=0;j<=8;j++) {
                var p=fixed.roadPoint(road,i,j/8.0);var c=fixed.chart(p);
                assertNotNull(fixed.road(c.face(),c.u(),c.v(),p),i+" "+j);
            }
            if(i+1<road.nodes().size()) {
                var joint=fixed.roadPoint(road,i,1);
                var before=joint.sub(fixed.roadPoint(road,i,.999));
                var after=fixed.roadPoint(road,i+1,.001).sub(joint);
                assertTrue(before.dot(after)/(before.length()*after.length())>.999,"smooth tangent at "+i);
            }
        }
        assertTrue(diagonal,"minimum-cost graph must include diagonal motion");
        var template=new PlanetPlan.Template(List.of(new PlanetPlan.Voxel(0,0,0,Blocks.STONE.defaultBlockState())));
        var encoded=new PlanetPlan(512,fixed,List.of(a,b),List.of(template));
        assertArrayEquals(encoded.compressed(),PlanetPlan.decode(encoded.compressed()).compressed());
    }
    @Test void regionalBiomesOccupyConnectedRecipeControlledFractionsInAllThirteenTypes() throws Exception {
        var summary=new com.google.gson.JsonObject();
        for(String type:TYPES) {
            var fixed=new TerrainPlanner(42,512,recipe(type),type.equals("flesh")?70:0).generate();
            var grid=new Grid(512);var neighbours=grid.neighbours();
            int[] counts=new int[fixed.recipe().biomes().size()],largest=new int[counts.length];
            for(int node=0;node<fixed.nodeCount();node++)counts[fixed.biome(node)]++;
            var visited=new BitSet(fixed.nodeCount());
            for(int node=0;node<fixed.nodeCount();node++)if(!visited.get(node)) {
                int biome=fixed.biome(node),count=0;var queue=new ArrayDeque<Integer>();queue.add(node);visited.set(node);
                while(!queue.isEmpty()) {int at=queue.remove();count++;
                    for(int next:neighbours[at])if(!visited.get(next) && fixed.biome(next)==biome) {visited.set(next);queue.add(next);}}
                largest[biome]=Math.max(largest[biome],count);
            }
            var values=new com.google.gson.JsonObject();
            for(int i=0;i<counts.length;i++) {
                double fraction=counts[i]/(double)fixed.nodeCount();
                assertTrue(fraction>=(type.equals("home")?.005:.03),type+" fraction "+i+" "+fraction);
                if(fixed.recipe().biomes().get(i).parameters().has("region_max")) {
                    assertTrue(fraction>=.24 && fraction<=.4,type+" regional area "+fraction);
                    assertTrue(largest[i]>=counts[i]*.15,type+" contiguous region "+largest[i]+"/"+counts[i]);
                }
                var entry=new com.google.gson.JsonObject();entry.addProperty("share",fraction);entry.addProperty("largest_nodes",largest[i]);
                values.add(fixed.recipe().biomes().get(i).biomeId(),entry);
                if(type.equals("desert") && i==0)assertEquals(.35,fraction,.01);
            }
            summary.add(type,values);
        }
        for(String type:List.of("desert","ice","rocky")) {
            var file=Path.of("src/main/resources/data/starfold/starfold/planet_type/"+type+".json");
            var root=new TerrainRecipe(com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("terrain"));
            var fixed=new TerrainPlanner(42,512,root).generate();int[] counts=new int[root.biomes().size()];
            for(int node=0;node<fixed.nodeCount();node++)counts[fixed.biome(node)]++;
            for(int count:counts)assertTrue(count>fixed.nodeCount()*.2,"root resource must cover every node and retain regional area: "+type);
        }
        Files.createDirectories(Path.of("build/terrain-plan"));
        Files.writeString(Path.of("build/terrain-plan/s3-biome-coverage.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(summary));
    }
    @Test void plannedOverhangsAndUndercutsIntersectTheWallAndProjectOverLowerGround() throws Exception {
        int checked=0;
        for(String type:List.of("home","desert","rocky")) {
            var fixed=new TerrainPlanner(42,512,recipe(type)).generate();
            assertTrue(fixed.volumes().stream().noneMatch(v->v.type().equals("starfold:floating_rock")),type);
            for(var volume:fixed.volumes())if(List.of("starfold:overhang","starfold:undercut").contains(volume.type())) {
                var p=volume.parameters();assertTrue(p.has("wall_x"),type);
                var center=volume.center();var axis=Math.abs(center.y())<.8?Face.TOP.normal:Face.EAST.normal;
                var tangent=center.cross(axis);tangent=tangent.scale(1/tangent.length());
                var along=tangent.scale(p.get("wall_x").getAsDouble()).add(center.cross(tangent).scale(p.get("wall_z").getAsDouble()));
                var back=center.sub(along.scale(volume.radiusU()*.65/(fixed.size()*.5)));back=back.scale(1/back.length());
                var front=center.add(along.scale(volume.radiusU()*.65/(fixed.size()*.5)));front=front.scale(1/front.length());
                var bc=fixed.chart(back);var fc=fixed.chart(front);
                assertTrue(fixed.height(bc.face(),bc.u(),bc.v())>=volume.level()-1,"connected uphill anchor");
                assertTrue(fixed.contains(volume,back,(int)Math.round(volume.level())),"anchor lies inside saved cap");
                assertTrue(fixed.height(fc.face(),fc.u(),fc.v())<volume.level()-volume.radiusY()*.4+1,"exposed outward shelf");
                checked++;
            }
        }
        assertTrue(checked>=6,"wall placements must actually be present: "+checked);
    }
    @Test void textureTexelsAndBicubicClimateDriveTheSameJavaSnowAtSeamsAndEveryType() throws Exception {
        var random=new Random(912);
        for(String type:TYPES) {
            var fixed=new TerrainPlanner(42,512,recipe(type),type.equals("flesh")?70:0).generate();
            var field=fixed.temperatureField();var texels=field.texels();
            assertEquals((512/16+5)*6,field.height());
            for(var face:Face.VALUES)for(int i=0;i<20;i++) {
                double u=i<4?(i%2)*512:random.nextDouble()*512,v=i<4?256:random.nextDouble()*512;
                var up=dev.starfold.worldgen.PlanetTerrain.direction(new dev.starfold.chart.PlanetShape(512),face,u,v);
                var c=fixed.chart(up);double altitude=37;
                double base=gpuSample(texels,field.width(),c.face(),c.u(),c.v(),0);
                double surface=gpuSample(texels,field.width(),c.face(),c.u(),c.v(),1);
                assertEquals(fixed.interpolate(TEMPERATURE,c.face(),c.u(),c.v()),base,2e-4);
                for(double seasonal:new double[]{-30,0,30})for(double density:new double[]{0,1}) {
                    var forcing=new PlanetSeasons.Forcing(new Point(0,1,0),.3,seasonal,density,.4,70);
                    var java=PlanetSeasons.sample(forcing,up,altitude,fixed);
                    double temperature=base+seasonal*up.y()-fixed.recipe().value("climate","lapse_rate")*(altitude-surface);
                    double edge=PlanetSeasons.noise(up),snow=density==0?0:Math.clamp((2-temperature+edge*3)/6,0,1);
                    assertEquals(temperature,java.temperature(),2e-4);assertEquals(snow,java.snow(),4e-5);
                    if(Math.abs(java.snow()-.5)>4e-5)assertEquals(snow>=.5,java.snow()>=.5);
                }
            }
        }
    }
    private static double gpuSample(float[] data,int width,Face face,double u,double v,int channel) {
        float x=(float)(u/16),z=(float)(v/16);int a=(int)Math.floor(x),b=(int)Math.floor(z);float[] rows=new float[4];
        for(int j=0;j<4;j++) {
            int at=((face.ordinal()*width+b+j+1)*width+a+1)*2+channel;
            rows[j]=cubic(data[at],data[at+2],data[at+4],data[at+6],x-a);
        }
        return cubic(rows[0],rows[1],rows[2],rows[3],z-b);
    }
    private static float cubic(float a,float b,float c,float d,float t) {return b+.5f*t*(c-a+t*(2*a-5*b+4*c-d+t*(3*(b-c)+d-a)));}
    @Test void coarseGridErrorIsBoundedAgainstActualColumnsForEveryType() throws Exception {
        try(var csv=Files.newBufferedWriter(Path.of("build/terrain-plan/s3-coarse-errors.csv"))) {
            csv.write("type,level,samples,mean_abs_height,max_abs_height,mean_material_total_variation\n");
            var random=new Random(189);
            for(String type:TYPES) {
                var fixed=new TerrainPlanner(42,512,recipe(type),type.equals("flesh")?70:0).generate();
                var terrain=TerrainPlanS2BTest.terrain(fixed);var function=new SurfaceFunction(terrain);
                for(int level:new int[]{16,32,64}) {
                    double sum=0,max=0,materials=0;int count=6;
                    for(int i=0;i<count;i++) {
                        var face=Face.VALUES[i];int u=random.nextInt(512-level),v=random.nextInt(512-level);
                        var exact=new SurfaceFunction.Aggregate();
                        for(int x=0;x<level;x++)for(int z=0;z<level;z++)exact.add(function.point(face,u+x,v+z));
                        var expected=exact.cell();var actual=function.sample(face,u,v,level);
                        double error=Math.abs(actual.height()-expected.height());sum+=error;max=Math.max(max,error);
                        var shares=new HashMap<net.minecraft.world.level.block.state.BlockState,Double>();
                        for(var m:expected.materials())shares.merge(m.block(),m.share(),Double::sum);
                        for(var m:actual.materials())shares.merge(m.block(),-m.share(),Double::sum);
                        materials+=shares.values().stream().mapToDouble(Math::abs).sum()*.5;
                        assertEquals(1,actual.materials().stream().mapToDouble(SurfaceFunction.Coverage::share).sum(),1e-9);
                    }
                    csv.write(String.format(Locale.ROOT,"%s,%d,%d,%.4f,%.4f,%.4f%n",type,level,count,sum/count,max,materials/count));csv.flush();
                    assertTrue(sum/count<=8,type+" mean error "+sum/count);assertTrue(max<=40,type+" max error "+max);
                    assertTrue(materials/count<=.5,type+" material error "+materials/count);
                }
            }
        }
    }
    @Test void coarseSummariesCoverNarrowStructuresAndLargeVolumesAwayFromTheirCentres() throws Exception {
        var base=TerrainPlanS2BTest.flat(1);
        var volume=TerrainPlanS2BTest.volume("floating_rock",base.position(base.node(Face.TOP,20,16)),210,32,8,24,Operation.FILL);
        var fixed=base.withContent(List.of(),List.of(volume),List.of());
        var bounds=new PlanetPlan.Bounds(160,201,160,207,204,161);
        var voxels=new ArrayList<PlanetPlan.Voxel>();for(int x=0;x<48;x++)for(int z=0;z<2;z++)voxels.add(new PlanetPlan.Voxel(x,0,z,Blocks.GOLD_BLOCK.defaultBlockState()));
        var piece=new PlanetPlan.Piece(0,new BlockPos(160,204,160),Rotation.NONE,Mirror.NONE,bounds,bounds,TerrainAdjustment.NONE);
        var site=new PlanetPlan.Site(1,ResourceLocation.parse("test:narrow_roof"),Face.TOP,160,160,bounds,List.of(piece));
        var plan=new PlanetPlan(512,fixed,List.of(site),List.of(new PlanetPlan.Template(voxels)));
        var terrain=TerrainPlanS2BTest.terrain(fixed);terrain.initializePlan(()->plan);var function=new SurfaceFunction(terrain);
        var summary=terrain.surfaceSummary(function);
        assertSame(summary,terrain.surfaceSummary(new SurfaceFunction(terrain)),"block readers reuse the summary");
        var roof=function.sample(Face.TOP,192,160,16);
        assertTrue(roof.canopyCoverage()>=.12);assertEquals(204,roof.canopyTop(),.01);
        assertTrue(roof.canopyMaterials().stream().anyMatch(m->m.block().is(Blocks.GOLD_BLOCK)));
        var rock=function.sample(Face.TOP,336,256,16);
        assertTrue(rock.height()>190 || rock.canopyTop()>200,"volume bounds reach cells beyond its centre");
        terrain.initializePlan(()->plan);
        assertSame(summary,terrain.surfaceSummary(new SurfaceFunction(terrain)),"the same immutable plan reuses its summary after rebinding");
    }
}
