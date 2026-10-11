package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Geometry/ownership/GLSL contracts; no live GPU acceptance or FPS assertion. */
class NativeGroundSurfaceMeshTest {
    final NativePlanetHeightCache cache=new NativePlanetHeightCache(new NativePlanetTerrain(NativePlanetTerrain.TEST_SEED));
    static double area(List<Point> p){double a=0;for(int i=0;i<p.size();i++){var x=p.get(i);var y=p.get((i+1)%p.size());a+=x.x()*y.z()-y.x()*x.z();}return Math.abs(a)*.5;}
    @Test void clippedLandAndWaterPartitionCrossingTrianglesWithoutCoplanarOverlap(){
        for(var triangle:List.of(List.of(new Point(0,-2,0),new Point(0,2,16),new Point(16,1,16)),List.of(new Point(0,0,0),new Point(0,-2,16),new Point(16,2,16)))){
            var land=NativeGroundSurfaceMesh.clip(triangle,0,false);var water=NativeGroundSurfaceMesh.clip(triangle,0,true);
            assertEquals(area(triangle),area(land)+area(water),1e-10);
            assertTrue(land.stream().allMatch(p->p.y()>=0));assertTrue(water.stream().allMatch(p->p.y()<=0));
            var cut=land.stream().filter(p->p.y()==0).toList();assertTrue(water.containsAll(cut));
        }
    }
    @Test void surfaceTilesCoverExactlyTheir16BlockFootprintIncludingTheCoast(){
        int coast=0,wet=0,dry=0;
        for(var f:Face.values())for(int x=0;x<2048;x+=64)for(int z=0;z<2048;z+=64){
            var polygons=NativeGroundSurfaceMesh.tile(cache,f,x,z);assertEquals(256,polygons.stream().mapToDouble(p->area(p.points())).sum(),1e-7);
            boolean water=polygons.stream().anyMatch(NativeGroundSurfaceMesh.Polygon::water),land=polygons.stream().anyMatch(p->!p.water());
            if(water&&land)coast++;else if(water)wet++;else dry++;
            for(var polygon:polygons)for(var p:polygon.points()){assertTrue(p.x()>=x-1e-9&&p.x()<=x+16+1e-9);assertTrue(p.z()>=z-1e-9&&p.z()<=z+16+1e-9);if(polygon.water())assertEquals(0,p.y());else assertTrue(p.y()>=0);}
        }assertTrue(coast>0);assertTrue(wet>0);assertTrue(dry>0);
    }
    @Test void sharedTileBordersUseIdenticalHeightsAndShorelineIntersections(){
        for(var f:Face.values())for(int x=0;x<2032;x+=64)for(int z=0;z<2048;z+=64){
            var a=NativeGroundSurfaceMesh.tile(cache,f,x,z);var b=NativeGroundSurfaceMesh.tile(cache,f,x+16,z);
            var left=new TreeSet<String>();var right=new TreeSet<String>();
            for(var p:a)for(var v:p.points())if(Math.abs(v.x()-x-16)<1e-8)left.add(Math.round(v.y()*1e7)+":"+Math.round(v.z()*1e7));
            for(var p:b)for(var v:p.points())if(Math.abs(v.x()-x-16)<1e-8)right.add(Math.round(v.y()*1e7)+":"+Math.round(v.z()*1e7));
            assertEquals(left,right);
        }
    }
    @Test void all24DirectedFaceSeamsHaveTheSameDerivedBoundaryVertices(){
        int edges=0;
        for(var f:Face.values())for(int edge=0;edge<4;edge++){
            int x=edge==0?0:edge==1?2032:1120,z=edge==2?0:edge==3?2032:1120;
            double u=edge==0?0:edge==1?2048:x+8,v=edge==2?0:edge==3?2048:z+8;
            var cube=f.normal.scale(1024).add(f.u.scale(u-1024)).add(f.v.scale(v-1024));
            var g=Arrays.stream(Face.values()).filter(face->face!=f&&Math.abs(cube.dot(face.normal)-1024)<1e-8).findFirst().orElseThrow();
            int a=Math.clamp((int)Math.floor((1024+cube.dot(g.u))/16)*16,0,2032),b=Math.clamp((int)Math.floor((1024+cube.dot(g.v))/16)*16,0,2032);
            assertEquals(seam(f,g,x,z),seam(g,f,a,b));edges++;
        }assertEquals(24,edges);
    }
    Set<String> seam(Face face,Face neighbor,int x,int z){var out=new TreeSet<String>();for(var polygon:NativeGroundSurfaceMesh.tile(cache,face,x,z))for(var p:polygon.points()){
        var cube=face.normal.scale(1024).add(face.u.scale(p.x()-1024)).add(face.v.scale(p.z()-1024));if(Math.abs(cube.dot(neighbor.normal)-1024)>1e-8)continue;
        var world=NativePlanetTerrain.direction(face,p.x(),p.z()).scale(GroundOrbit.RADIUS+p.y());out.add(Math.round(world.x()*1e7)+":"+Math.round(world.y()*1e7)+":"+Math.round(world.z()*1e7));
    }return out;}
    @Test void aDetailEvictionRestoresCoverInTheSameSelectionWithoutRebuildingAHole(){
        var nativeReady=new BitSet();nativeReady.set(4);
        var withDetail=NativeGroundSurfaceMesh.exclusions(nativeReady,List.of(9L));assertTrue(withDetail.get(9));assertTrue(withDetail.get(4));
        var after=NativeGroundSurfaceMesh.exclusions(nativeReady,List.of());assertFalse(after.get(9));assertTrue(after.get(4));assertEquals(1,nativeReady.cardinality());
        assertThrows(IllegalArgumentException.class,()->NativeGroundSurfaceMesh.exclusions(nativeReady,List.of(-1L)));
    }
    @Test void dataLoadedDoesNotMeanAllSurfaceSectionsAreCompiledAndVisible(){
        var sections=new BitSet();sections.set((127+64)>>4);
        assertTrue(NativeGroundSurfaceMesh.surfaceSectionsReady(new int[]{127,-65},sections));
        assertFalse(NativeGroundSurfaceMesh.surfaceSectionsReady(new int[]{127,145},sections));
        sections.set((145+64)>>4);assertTrue(NativeGroundSurfaceMesh.surfaceSectionsReady(new int[]{127,145},sections));
        assertFalse(NativeGroundSurfaceMesh.surfaceSectionsReady(new int[]{320},sections));
    }
    @Test void takeoffAndLandingKeepNativeThroughoutTheWholeCurvatureBlend(){
        for(double y:new double[]{0,128,135.9,160,164.8,256,512,1023.99})assertFalse(NativeGroundSurfaceMesh.replaceNative(y,true));
        assertFalse(NativeGroundSurfaceMesh.replaceNative(1351.8,false));
        assertTrue(NativeGroundSurfaceMesh.replaceNative(1024,true));assertTrue(NativeGroundSurfaceMesh.replaceNative(1351.8,true));
    }
    @Test void shaderDoesNotTurnAnOverheadSurfaceIntoSkyFogJustBecauseCameraIsHigh()throws Exception{
        var s=Files.readString(Path.of("src/main/java/dev/starfold/NativeGroundShaderSource.java"));
        assertTrue(s.contains("length(point.xz-CPNativeEye.xz)*(1.0-smoothstep(128.0,160.0,CPNativeEye.y))"));
        assertFalse(s.contains("vertexDistance = length(view.xyz)"));
        assertTrue(s.contains("texelFetch(CPNativeCoverage,tile+ivec2(1,1+int(CPNativeFace)*130),0).r"));assertFalse(s.contains("CPNativeDerived<0.5&&owner<0.75)discard"));
    }
    @Test void rendererHasNoLargeQuadStageOrBakedDetailHolesAndRestoresCoverageSampler()throws Exception{
        var s=Files.readString(Path.of("src/main/java/dev/starfold/NativeGroundRenderer.java"));
        assertFalse(s.contains("BaseCoverJob"));assertFalse(s.contains("if(wanted.contains(key)&&detail.containsKey(key))"));
        assertTrue(s.contains("patchesComplete()"));assertTrue(s.contains("staging[patch]=mesh;stagedEpoch++;if(old!=null)old.close()"));assertTrue(s.contains("NativeGroundPipeline.publish(dirty.count(),coverJob!=null,patches,staging)"));
        assertTrue(s.contains("GL11.glBindTexture(GL11.GL_TEXTURE_2D,boundCoverage)"));assertTrue(s.contains("ARBSamplerObjects.glBindSampler(3,coverageSampler)"));
        assertFalse(s.contains("CompiledSection.UNCOMPILED"));assertTrue(s.contains("NativeGroundViewClient.window()"));assertEquals(16,NativeGroundSurfaceMesh.GRID);
        var client=Files.readString(Path.of("src/main/java/dev/starfold/NativeGroundClient.java"));
        assertTrue(client.contains("return frame&&replaceNative"));
        assertTrue(client.contains("frame=true;saveCoverageBinding();replaceNative=NativeGroundSurfaceMesh.replaceNative"));
        assertTrue(s.contains("return NativeGroundClient.replacesNative()"));
    }
}
