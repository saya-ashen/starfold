package dev.starfold;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class NativeAnomalyTest {
    private static final Path SOURCE=Path.of("src/main/java/dev/starfold");
    @org.junit.jupiter.api.BeforeAll static void boot(){NativePlanetEcologyTest.boot();}
    @Test void litSeamsRepeatAndCloseTheTopOfTheDefaultClosure(){
        int lit=0;for(int row=-8;row<GroundSpace.DEFAULT_MASK.height();row++)if(NativeAnomaly.band(row))lit++;
        assertEquals(9,lit);
        assertTrue(NativeAnomaly.band(GroundSpace.DEFAULT_MASK.height()-1));assertFalse(NativeAnomaly.band(0));assertTrue(NativeAnomaly.band(-1));
    }
    @Test void spireNarrowsFromInsideTheClosureFootprintToItsTip(){assertTrue(NativeAnomaly.BEAM_WIDTH>0&&NativeAnomaly.BEAM_WIDTH<GroundSpace.DEFAULT_MASK.core());assertEquals(NativeAnomaly.BEAM_WIDTH,NativeAnomaly.beamWidth(0));assertEquals(0,NativeAnomaly.beamWidth(NativeAnomaly.BEAM_HEIGHT));double prior=NativeAnomaly.BEAM_WIDTH;for(int h=0;h<=NativeAnomaly.BEAM_HEIGHT;h+=NativeAnomaly.QUAD){assertTrue(NativeAnomaly.beamWidth(h)<=prior);prior=NativeAnomaly.beamWidth(h);}}
    @Test void orbitalViewsDrawEachFacesQuarterOfTheSameSpireSelfLit()throws Exception{
        int foot=GroundSpace.DEFAULT_MASK.height(),total=0;for(var f:CubeSurfaceTopology.Face.values())for(int x0=0;x0<2048;x0+=256)for(int z0=0;z0<2048;z0+=256){var beams=NativeGroundExteriorMesh.anomalyBeams(f,x0,z0,foot);total+=beams.size();
            for(var t:beams){assertEquals(GroundOrbit.RADIUS+foot+NativeAnomaly.BEAM_HEIGHT,t[2].length(),1e-6);assertEquals(0,NativeAnomaly.distance(t[2].scale(1/t[2].length())),1e-3);assertEquals(GroundOrbit.RADIUS+foot,t[0].length(),1e-6);assertTrue(NativeAnomaly.distance(t[0].scale(1/t[0].length()))<=GroundSpace.DEFAULT_MASK.core()*1.5);}}
        assertEquals(6*4*2,total);
        assertTrue(Files.readString(Path.of("src/main/resources/assets/starfold/shaders/core/native_ground_exterior.fsh")).contains("if(surfaceColor.a<0.5)light=1.0;"));
        for(String file:new String[]{"NativeGroundExterior.java","NativePlanetRemoteRenderer.java"}){var source=Files.readString(SOURCE.resolve(file));assertTrue(source.contains("NativeGroundExteriorMesh.anomalyBeams(shape,Face.values()[face],x0,z0,mask.height())"),file);assertTrue(source.contains("NativeAnomaly.GLOW_TINT&255,0)"),file);}
    }
    @Test void fieldIsOneCircleOnTheSphereSharedByTheThreeFaces(){
        for(var f:CubeSurfaceTopology.Face.values())for(int[] corner:new int[][]{{0,0},{2048,0},{0,2048},{2048,2048}}){int su=corner[0]==0?1:-1,sv=corner[1]==0?1:-1;
            assertEquals(0,NativeAnomaly.distance(NativePlanetTerrain.direction(f,corner[0],corner[1])),1e-3);
            assertEquals(16,NativeAnomaly.distance(NativePlanetTerrain.direction(f,corner[0]+su*16,corner[1])),.2);assertEquals(16,NativeAnomaly.distance(NativePlanetTerrain.direction(f,corner[0],corner[1]+sv*16)),.2);
            assertTrue(NativeAnomaly.distance(NativePlanetTerrain.direction(f,corner[0]+su*16,corner[1]+sv*16))<NativeAnomaly.FIELD);}
        assertNull(NativeAnomaly.surface(NativeAnomaly.distance(NativePlanetTerrain.direction(CubeSurfaceTopology.Face.TOP,1024,1024))));
        assertSame(NativeAnomaly.ring(),NativeAnomaly.surface(16.5));assertSame(NativeAnomaly.ground(),NativeAnomaly.surface(20.5));assertNull(NativeAnomaly.surface(NativeAnomaly.FIELD));
        assertEquals(NativeAnomaly.PLATEAU,NativeAnomaly.elevation(-20,NativeAnomaly.PLATEAU_INNER));assertEquals(-20,NativeAnomaly.elevation(-20,NativeAnomaly.PLATEAU_OUTER));
    }
    @Test void everyGeneratorLevelsTheFieldLaysItsGroundAndGrowsNothingOnIt(){
        for(int version:new int[]{NativePlanetTerrain.MINING_VERSION,NativePlanetTerrain.ECOLOGY_VERSION,NativePlanetTerrain.ROCK_VERSION}){var t=new NativePlanetTerrain(NativePlanetTerrain.TEST_SEED,version);
            for(var f:CubeSurfaceTopology.Face.values())for(int u=17;u<34;u+=4)for(int v=17;v<34;v+=4){var col=t.column(f,u,v);int ground=col.ground();
                assertTrue(Math.abs(ground-127-NativeAnomaly.PLATEAU)<=NativeAnomaly.WARP+1,""+ground);assertTrue(ground>=NativePlanetTerrain.SEA_LEVEL);var top=col.state(ground);assertTrue(top==NativeAnomaly.ground()||top==NativeAnomaly.ring());assertSame(top,t.surfaceState(f,u,v));
                assertSame(NativeAnomaly.ground(),col.state(ground-NativeAnomaly.DEPTH));assertTrue(col.state(ground+1).isAir());assertEquals(ground,t.surfaceY(f,u,v));}
            var far=t.column(CubeSurfaceTopology.Face.TOP,1024,1024);assertNotSame(NativeAnomaly.ground(),far.state(far.ground()));}
        var eco=new NativePlanetTerrain(NativePlanetTerrain.TEST_SEED,NativePlanetTerrain.ECOLOGY_VERSION);for(int u=0;u<40;u++)for(int v=0;v<40;v++)assertNull(eco.ecology().tree(CubeSurfaceTopology.Face.TOP,u,v));
    }
    @Test void groundIsWrungAroundTheVertexHarderTheNearerItLies(){
        assertEquals(0,NativeAnomaly.twist(NativeAnomaly.PLATEAU_OUTER));assertEquals(0,NativeAnomaly.warp(NativeAnomaly.PLATEAU_OUTER));assertEquals(NativeAnomaly.TWIST,NativeAnomaly.twist(0));assertEquals(NativeAnomaly.WARP,NativeAnomaly.warp(0));
        for(int d=1;d<=NativeAnomaly.PLATEAU_OUTER;d++){assertTrue(NativeAnomaly.twist(d-1)>NativeAnomaly.twist(d));assertTrue(NativeAnomaly.warp(d-1)>NativeAnomaly.warp(d));}
        assertTrue(NativeAnomaly.PLATEAU-NativeAnomaly.warp(GroundSpace.DEFAULT_MASK.core())>1,"the field stays above the sea");
        for(var f:CubeSurfaceTopology.Face.values())for(int[] at:new int[][]{{30,9},{2000,60},{70,2040},{2030,2001}}){var unit=NativePlanetTerrain.direction(f,at[0],at[1]);double d=NativeAnomaly.distance(unit);var turned=NativeAnomaly.twisted(unit,d);
            assertEquals(1,turned.length(),1e-12);assertEquals(d,NativeAnomaly.distance(turned),1e-6);assertTrue(turned.sub(unit).length()>1e-3);}
        var rim=NativePlanetTerrain.direction(CubeSurfaceTopology.Face.TOP,120,0);assertEquals(0,NativeAnomaly.twisted(rim,NativeAnomaly.distance(rim)).sub(rim).length(),1e-12);
        // Relief: rough near the closure.
        var t=new NativePlanetTerrain(NativePlanetTerrain.TEST_SEED,NativePlanetTerrain.MINING_VERSION);int low=999,high=-999;for(int u=0;u<40;u++)for(int v=0;v<40;v++){int y=t.groundY(CubeSurfaceTopology.Face.TOP,u,v);low=Math.min(low,y);high=Math.max(high,y);}
        assertTrue(high-low>=5,low+".."+high);
    }
    @Test void enteringTheZoneWarnsOnceAndKeepsThePlayerNauseous()throws Exception{
        var zone=Files.readString(SOURCE.resolve("NativeAnomalyZone.java"));assertTrue(zone.contains("MobEffects.CONFUSION"));assertTrue(zone.contains("if(inside.add(player.getUUID()))"));assertTrue(zone.contains("distance(level,access,player)<NativeAnomaly.ZONE"));
        assertTrue(Files.readString(SOURCE.resolve("NativeGroundServer.java")).contains("NativeAnomalyZone.tick(level);"));
        for(String lang:new String[]{"zh_cn","en_us"}){var text=Files.readString(Path.of("src/main/resources/assets/starfold/lang/"+lang+".json"));for(String key:new String[]{"anomaly.starfold.enter","anomaly.starfold.enter.detail","anomaly.starfold.leave"})assertTrue(text.contains("\""+key+"\""),lang+" "+key);}
    }
    @Test void materialsAreTheModsOwnBlocksWithTheirOwnTextures(){
        for(var block:new net.minecraft.world.level.block.Block[]{NativeAnomaly.SHELL_BLOCK,NativeAnomaly.GLOW_BLOCK,NativeAnomaly.GROUND_BLOCK,NativeAnomaly.RING_BLOCK}){var id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);assertEquals("starfold",id.getNamespace());
            for(String file:new String[]{"blockstates/"+id.getPath()+".json","models/block/"+id.getPath()+".json","models/item/"+id.getPath()+".json","textures/block/"+id.getPath()+".png"})assertTrue(Files.exists(Path.of("src/main/resources/assets/starfold/"+file)),file);}
        assertSame(NativeAnomaly.SHELL_BLOCK,NativeAnomaly.shell().getBlock());assertSame(NativeAnomaly.GLOW_BLOCK,NativeAnomaly.glow().getBlock());assertSame(NativeAnomaly.GROUND_BLOCK,NativeAnomaly.ground().getBlock());assertSame(NativeAnomaly.RING_BLOCK,NativeAnomaly.ring().getBlock());
        assertEquals(15,NativeAnomaly.glow().getLightEmission());assertTrue(NativeAnomaly.ring().getLightEmission()>0);
    }
    @Test void closureIsSolidOnBothSidesOfEveryEdgeAtAVertexAndNowhereElse()throws Exception{
        for(int in:new int[]{0,15,-1,-16,2047,2032,2048,2063})assertTrue(NativeAnomaly.closed(in,16),""+in);for(int out:new int[]{16,-17,2031,2064,1024})assertFalse(NativeAnomaly.closed(out,16),""+out);assertFalse(NativeAnomaly.closed(0,0));
        var client=Files.readString(SOURCE.resolve("NativeGroundClient.java"));assertTrue(client.contains("NativeAnomaly.closed(NativePlanetShape.of(f.planet()),storage.getX()-(int)o.x(),config.core())&&NativeAnomaly.closed(NativePlanetShape.of(f.planet()),storage.getZ()-(int)o.z(),config.core())"));assertTrue(client.contains("access().origin(f.planet(),f.root())"));
        assertTrue(Files.readString(SOURCE.resolve("NativeGroundRenderer.java")).contains("drawResidual(LocalRender c){if(NativeGroundFlightClient.controlled()||mask!=null&&mask.core()>0)return;"));
    }
    @Test void displayPathsTakeTheLookFromOneReplaceableDefinition()throws Exception{
        var renderer=Files.readString(SOURCE.resolve("NativeGroundRenderer.java"));var mask=renderer.substring(renderer.indexOf("private static Mesh buildMask("),renderer.indexOf("private static void render(LocalRender c,int face,Mesh mesh,RenderType layer){"));
        assertTrue(mask.contains("NativeAnomaly.shell()"));assertTrue(mask.contains("next-row<NativeAnomaly.QUAD"));assertTrue(renderer.contains("masks[face]=buildMask(face,false);masks[face+6]=buildMask(face,true);"));assertTrue(renderer.contains("if(!orbitalChart&&face==NativeGroundClient.face())render(c,face,masks[face+6],layer);"));assertTrue(renderer.contains("mesh!=masks[face]&&mesh!=masks[face+6]&&mesh!=stitches[face]"));assertTrue(mask.contains("above+=NativeAnomaly.QUAD"));assertTrue(NativeAnomaly.QUAD<=4);assertTrue(mask.contains("NativeAnomaly.glow()"));assertFalse(mask.contains("Blocks."));
        for(String file:new String[]{"NativeGroundExterior.java","NativePlanetRemoteRenderer.java"})assertTrue(Files.readString(SOURCE.resolve(file)).contains("if(masked(x,z))state=NativeAnomaly.shell();"),file);
    }
}
