package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Cell;
import dev.starfold.CubeSurfaceTopology.Face;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NativeObserverReadyTest {
    private static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/dev/starfold/"+name+".java"));}
    @Test void haloPredicateIsCanonicalsHalfOpenBoundaryForEveryShape(){
        for(int size:new int[]{256,1024,2048}){
            var shape=new NativePlanetShape(size);var topology=shape.topology();
            for(double u:new double[]{Math.nextDown(-size),-size,-.5,0,size,Math.nextDown(2.0*size),2.0*size})
                for(double v:new double[]{Math.nextDown(-size),-size,0,Math.nextDown(2.0*size),2.0*size}){
                    var cell=new Cell(Face.TOP,(int)Math.floor(u),0,(int)Math.floor(v));
                    assertEquals(shape.inChart(cell.u(),cell.v()),topology.inHalo(u,v));
                    if(topology.inHalo(u,v))assertDoesNotThrow(()->topology.canonical(cell));
                    else assertThrows(IllegalArgumentException.class,()->topology.canonical(cell));
                }
            for(double invalid:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})assertFalse(topology.inHalo(invalid,0));
        }
    }
    @Test void airStencilCannotCrossTheHaloEvenWhenTheEyeCentreFits(){
        var small=new NativePlanetShape(1024);var big=new NativePlanetShape(2048);var eye=new Point(26.5,-126.38,2040.5);
        assertTrue(small.topology().inHalo(eye.x(),eye.z()));assertFalse(NativeAir.canSample(small,eye));assertTrue(NativeAir.canSample(big,eye));
        assertThrows(IllegalArgumentException.class,()->new NativeAir(small,Face.TOP,eye,GroundAir.DEFAULT,true));
        for(var shape:List.of(small,big))for(var face:Face.values())for(double u:new double[]{-shape.size()+32,0,.5,shape.size(),2*shape.size()-33}){
            var point=new Point(u,50,shape.half());assertTrue(NativeAir.canSample(shape,point));
            assertDoesNotThrow(()->new NativeAir(shape,face,point,GroundAir.DEFAULT,true));
        }
        assertFalse(NativeAir.canSample(small,new Point(Double.NaN,0,0)));
        assertFalse(NativeAir.canSample(small,new Point(0,Double.POSITIVE_INFINITY,0)));
    }
    @Test void readinessOwnsFrameShapeEyeAndCurrentRenderPartial()throws Exception{
        var client=source("NativeGroundClient");
        for(String rule:List.of("chartFrame==null","planet()!=f.planet()","inFrame(b,f)","inFrame(storage(mc.player.getEyePosition(partial)),f)","NativeAir.canSample(shape(),eye())","access.storagePlanet(p.x(),p.z())==f.planet()","access.face(p.x())==f.root()","shape().topology().inHalo"))assertTrue(client.contains(rule),rule);
        var begin=client.substring(client.indexOf("public static void begin"),client.indexOf("public static void end"));
        assertTrue(begin.indexOf("partial=delta")<begin.indexOf("!ready()"));
        assertTrue(client.contains("boolean projected=frame&&ready();"));assertTrue(client.contains("if(!projected)return;"));
        assertTrue(source("CubeSurfaceTopology").contains("if(!inHalo(query.u,query.v))"));
    }
    @Test void hudProbeLightFogAndSkyKeepChartQueriesBehindReadiness()throws Exception{
        var flight=source("NativeGroundFlightClient");
        assertTrue(flight.contains("if(!ready()||!NativeGroundClient.ready()||Minecraft.getInstance().options.hideGui)return"));
        assertTrue(flight.contains("if(!ready()||!NativeGroundClient.ready())return \"flight not ready\""));
        assertTrue(flight.contains("probeAltitude(){return ready()&&NativeGroundClient.ready()?"));
        assertTrue(source("NativeWaypointClient").contains("!NativeGroundClient.ready() || !NativeGroundFlightClient.ready()"));
        var probe=source("DevProbe");assertTrue(probe.contains("case \"solarplan\",\"solarjump\"->{if(!NativeGroundClient.ready())"));
        assertTrue(probe.contains("NativeGroundClient.ready()?NativeGroundClient.face():-1"));
        assertTrue(probe.contains("NativeGroundClient.ready()?NativeGroundRenderer.realCorners(NativeGroundClient.face()):0"));
        assertTrue(source("mixin/NativeGroundLightMixin").contains("&&NativeGroundClient.ready())"));
        assertTrue(source("mixin/NativeGroundFogMixin").contains("||!NativeGroundClient.ready())return"));
        assertTrue(source("mixin/NativeGroundFlightRenderMixin").contains("NativeSpaceView.chartFar(NativeGroundClient.ready()?NativeGroundClient.eye().y():0,NativeGroundClient.view())"));
        assertTrue(source("NativeGroundCelestials").contains("!NativeSpaceClient.active()&&!NativeGroundClient.ready()"));
        assertTrue(source("NativeGroundSunRenderer").contains("!NativeGroundClient.solar()"));
        assertTrue(source("NativePlanetRemoteRenderer").contains("!NativeGroundClient.ready()"));
        assertTrue(source("NativeGroundRenderer").contains("!NativeGroundClient.ready()"));
    }
    @Test void plotGuardRefusesImmutableDestinationAndRetainsSourceProtection()throws Exception{
        var guard=source("mixin/NativeCoreChunkMixin");
        assertTrue(guard.contains("if(state.is(NativeCoreBlock.IMMUTABLE))"));
        assertTrue(guard.contains("container.getPlot(chunk.getPos())!=null){ci.setReturnValue(null);return;}"));
        assertTrue(guard.contains("old.is(NativeCoreBlock.IMMUTABLE)&&old!=state)ci.setReturnValue(null)"));
        assertTrue(guard.indexOf("state.is(NativeCoreBlock.IMMUTABLE)")<guard.indexOf("var old=chunk.getBlockState(pos)"));
    }
}
