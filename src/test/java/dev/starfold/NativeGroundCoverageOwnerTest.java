package dev.starfold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeGroundCoverageOwnerTest {
    private static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/dev/starfold/"+name+".java"));}
    private static MethodNode method(String name,String method)throws Exception{var node=new ClassNode();try(var in=TestArchiveResources.open("/dev/starfold/"+name+".class")){new ClassReader(in).accept(node,0);}return node.methods.stream().filter(m->m.name.equals(method)).findFirst().orElseThrow();}
    @Test void coverageAndInvalidationsAreNotConsumedBeforeOwnershipIsReady()throws Exception{
        for(String name:List.of("refreshCoverage","prepareDetails")){
            var instructions=method("NativeGroundRenderer",name).instructions;MethodInsnNode first=null;
            for(var i:instructions)if(i instanceof MethodInsnNode call){first=call;break;}
            assertNotNull(first);assertEquals("dev/starfold/NativeGroundViewClient",first.owner);assertEquals("coverageReady",first.name);
            var branch=first.getNext();assertEquals(Opcodes.IFNE,branch.getOpcode());assertEquals(Opcodes.RETURN,branch.getNext().getOpcode());
        }
    }
    @Test void sameSizedPlanetsAndSameSessionShapeChangesStillResetTheirCells()throws Exception{
        var renderer=source("NativeGroundRenderer");assertTrue(renderer.contains("planet!=observedPlanet||!observed.equals(shape)){clear();"));
        for(String cleared:List.of("dirty.all()","priorCoverage.clear()","dirtyDetails.clear()","wanted=Set.of()","window=Set.of()","publishedWindow=Set.of()"))assertTrue(renderer.contains(cleared),cleared);
        var client=source("NativeGroundViewClient");assertTrue(client.contains("level==world&&session==id&&boundPlanet==planet&&boundShape.equals(shape))return"));
        assertTrue(client.contains("boundPlanet==NativeGroundClient.planet()&&boundShape.equals(NativeGroundClient.shape())&&coverageRevision>=0"));
        assertTrue(client.contains("heights.shape().equals(boundShape)"));assertTrue(client.contains("session==NativeGroundFlightClient.session()"));
        for(String cleared:List.of("changed.clear()","invalidated.clear()","cache.clear()","windowStamp=Long.MIN_VALUE"))assertTrue(client.contains(cleared),cleared);
        assertTrue(client.contains("new NativeGroundViewCache(()->boundShape,"));assertFalse(client.contains("neighbours(NativeGroundClient.shape(),key)"));
    }
    @Test void theLargePlanetsLastTileHaloCannotBeReinterpretedOnTheSmallPlanet(){
        var big=new NativePlanetShape(2048);var small=new NativePlanetShape(1024);
        var cell=new CubeSurfaceTopology.Cell(CubeSurfaceTopology.Face.TOP,2048,0,1024);
        assertDoesNotThrow(()->big.topology().canonical(cell));assertThrows(IllegalArgumentException.class,()->small.topology().canonical(cell));
    }
    @Test void arrivalPreviewIsSeededAfterBindingAndStillRequestsAuthoritativeCoverage()throws Exception{
        var client=source("NativeGroundViewClient");int bind=client.indexOf("public static void bindSession"),preview=client.indexOf("static void preview");var binding=client.substring(bind,preview);
        assertTrue(binding.indexOf("clear();")<binding.indexOf("boundPlanet=planet;boundShape=shape;"));
        assertTrue(binding.indexOf("boundPlanet=planet;boundShape=shape;")<binding.indexOf("NativePlanetRemoteRenderer.seedOwn(boundPlanet,id)"));
        assertTrue(client.contains("coverageRevision=0;previewed=true"));assertTrue(client.contains("(previewed||!coverageReady())"));
        var remote=source("NativePlanetRemoteRenderer");assertTrue(remote.contains("bodies.get(planet)"));assertTrue(remote.contains("NativeGroundViewClient.preview(session,snapshot)"));
    }
}
