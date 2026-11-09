package dev.starfold.worldgen.plan;

import static dev.starfold.worldgen.plan.terrain.TerrainPlan.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.universe.*;
import dev.starfold.worldgen.*;
import dev.starfold.worldgen.lod.VoxelColumn;
import dev.starfold.worldgen.plan.terrain.*;
import dev.starfold.worldgen.surface.SurfaceFunction;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.*;
import org.junit.jupiter.api.*;

/** Fixed S2 composition contracts; all fixtures are explicitly CPU-only. */
class TerrainPlanS2BTest {
    @BeforeAll static void boot() throws Exception { PlanetPlanFixtures.boot(); }
    @AfterAll static void close() throws Exception { PlanetPlanFixtures.close(); }

    static TerrainPlan flat(double continuity) throws Exception {
        var json = TerrainPlanTest.recipe("home").json();
        json.getAsJsonObject("preferences").addProperty("road_continuity",continuity);
        var recipe = new TerrainRecipe(json);
        int count = new Grid(512).positions.length;
        var fields = new float[6][count];
        Arrays.fill(fields[HEIGHT],160);
        Arrays.fill(fields[TEMPERATURE],20);
        return new TerrainPlan(42,512,recipe,fields,new short[count],new byte[count],List.of(),List.of());
    }
    static JsonObject parameters(String operation) throws Exception {
        var p = TerrainPlanTest.recipe("home").volumes().getFirst().parameters();
        p.addProperty("operation",operation);
        return p;
    }
    static Volume volume(String type, Point center, double level, double u, double y, double v, Operation op) throws Exception {
        return new Volume("starfold:"+type,parameters(op.name().toLowerCase(Locale.ROOT)),center,level,u,y,v,op);
    }
    static PlanetPlan.Site site(long id, Face face, int u, int v, int level, int template, PlanetPlan.Effects effects) {
        var bounds = new PlanetPlan.Bounds(u,level,v,u,level+2,v);
        var piece = new PlanetPlan.Piece(template,new BlockPos(u,level,v),Rotation.NONE,Mirror.NONE,bounds,bounds,TerrainAdjustment.NONE);
        return new PlanetPlan.Site(id,ResourceLocation.parse("test:fixture"),face,u,v,bounds,List.of(piece),effects);
    }
    static PlanetTerrain terrain(TerrainPlan fixed) throws Exception {
        var terrain = TerrainPlanTest.planet("home").terrain();
        terrain.initializeTerrain(fixed);
        return terrain;
    }
    @Test void volumesPreserveFloatingAirGapsAndAllSurfaceQueriesUseTheSameRuns() throws Exception {
        var base = flat(1);
        var center = base.position(base.node(Face.TOP,16,16));
        var floating = volume("floating_rock",center,200,18,7,12,Operation.FILL);
        var fixed = base.withContent(List.of(),List.of(floating),List.of());
        var terrain = terrain(fixed);
        var function = new SurfaceFunction(terrain);
        var blocks = function.blocks(Face.TOP,256,256);
        assertTrue(blocks[180-terrain.floor()].isAir());
        assertFalse(blocks[200-terrain.floor()].isAir());
        var lod = VoxelColumn.visible(function,Face.TOP,256,256);
        assertTrue(lod.state(180).isAir(),"LOD must preserve the gap under floating rock");
        assertFalse(lod.state(200).isAir());
        var point = function.point(Face.TOP,256,256);
        var visible = function.visible(Face.TOP,256,256);
        assertTrue(point.height()>200);
        assertEquals(point.height(),visible.top());
        assertEquals(point.height(),function.groundTop(Face.TOP,256,256));
        assertEquals(blocks[visible.top()-terrain.floor()],visible.block());
        assertEquals(visible.block(),point.materials().getFirst().block());
        var decoded = PlanetPlan.decode(terrain.plan().compressed()).terrain();
        assertEquals(fixed.volumes(),decoded.volumes());
        assertArrayEquals(blocks,new SurfaceFunction(terrain(decoded)).blocks(Face.TOP,256,256));
    }
    @Test void registeredVolumeShapesDoNotRequireEvaluatorBranchesAndCavesAreReallyConnected() throws Exception {
        TerrainRegistries.VOLUMES.register("test:box",TerrainRegistries.parameters(p -> TerrainRecipe.keys(p)),
            (x,y,z,p) -> Math.abs(x)<=1 && Math.abs(y)<=1 && Math.abs(z)<=1);
        var base=flat(1);
        var custom=new Volume("test:box",new JsonObject(),Face.TOP.normal,190,8,3,8,Operation.FILL);
        var fixed=base.withContent(List.of(),List.of(custom),List.of());
        assertFalse(new SurfaceFunction(terrain(fixed)).blocks(Face.TOP,256,256)[190+64].isAir());
        assertEquals("test:box",PlanetPlan.decode(new PlanetPlan(512,fixed,List.of(),List.of()).compressed()).terrain().volumes().getFirst().type());
        var plan=new TerrainPlanner(42,512,TerrainPlanTest.recipe("lush")).generate();
        var caves=plan.volumes().stream().filter(v -> v.parameters().get("cave_connection").getAsBoolean()).toList();
        assertFalse(caves.isEmpty());
        var terrain=terrain(plan);
        var underground=new PlanetUnderground(terrain);
        for(var mouth:caves) {
            var c=plan.chart(mouth.center());
            int ground=terrain.groundY(c.face(),(int)c.u(),(int)c.v());
            boolean connected=false;
            for(int y=Math.max(terrain.floor()+5,(int)Math.floor(mouth.level()-mouth.radiusY()));y<ground-5;y++)
                if(plan.contains(mouth,mouth.center(),y) && underground.cave(underground.sample(mouth.center(),y),ground,y))connected=true;
            assertTrue(connected,"every planned cave entrance intersects an actual underground cave");
            assertTrue(plan.contains(mouth,mouth.center(),ground),"every planned cave entrance also opens through the surface");
        }
    }
    @Test void orderedStructuresBuryBackfillDamageCutAndOverlapWithoutChangingTemplates() throws Exception {
        var base=flat(1);
        var template=new PlanetPlan.Template(List.of(new PlanetPlan.Voxel(0,0,0,Blocks.CHEST.defaultBlockState()),
            new PlanetPlan.Voxel(0,1,0,Blocks.AIR.defaultBlockState()),new PlanetPlan.Voxel(0,2,0,Blocks.GOLD_BLOCK.defaultBlockState())));
        var buried=site(100,Face.TOP,256,256,166,0,new PlanetPlan.Effects(8,0,List.of()));
        var plan=new PlanetPlan(512,base,List.of(buried),List.of(template));
        var terrain=terrain(base);terrain.initializePlan(()->plan);
        var blocks=new SurfaceFunction(terrain).blocks(Face.TOP,256,256);
        assertEquals(Blocks.CHEST.defaultBlockState(),blocks[158-terrain.floor()]);
        assertFalse(blocks[159-terrain.floor()].isAir(),"buried template AIR is backfilled with ground material");
        assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(),blocks[160-terrain.floor()]);
        assertTrue(blocks[168-terrain.floor()].isAir(),"the structure moved down rather than merely being covered");
        var ruined=site(101,Face.TOP,256,256,166,0,new PlanetPlan.Effects(0,1,List.of()));
        var damaged=new PlanetPlan(512,base,List.of(ruined),List.of(template));
        assertTrue(damaged.column(Face.TOP,256,256).values().stream().allMatch(s->s.isAir()));
        assertEquals(template,damaged.templates().getFirst());
        var carve=volume("undercut",Face.TOP.normal,167,12,8,12,Operation.CARVE);
        var cutTerrain=base.withContent(List.of(),List.of(carve),List.of());
        var cut=site(102,Face.TOP,256,256,166,0,new PlanetPlan.Effects(0,0,List.of(new Ref(Kind.VOLUME,0))));
        assertTrue(new PlanetPlan(512,cutTerrain,List.of(cut),List.of(template)).column(Face.TOP,256,256).values().stream().allMatch(s->s.isAir()));
        var other=new PlanetPlan.Template(List.of(new PlanetPlan.Voxel(0,0,0,Blocks.DIAMOND_BLOCK.defaultBlockState())));
        var later=site(1,Face.TOP,256,256,166,1,PlanetPlan.Effects.NONE);
        var overlap=new PlanetPlan(512,base,List.of(ruined,later),List.of(template,other));
        assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(),overlap.column(Face.TOP,256,256).get(166));
        assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(),PlanetPlan.decode(overlap.compressed()).column(Face.TOP,256,256).get(166));
        assertTrue(overlap.owns(Face.TOP,256,256,166,1));
    }
    static TerrainPlan crossingRoad(double continuity) throws Exception {
        var base=flat(continuity);
        var nodes=new ArrayList<Integer>();
        for(int v=4;v<=28;v++)nodes.add(base.node(Face.TOP,16,v));
        var fields=new float[6][base.nodeCount()];
        for(int f=0;f<6;f++)for(int i=0;i<base.nodeCount();i++)fields[f][i]=base.field(f,i);
        var river=new TerrainPlan.Path(nodes,8,8,159,Operation.CARVE);
        base=new TerrainPlan(42,512,base.recipe(),fields,new short[base.nodeCount()],new byte[base.nodeCount()],List.of(),List.of(river));
        var a=site(10,Face.TOP,128,256,161,0,PlanetPlan.Effects.NONE);
        var b=site(20,Face.TOP,384,256,161,0,PlanetPlan.Effects.NONE);
        return TerrainRoadPlanner.connect(base,List.of(a,b),new ArrayList<>());
    }
    @Test void roadsUseCostedGridRoutesAndSaveBothEndpointsAndBridgeOrBreakDecisions() throws Exception {
        var broken=crossingRoad(0);
        var bridged=crossingRoad(1);
        assertEquals(1,broken.roads().size());
        assertTrue(broken.roads().getFirst().modes().contains(RoadMode.CUT));
        assertTrue(bridged.roads().getFirst().modes().contains(RoadMode.BRIDGE));
        assertFalse(bridged.roads().getFirst().modes().contains(RoadMode.CUT));
        assertEquals(Set.of(10L,20L),bridged.relations().stream().filter(r->r.relation().equals("connects"))
            .map(r->r.target().id()).collect(java.util.stream.Collectors.toSet()));
        for(var fixed:List.of(broken,bridged)) {
            var road=fixed.roads().getFirst();
            for(int i=1;i<road.nodes().size();i++)
                assertTrue(fixed.position(road.nodes().get(i)).sub(fixed.position(road.nodes().get(i-1))).length()*256<=16.01);
            var terrain=terrain(fixed);
            var function=new SurfaceFunction(terrain);
            boolean observed=false;
            for(int u=240;u<272;u++)for(int v=248;v<264;v++) {
                var sample=fixed.road(Face.TOP,u+.5,v+.5,terrain.unit(Face.TOP,u+.5,v+.5));
                if(sample==null || sample.mode()==RoadMode.GROUND)continue;
                observed=true;
                int y=(int)Math.round(sample.height());
                var block=function.blocks(Face.TOP,u,v)[y-terrain.floor()];
                if(sample.mode()==RoadMode.BRIDGE)assertEquals(Blocks.OAK_PLANKS.defaultBlockState(),block);
                else assertNotEquals(Blocks.DIRT_PATH.defaultBlockState(),block);
            }
            assertTrue(observed);
        }
    }
    @Test void visibleBandReachesSurfaceLoweredByRoadThroughRidge() throws Exception {
        // Regression: a road deck far below the ground lowered surfaceTop under the band bottom (NegativeArraySizeException).
        var base=flat(1);
        var fields=new float[6][base.nodeCount()];
        for(int f=0;f<6;f++)for(int i=0;i<base.nodeCount();i++)fields[f][i]=base.field(f,i);
        Arrays.fill(fields[HEIGHT],200);
        var hill=new TerrainPlan(42,512,base.recipe(),fields,new short[base.nodeCount()],new byte[base.nodeCount()],List.of(),List.of());
        var nodes=new ArrayList<Integer>();var levels=new ArrayList<Double>();var modes=new ArrayList<RoadMode>();
        for(int u=8;u<=24;u++) { nodes.add(hill.node(Face.TOP,u,16)); levels.add(160.0); if(u>8)modes.add(RoadMode.GROUND); }
        var terrain=terrain(hill.withContent(List.of(),List.of(),List.of(new TerrainPlan.Road(nodes,levels,modes,3))));
        boolean lowered=false;
        for(int u=192;u<=320;u++)for(int v=248;v<264;v++) {
            int ground=terrain.groundY(Face.TOP,u,v),top=terrain.surfaceTop(Face.TOP,u,v,ground);
            lowered|=top<ground-2;
            var band=terrain.visibleBand(Face.TOP,u,v,ground,ground);
            assertTrue(band.bottom()<=top && band.end()>top,u+" "+v);
        }
        assertTrue(lowered,"fixture must contain a road cut below ground");
    }
    @Test void malformedPathsAndReferencesFailAtTheReadBoundary() throws Exception {
        var base=flat(1);
        int a=base.node(Face.TOP,15,16),b=base.node(Face.TOP,16,16);
        for(var nodes:List.of(List.of(a,a),List.of(a,b,a),List.of(a,base.node(Face.TOP,24,16)))) {
            var bad=new TerrainPlan(42,512,base.recipe(),new float[6][base.nodeCount()],new short[base.nodeCount()],
                new byte[base.nodeCount()],List.of(),List.of(new TerrainPlan.Path(nodes,2,2,150,Operation.CARVE)));
            var payload=new PlanetPlan(512,bad,List.of(),List.of()).compressed();
            assertThrows(IllegalArgumentException.class,()->PlanetPlan.decode(payload));
        }
        int c=base.node(Face.TOP,15,17);
        var cyclic=new TerrainPlan(42,512,base.recipe(),new float[6][base.nodeCount()],new short[base.nodeCount()],new byte[base.nodeCount()],List.of(),
            List.of(new TerrainPlan.Path(List.of(a,b),2,2,150,Operation.CARVE),new TerrainPlan.Path(List.of(b,c),2,2,150,Operation.CARVE),new TerrainPlan.Path(List.of(c,a),2,2,150,Operation.CARVE)));
        assertThrows(IllegalArgumentException.class,()->PlanetPlan.decode(new PlanetPlan(512,cyclic,List.of(),List.of()).compressed()));
        var repeating=base.withContent(List.of(),List.of(),List.of(new Road(List.of(a,b,a),List.of(160.0,160.0,160.0),List.of(RoadMode.GROUND,RoadMode.GROUND),3)));
        assertThrows(IllegalArgumentException.class,()->PlanetPlan.decode(new PlanetPlan(512,repeating,List.of(),List.of()).compressed()));
        var missingEnds=base.withContent(List.of(),List.of(),List.of(new Road(List.of(a,b),List.of(160.0,160.0),List.of(RoadMode.GROUND),3)));
        assertThrows(IllegalArgumentException.class,()->PlanetPlan.decode(new PlanetPlan(512,missingEnds,List.of(),List.of()).compressed()));
        var link=new Relation(new Ref(Kind.ROAD,0),"connects",new Ref(Kind.SITE,999));
        var bad=base.withContent(List.of(link),List.of(),List.of());
        assertThrows(IllegalArgumentException.class,()->PlanetPlan.decode(new PlanetPlan(512,bad,List.of(),List.of()).compressed()));
    }
    @Test void savedRiverCarveAndFillKeepTheirOriginalCompositionOrder() throws Exception {
        var base=flat(1);
        var fields=new float[6][base.nodeCount()];
        for(int f=0;f<6;f++)for(int i=0;i<base.nodeCount();i++)fields[f][i]=base.field(f,i);
        var nodes=List.of(base.node(Face.TOP,16,15),base.node(Face.TOP,16,17));
        var carve=new TerrainPlan.Path(nodes,8,8,159,Operation.CARVE);
        var fill=new TerrainPlan.Path(nodes,8,0,170,Operation.FILL);
        double[] heights=new double[2];int order=0;
        for(var paths:List.of(List.of(carve,fill),List.of(fill,carve))) {
            var fixed=new TerrainPlan(42,512,base.recipe(),fields,new short[base.nodeCount()],new byte[base.nodeCount()],List.of(),paths);
            var decoded=PlanetPlan.decode(new PlanetPlan(512,fixed,List.of(),List.of()).compressed()).terrain();
            assertEquals(paths,decoded.paths());
            heights[order++]=decoded.height(Face.TOP,256,256);
        }
        assertTrue(heights[0]>heights[1]+10,"FILL then CARVE differs from CARVE then FILL");
    }
    @Test void allSixVolumesAndBridgeAndBrokenRoadsMatchActualChunksAcrossASeam() throws Exception {
        var base=flat(1);
        var center=base.position(base.node(Face.TOP,32,16));
        for(String type:List.of("overhang","arch","undercut","cave_mouth","sinkhole","floating_rock")) {
            boolean carve=List.of("undercut","cave_mouth","sinkhole").contains(type);
            double level=type.equals("floating_rock")?200:carve?151:170;
            var shape=volume(type,center,level,20,carve?14:12,12,carve?Operation.CARVE:Operation.FILL);
            var fixed=base.withContent(List.of(),List.of(shape),List.of());
            var plan=new PlanetPlan(512,fixed,List.of(),List.of());
            assertRealSeamChunks(fixed,plan);
            var evaluated=new SurfaceFunction(terrain(fixed)).blocks(Face.TOP,511,256);
            var original=new SurfaceFunction(terrain(base)).blocks(Face.TOP,511,256);
            assertFalse(Arrays.equals(evaluated,original),type+" must affect actual blocks");
        }
        for(double continuity:List.of(0.0,1.0)) {
            base=flat(continuity);
            var nodes=new ArrayList<Integer>();
            for(int v=8;v<=24;v++)nodes.add(base.node(Face.TOP,32,v));
            var fields=new float[6][base.nodeCount()];
            for(int f=0;f<6;f++)for(int i=0;i<base.nodeCount();i++)fields[f][i]=base.field(f,i);
            base=new TerrainPlan(42,512,base.recipe(),fields,new short[base.nodeCount()],new byte[base.nodeCount()],List.of(),
                List.of(new TerrainPlan.Path(nodes,8,8,159,Operation.CARVE)));
            var end=new dev.starfold.geometry.CubeSurfaceTopology(512).canonical(
                new dev.starfold.geometry.CubeSurfaceTopology.Cell(Face.TOP,576,0,256)).cell();
            var a=site(10,Face.TOP,448,256,161,0,PlanetPlan.Effects.NONE);
            var b=site(20,end.face(),end.u(),end.v(),161,0,PlanetPlan.Effects.NONE);
            var fixed=TerrainRoadPlanner.connect(base,List.of(a,b),new ArrayList<>());
            assertTrue(fixed.roads().getFirst().modes().contains(continuity==0?RoadMode.CUT:RoadMode.BRIDGE));
            var template=new PlanetPlan.Template(List.of(new PlanetPlan.Voxel(0,0,0,Blocks.COBBLESTONE.defaultBlockState())));
            var plan=new PlanetPlan(512,fixed,List.of(a,b),List.of(template));
            var decoded=PlanetPlan.decode(plan.compressed());
            assertEquals(fixed.roads(),decoded.terrain().roads());
            assertRealSeamChunks(decoded.terrain(),decoded);
        }
    }
    private static void assertRealSeamChunks(TerrainPlan fixed,PlanetPlan plan) throws Exception {
        var planet=TerrainPlanTest.planet("home");
        planet.terrain().initializeTerrain(fixed);planet.terrain().initializePlan(()->plan);
        var system=new SystemChunkGenerator(dev.starfold.planet.PlanetStorage.LAYOUT,List.of(planet));
        var function=new SurfaceFunction(planet.terrain());
        for(int u:List.of(511,512)) {
            var c=planet.terrain().shape().topology().canonical(
                new dev.starfold.geometry.CubeSurfaceTopology.Cell(Face.TOP,u,0,256)).cell();
            var pos=new net.minecraft.world.level.ChunkPos(c.face().ordinal()*256+c.u()/16,c.v()/16);
            var chunk=new net.minecraft.world.level.chunk.ProtoChunk(pos,net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                net.minecraft.world.level.LevelHeightAccessor.create(-64,384),
                PlanetPlanFixtures.context.registries().registryOrThrow(net.minecraft.core.registries.Registries.BIOME),null);
            SystemGeneration.fill(system,chunk).join();
            for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
                int a=c.u()/16*16+x,b=c.v()/16*16+z;
                var expected=function.blocks(c.face(),a,b);
                var visible=function.visible(c.face(),a,b);
                assertEquals(expected[visible.top()-planet.terrain().floor()],visible.block());
                assertEquals(visible.top(),function.point(c.face(),a,b).height());
                for(int y=planet.terrain().floor();y<PlanetTerrain.MAX_Y;y++)
                    assertEquals(expected[y-planet.terrain().floor()],chunk.getBlockState(
                        new BlockPos(c.face().ordinal()*4096+a,y,b)),c.face()+" "+a+","+b+" y="+y);
            }
        }
    }
    @Test void registeredBoxCornersUseTheSameConservativeBoundsInEverySurfaceQuery() throws Exception {
        TerrainRegistries.VOLUMES.register("test:corner_box",TerrainRegistries.parameters(p->TerrainRecipe.keys(p)),
            (x,y,z,p)->Math.abs(x)<=1 && Math.abs(y)<=1 && Math.abs(z)<=1);
        var base=flat(1);
        var box=new Volume("test:corner_box",new JsonObject(),Face.TOP.normal,210,64,4,64,Operation.FILL);
        var fixed=base.withContent(List.of(),List.of(box),List.of());
        var function=new SurfaceFunction(terrain(fixed));
        var blocks=function.blocks(Face.TOP,320,320);
        assertFalse(blocks[210+64].isAir(),"box corner lies outside either individual horizontal radius");
        assertEquals(function.point(Face.TOP,320,320).height(),function.visible(Face.TOP,320,320).top());
        assertTrue(function.point(Face.TOP,320,320).height()>210);
        assertFalse(VoxelColumn.visible(function,Face.TOP,320,320).state(210).isAir());
        var carved=new Volume("test:corner_box",new JsonObject(),Face.TOP.normal,170,64,4,64,Operation.CARVE);
        var terrain=base.withContent(List.of(),List.of(carved),List.of());
        var planet=TerrainPlanTest.planet("home");planet.terrain().initializeTerrain(terrain);
        var corner=site(22,Face.TOP,320,320,170,0,PlanetPlan.Effects.NONE);
        var effected=new TraditionalPlanetPlanner(planet,PlanetPlanFixtures.context).effects(corner,new ArrayList<>());
        assertTrue(effected.effects().cuts().contains(new Ref(Kind.VOLUME,0)),"structure cuts use the same full shape bound");
    }
    @Test void offMidpointNarrowRiversAndEqualHeightCanyonsAndVoidVolumesStillCutRoads() throws Exception {
        var base=flat(0);
        var fields=new float[6][base.nodeCount()];
        for(int f=0;f<6;f++)for(int i=0;i<base.nodeCount();i++)fields[f][i]=base.field(f,i);
        var riverNodes=new ArrayList<Integer>();
        for(int v=12;v<=20;v++)riverNodes.add(base.node(Face.TOP,24,v));
        TerrainPlan narrow=null;
        for(int seed=0;seed<128 && narrow==null;seed++) {
            var candidate=new TerrainPlan(seed,512,base.recipe(),fields,new short[base.nodeCount()],new byte[base.nodeCount()],
                List.of(),List.of(new TerrainPlan.Path(riverNodes,1,8,159,Operation.CARVE)));
            var shape=new dev.starfold.chart.PlanetShape(512);
            var quarter=PlanetTerrain.direction(shape,Face.TOP,380,256);
            if(!Double.isFinite(candidate.riverLevel(Face.TOP,380,256,quarter)))continue;
            boolean missed=true;
            for(int u:List.of(368,376,384))if(Double.isFinite(candidate.riverLevel(Face.TOP,u,256,
                PlanetTerrain.direction(shape,Face.TOP,u,256))))missed=false;
            if(missed)narrow=candidate;
        }
        assertNotNull(narrow,"fixture crosses at one quarter of the directed edge while all old three samples miss it");
        var a=site(10,Face.TOP,368,256,161,0,PlanetPlan.Effects.NONE);
        var b=site(20,Face.TOP,384,256,161,0,PlanetPlan.Effects.NONE);
        assertTrue(TerrainRoadPlanner.connect(narrow,List.of(a,b),new ArrayList<>()).roads().getFirst().modes().contains(RoadMode.CUT));
        TerrainRegistries.LANDFORMS.register("test:narrow_canyon",TerrainRegistries.parameters(p->TerrainRecipe.keys(p)),
            (r,angle,p)->r<.1?1:0);
        var center=base.position(base.node(Face.TOP,23,16)).add(base.position(base.node(Face.TOP,24,16)));
        center=center.scale(1/center.length());
        var canyon=new Landform("test:narrow_canyon",new JsonObject(),center,16,16,Operation.LOWER);
        var valley=new TerrainPlan(42,512,base.recipe(),fields,new short[base.nodeCount()],new byte[base.nodeCount()],List.of(canyon),List.of());
        assertEquals(valley.height(Face.TOP,368,256),valley.height(Face.TOP,384,256),1e-8);
        assertTrue(TerrainRoadPlanner.connect(valley,List.of(a,b),new ArrayList<>()).roads().getFirst().modes().contains(RoadMode.CUT));
        var hole=volume("sinkhole",center,160,2,5,2,Operation.CARVE);
        var voidTerrain=base.withContent(List.of(),List.of(hole),List.of());
        assertTrue(TerrainRoadPlanner.connect(voidTerrain,List.of(a,b),new ArrayList<>()).roads().getFirst().modes().contains(RoadMode.CUT));
    }
    @Test void burialBackfillReplacesEarlierBlocksAndEachSitesCutsStayInItsOwnOrder() throws Exception {
        var base=flat(1);
        var priorTemplate=new PlanetPlan.Template(List.of(new PlanetPlan.Voxel(0,0,0,Blocks.DIAMOND_BLOCK.defaultBlockState())));
        var buriedTemplate=new PlanetPlan.Template(List.of(new PlanetPlan.Voxel(0,0,0,Blocks.CHEST.defaultBlockState()),
            new PlanetPlan.Voxel(0,1,0,Blocks.AIR.defaultBlockState()),new PlanetPlan.Voxel(0,2,0,Blocks.GOLD_BLOCK.defaultBlockState())));
        var prior=site(1,Face.TOP,256,256,159,0,PlanetPlan.Effects.NONE);
        var later=site(2,Face.TOP,256,256,166,1,new PlanetPlan.Effects(8,0,List.of()));
        var plan=new PlanetPlan(512,base,List.of(prior,later),List.of(priorTemplate,buriedTemplate));
        var terrain=terrain(base);terrain.initializePlan(()->plan);
        var block=new SurfaceFunction(terrain).blocks(Face.TOP,256,256)[159-terrain.floor()];
        assertFalse(block.isAir());assertNotEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(),block,"later bury backfills with terrain material");
        assertFalse(plan.owns(Face.TOP,256,256,159,prior.id()));
        var floating=base.withContent(List.of(),List.of(volume("floating_rock",Face.TOP.normal,200,12,8,12,Operation.FILL)),List.of());
        var capSite=site(5,Face.TOP,256,256,207,1,new PlanetPlan.Effects(8,0,List.of()));
        var capPlan=new PlanetPlan(512,floating,List.of(capSite),List.of(priorTemplate,buriedTemplate));
        var capTerrain=terrain(floating);capTerrain.initializePlan(()->capPlan);
        assertFalse(new SurfaceFunction(capTerrain).blocks(Face.TOP,256,256)[200+64].isAir(),
            "bury backfills the final floating cap as well as the scalar base ground");
        var fields=new float[6][base.nodeCount()];
        for(int f=0;f<6;f++)for(int i=0;i<base.nodeCount();i++)fields[f][i]=base.field(f,i);
        var fixed=new TerrainPlan(42,512,base.recipe(),fields,new short[base.nodeCount()],new byte[base.nodeCount()],List.of(),
            List.of(new TerrainPlan.Path(List.of(base.node(Face.TOP,16,15),base.node(Face.TOP,16,17)),8,8,159,Operation.CARVE)));
        var cut=adaptedSite(3,191,new PlanetPlan.Effects(0,0,List.of(new Ref(Kind.RIVER,0))));
        var uncut=adaptedSite(4,181,PlanetPlan.Effects.NONE);
        assertTrue(new PlanetPlan(512,fixed,List.of(cut),List.of(priorTemplate)).ground(Face.TOP,256,256,160)<=152);
        assertEquals(180,new PlanetPlan(512,fixed,List.of(cut,uncut),List.of(priorTemplate)).ground(Face.TOP,256,256,160),
            "a later unrelated site's adaptation follows the earlier site's cut");
    }
    private static PlanetPlan.Site adaptedSite(long id,int height,PlanetPlan.Effects effects) {
        var bounds=new PlanetPlan.Bounds(256,height,256,256,height+2,256);
        var piece=new PlanetPlan.Piece(0,new BlockPos(256,height,256),Rotation.NONE,Mirror.NONE,bounds,bounds,TerrainAdjustment.BEARD_THIN);
        return new PlanetPlan.Site(id,ResourceLocation.parse("test:adapted"),Face.TOP,256,256,bounds,List.of(piece),effects);
    }
    @Test void placementReadsFinalSurfaceAndPenalizesDetachedFloatingCaps() throws Exception {
        var base=flat(1);
        var fixed=base.withContent(List.of(),List.of(volume("floating_rock",Face.TOP.normal,200,18,7,12,Operation.FILL)),List.of());
        var terrain=terrain(fixed);
        int raw=terrain.groundY(Face.TOP,256,256),surface=terrain.foundationTop(Face.TOP,256,256,raw);
        assertTrue(surface>200);assertEquals(surface,terrain.surfaceTop(Face.TOP,256,256,raw));
        assertTrue(TraditionalPlanetPlanner.supportPenalty(terrain,Face.TOP,256,256,raw,surface)>0);
        assertEquals(0,TraditionalPlanetPlanner.supportPenalty(terrain,Face.TOP,128,128,160,160));
    }
    @Test void orderedVolumesThenLocalWaterAndFinalExposedIceAndSnowComposeConsistently() throws Exception {
        var base=flat(1);var json=base.recipe().json();
        json.getAsJsonObject("water").addProperty("sea_level",190);
        json.getAsJsonObject("water").addProperty("freeze",true);
        var recipe=new TerrainRecipe(json);
        var fields=new float[6][base.nodeCount()];
        for(int f=0;f<6;f++)for(int i=0;i<base.nodeCount();i++)fields[f][i]=base.field(f,i);
        Arrays.fill(fields[WATER_LEVEL],190);
        var water=new byte[base.nodeCount()];Arrays.fill(water,(byte)Water.OCEAN.ordinal());
        var carve=volume("sinkhole",Face.TOP.normal,158,12,10,12,Operation.CARVE);
        var fill=volume("floating_rock",Face.TOP.normal,175,12,10,12,Operation.FILL);
        var above=volume("floating_rock",Face.TOP.normal,200,12,10,12,Operation.FILL);
        var wet=new TerrainPlan(42,512,recipe,fields,new short[base.nodeCount()],water,List.of(),List.of(),List.of(),List.of(carve,fill,above),List.of());
        var wetTerrain=terrain(wet);var wetBlocks=new SurfaceFunction(wetTerrain).blocks(Face.TOP,256,256);
        assertEquals(Blocks.WATER.defaultBlockState(),wetBlocks[158+64],"underwater carve refills with existing local water");
        assertTrue(wetBlocks[175+64].getFluidState().isEmpty());assertFalse(wetBlocks[175+64].isAir());
        assertTrue(wetBlocks[200+64].getFluidState().isEmpty());assertFalse(wetBlocks[200+64].isAir());
        var dry=base.withContent(List.of(),List.of(carve),List.of());
        assertTrue(new SurfaceFunction(terrain(dry)).blocks(Face.TOP,256,256)[158+64].isAir());
        var same=volume("sinkhole",Face.TOP.normal,175,12,10,12,Operation.CARVE);
        for(boolean fillLast:List.of(false,true)) {
            var fixed=wet.withContent(List.of(),fillLast?List.of(same,fill):List.of(fill,same),List.of());
            var state=new SurfaceFunction(terrain(fixed)).blocks(Face.TOP,256,256)[175+64];
            assertEquals(!fillLast,!state.getFluidState().isEmpty());
        }
        Arrays.fill(fields[TEMPERATURE],-20);
        var cold=new TerrainPlan(42,512,recipe,fields,new short[base.nodeCount()],water,List.of(),List.of(),List.of(),List.of(carve),List.of());
        assertEquals(Blocks.ICE.defaultBlockState(),new SurfaceFunction(terrain(cold)).blocks(Face.TOP,256,256)[189+64]);
        var coldRock=cold.withContent(List.of(),List.of(above),List.of());
        var function=new SurfaceFunction(terrain(coldRock));var blocks=function.blocks(Face.TOP,256,256);
        assertEquals(Blocks.SNOW.defaultBlockState(),function.visible(Face.TOP,256,256).block());
        assertEquals(Blocks.WATER.defaultBlockState(),blocks[189+64],"the sheltered water below a floating cap is not the exposed ice surface");
        for(int y=wetTerrain.floor()+1;y<PlanetTerrain.MAX_Y;y++)if(blocks[y+64].is(Blocks.SNOW))assertFalse(blocks[y+63].isAir(),"snow rests on the final cap");
    }
    @Test void everyPreferenceAffectsItsPlanningStage() throws Exception {
        var dense=TerrainPlanTest.recipe("rocky").json();
        dense.getAsJsonObject("preferences").addProperty("density",1);
        var sparse=dense.deepCopy();sparse.getAsJsonObject("preferences").addProperty("density",0);
        var absent=new TerrainPlanner(42,512,new TerrainRecipe(sparse)).generate();
        assertTrue(absent.landforms().isEmpty());assertTrue(absent.volumes().isEmpty());
        var present=new TerrainPlanner(42,512,new TerrainRecipe(dense)).generate();
        assertFalse(present.landforms().isEmpty());assertFalse(present.volumes().isEmpty());
        var seed=PlanetPlanFixtures.planet(416,"plains");
        var json=seed.generation().recipe().json();
        var preferences=json.getAsJsonObject("preferences");
        preferences.addProperty("desolation",0);preferences.addProperty("burial",1);preferences.addProperty("damage",1);
        preferences.addProperty("overlap",0);
        var sets=PlanetPlanFixtures.context.registries().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE_SET);
        var villages=sets.getHolderOrThrow(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.STRUCTURE_SET,ResourceLocation.withDefaultNamespace("villages")));
        var archives=new ArrayList<TraditionalPlanetPlanner.Archive>();
        for(int overlap=0;overlap<=1;overlap++) {
            preferences.addProperty("overlap",overlap);
            var generation=seed.generation().withRecipe(new TerrainRecipe(json));
            var planet=new PlanetChunkGenerator(seed.settings(),42,PlanetTerrain.VERSION,Optional.empty(),Optional.of(generation));
            archives.add(new TraditionalPlanetPlanner(planet,PlanetPlanFixtures.context).generate(List.of(villages)));
        }
        for(var archive:archives) {
            assertFalse(archive.plan().sites().isEmpty());
            for(var site:archive.plan().sites()) { assertTrue(site.effects().burial()>0);assertTrue(site.effects().damage()>=.5); }
        }
        assertNotEquals(archives.get(0).plan().sites().stream().map(s->List.of(s.face(),s.u(),s.v())).toList(),
            archives.get(1).plan().sites().stream().map(s->List.of(s.face(),s.u(),s.v())).toList(),"overlap preference changes candidate arrangements");
        preferences.addProperty("desolation",1);
        var generation=seed.generation().withRecipe(new TerrainRecipe(json));
        var empty=new PlanetChunkGenerator(seed.settings(),42,PlanetTerrain.VERSION,Optional.empty(),Optional.of(generation));
        assertTrue(new TraditionalPlanetPlanner(empty,PlanetPlanFixtures.context).generate(List.of(villages)).plan().sites().isEmpty());
        assertTrue(crossingRoad(0).roads().getFirst().modes().contains(RoadMode.CUT));
        assertTrue(crossingRoad(1).roads().getFirst().modes().contains(RoadMode.BRIDGE));
    }
    @Test void buriedDamagedVanillaRecipesStillValidateAndReplayIntoActualCrossSeamChunks() throws Exception {
        var planet=PlanetPlanFixtures.planet(512,"desert");
        var pieces=PlanetPlanTest.generate(planet,"desert_pyramid",3);
        var box=BoundingBox.encapsulatingBoxes(pieces.stream().map(StructurePiece::getBoundingBox).toList()).orElseThrow();
        int dx=1-(box.minX()+box.maxX())/2,dz=256-(box.minZ()+box.maxZ())/2;
        for(var piece:pieces)piece.move(dx,0,dz);
        var original=TraditionalPlanetPlanner.record(planet,PlanetPlanFixtures.context,3,ResourceLocation.withDefaultNamespace("desert_pyramid"),Face.NORTH,1,256,pieces);
        var site=original.plan().sites().getFirst();
        var buried=new PlanetPlan.Site(site.id(),site.structure(),site.face(),site.u(),site.v(),site.bounds(),site.pieces(),new PlanetPlan.Effects(8,.55,List.of()));
        var plan=new PlanetPlan(512,original.plan().terrain(),List.of(buried),original.plan().templates());
        assertEquals(original.plan().templates(),plan.templates());
        PlanetPlanTest.replayChunks(planet,new TraditionalPlanetPlanner.Archive(plan,original.recipes(),original.skipped()),"s2-buried-damaged-pyramid");
    }
}
