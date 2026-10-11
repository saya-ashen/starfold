package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativePlanetShapeTest {
    @org.junit.jupiter.api.BeforeAll static void boot(){NativePlanetEcologyTest.boot();}
    @Test void theSizeInUseReproducesTheFixedGeometryExactly(){
        var shape=new NativePlanetShape(GroundSpace.SIZE);assertEquals(GroundAir.HALF,shape.half());assertEquals(GroundOrbit.RADIUS,shape.radius());assertEquals(128,shape.chunks());
        assertEquals(shape,NativePlanetShape.STANDARD);assertEquals(shape,NativePlanetGeneration.defaults(5).shape());
        for(var f:Face.values())for(double u:new double[]{0,.5,300.25,1024,2047.5,2048,-40.5,2100.5})for(double v:new double[]{0,17.5,1024,2048,-3.5,2060.5}){
            if((u<0||u>2048)&&(v<0||v>2048))continue;
            assertEquals(GroundOrbit.direction(f,u,v),shape.direction(f,u,v),f+" "+u+" "+v);
            var here=NativeGroundTopology.canonical(f,new Point(u,12,v));var there=shape.canonical(f,new Point(u,12,v));assertEquals(here.pose(),there.pose());assertEquals(here.turns(),there.turns());}
        for(var p:new Point[]{new Point(900,700,-650),new Point(-1,-1400,3),new Point(5000,5200,4900)}){assertEquals(NativeGroundTopology.locate(p),shape.locate(p));assertEquals(NativeGroundTopology.planet(shape.locate(p)).sub(shape.position(shape.locate(p))).length(),0,1e-9);}
    }
    @Test void aSmallerPlanetIsTheSameShapeScaled(){
        var big=new NativePlanetShape(2048);var small=new NativePlanetShape(1024);assertEquals(big.radius()/2,small.radius());assertEquals(64,small.chunks());assertTrue(small.owned(1023.9,0));assertFalse(small.owned(1024,0));
        for(var f:Face.values())for(double u:new double[]{0,100.5,512,1024,-20.5,1040.5})for(double v:new double[]{0,333.5,1024}){
            assertEquals(0,big.direction(f,2*u,2*v).sub(small.direction(f,u,v)).length(),1e-12,f+" "+u+" "+v);}
        for(var f:Face.values())for(double u:new double[]{.5,511.5,1023.5})for(double v:new double[]{.5,700.5,1023.5})for(double h:new double[]{-60,0,90}){
            var pose=new NativeGroundTopology.Pose(f,new Point(u,h,v));var back=small.locate(small.position(pose));assertEquals(f,back.face());assertEquals(0,back.point().sub(pose.point()).length(),1e-9);assertEquals(small.radius()+h,small.position(pose).length(),1e-9);}
        // Beyond an edge is the neighbouring face of this planet, not a cell 2048 blocks away.
        var over=small.canonical(Face.TOP,new Point(1030.5,0,200.5)).pose();assertNotEquals(Face.TOP,over.face());assertTrue(small.owned(over.point().x(),over.point().z()));
        assertEquals(Face.TOP,big.canonical(Face.TOP,new Point(1030.5,0,200.5)).pose().face());
    }
    @Test void onlySizesTheStorageLayoutCanHoldAreAccepted(){
        for(int bad:new int[]{0,255,1000,2049,4096})assertThrows(IllegalArgumentException.class,()->new NativePlanetShape(bad),""+bad);
        for(int good:new int[]{256,1024,2048})assertEquals(good,new NativePlanetShape(good).size());
    }
    @Test void aSmallerPlanetsTerrainIsItsOwnClosedSurfaceWithFeaturesOfTheSameSizeInBlocks(){
        var policy=new NativePlanetGeneration("rocky","rocky_craters",1,1.2,.04,.09,1024);var small=new NativePlanetTerrain(20261002,5,policy);var big=new NativePlanetTerrain(20261002,5,new NativePlanetGeneration("rocky","rocky_craters",1,1.2,.04,.09));
        assertEquals(1024,small.shape().size());assertEquals(2048,big.shape().size());
        // Across an edge the neighbouring face continues the same ground: the column just beyond is the neighbour's own.
        for(var f:Face.values())for(int v=8;v<1024;v+=97){var cell=small.shape().topology().canonical(new CubeSurfaceTopology.Cell(f,1024,0,v)).cell();assertNotEquals(f,cell.face());
            assertEquals(small.groundY(cell.face(),cell.u(),cell.v()),small.column(f,1024,v).ground());assertTrue(Math.abs(small.groundY(f,1023,v)-small.column(f,1024,v).ground())<=6,f+" "+v);}
        // All eight vertices carry the anomaly field at the planet's own corners.
        for(var f:Face.values())for(int[] at:new int[][]{{20,20},{1003,20},{20,1003},{1003,1003}})assertSame(NativeAnomaly.GROUND_BLOCK,small.surfaceState(f,at[0],at[1]).getBlock());
        assertNotSame(NativeAnomaly.GROUND_BLOCK,small.surfaceState(Face.TOP,512,512).getBlock());assertNotSame(NativeAnomaly.GROUND_BLOCK,big.surfaceState(Face.TOP,1003,1003).getBlock());
        // Slopes away from the vertices stay as gentle as on the standard planet: relief is not squeezed into half the width.
        int steep=0,total=0;for(var f:Face.values())for(int u=120;u<900;u+=37)for(int v=120;v<900;v+=41){total++;if(Math.abs(small.groundY(f,u,v)-small.groundY(f,u+1,v))>2)steep++;}
        assertTrue(steep*50<total,steep+"/"+total);
        assertEquals(policy,NativePlanetGeneration.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,NativePlanetGeneration.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,policy).getOrThrow()).getOrThrow());
        assertThrows(IllegalArgumentException.class,()->new NativePlanetGeneration("rocky","rocky",1,1,.035,.085,1000));
    }
    @Test void storageAddressesEachPlanetByItsOwnSize(){
        try{NativePlanetShape.assign(1,new NativePlanetShape(1024));int b=NativePlanetStorage.offset(1);
            // A keeps every chunk of its 128-wide faces; B owns only the 64-wide corner of each face's storage, under unchanged keys.
            assertTrue(NativePlanetStorage.key(127,127)>=0);assertTrue(NativePlanetStorage.key(b/16+63,63)>=0);assertEquals(-1,NativePlanetStorage.key(b/16+64,10));assertEquals(-1,NativePlanetStorage.key(b/16+10,64));
            long key=NativePlanetStorage.key(b/16+256*3+5,7);assertEquals(NativePlanetStorage.CHUNKS_PER_PLANET+5+128L*(7+128L*3),key);assertEquals(new net.minecraft.world.level.ChunkPos(b/16+256*3+5,7),NativePlanetStorage.chunk(key));
            // One block beyond B's edge is its neighbouring face, 1024 blocks across; on A the same chart position is still the face itself.
            var over=NativePlanetStorage.address(new net.minecraft.core.BlockPos(b+1024,140,200));assertEquals(1,over.owner().planet());assertNotEquals(Face.TOP,over.owner().cell().face());assertTrue(NativePlanetShape.of(1).topology().owned(over.owner().cell()));
            var same=NativePlanetStorage.address(new net.minecraft.core.BlockPos(1024,140,200));assertEquals(Face.TOP,same.owner().cell().face());assertEquals(0,same.turns());
            // Every position of B's chart halo resolves to an owner that maps back into B's own storage.
            for(int face=0;face<6;face++)for(int u:new int[]{-1024,-1,0,1023,1024,2047})for(int v:new int[]{-1024,-1,0,500,1023,1024,2047}){
                if((u<0||u>=1024)&&(v<0||v>=1024))continue;var q=new net.minecraft.core.BlockPos(b+face*4096+u,130,v);assertTrue(NativePlanetAccess.SHARED.inChart(q),q.toString());
                var owner=NativePlanetStorage.address(q).owner();var block=owner.block();assertEquals(1,NativePlanetStorage.planet(block));assertTrue(NativePlanetStorage.key(block.getX()>>4,block.getZ()>>4)>=NativePlanetStorage.CHUNKS_PER_PLANET);}
            assertFalse(NativePlanetAccess.SHARED.inChart(new net.minecraft.core.BlockPos(b+2048,130,10)));assertTrue(NativePlanetAccess.SHARED.inChart(new net.minecraft.core.BlockPos(2048,130,10)));
            assertThrows(IllegalArgumentException.class,()->new NativePlanetStorage.Owner(1,new CubeSurfaceTopology.Cell(Face.TOP,1024,0,5)));
            // A neighbouring face is drawn one planet-width away in the observer's chart.
            var image=NativePlanetAccess.SHARED.image(b+10.5,10.5,new Point(over.owner().block().getX()+.5,140,over.owner().block().getZ()+.5));assertNotNull(image);assertEquals(b+1024.5,image.x(),1e-9);assertEquals(200.5,image.z(),1e-9);
        }finally{NativePlanetShape.reset();}
        assertEquals(NativePlanetShape.STANDARD,NativePlanetShape.of(1));
    }
    @Test void theStandardChartIsTheAcceptedOne(){
        for(var root:Face.values()){var mine=new NativeChart(NativePlanetShape.STANDARD,root);var accepted=new GroundChart(root);assertEquals(accepted.tiles().size(),mine.tiles().size());
            for(int i=0;i<5;i++){var a=accepted.tiles().get(i);var m=mine.tiles().get(i);assertEquals(a.face(),m.face());assertEquals(a.turn(),m.turn());assertEquals(a.x(),m.x());assertEquals(a.z(),m.z());var p=new Point(300.25,7,1900.5);assertEquals(a.point(p),m.point(p));assertEquals(a.local(a.point(p)),m.local(m.point(p)));}}
    }
    @Test void farViewOfASmallerPlanetCoversOnlyItsOwnTilesAndPatches(){
        var small=new NativePlanetShape(1024);int existing=0;for(int p=0;p<NativeGroundViewPolicy.PATCH_COUNT;p++)if(NativeGroundViewPolicy.exists(small,p)){existing++;assertTrue(NativeGroundViewPolicy.x(p)<1024&&NativeGroundViewPolicy.z(p)<1024);}
        assertEquals(6*16,existing);assertTrue(NativeGroundViewPolicy.exists(NativePlanetShape.STANDARD,NativeGroundViewPolicy.PATCH_COUNT-1));
        var dirty=new NativeGroundViewPolicy.DirtyPatches(()->small);assertEquals(96,dirty.count());dirty.tile(100+128L*5);assertEquals(96,dirty.count());for(int i=0;i<96;i++)assertTrue(NativeGroundViewPolicy.exists(small,dirty.poll()));assertEquals(-1,dirty.poll());
        // Standing at an edge, the working set continues onto the neighbouring face; every tile is one the planet has.
        var pose=new NativeGroundTopology.Pose(Face.TOP,new Point(1016.5,10,500.5));var tiles=NativeGroundStreaming.footprint(small,pose,4);assertEquals(81,tiles.size());boolean crossed=false;
        for(long key:tiles){int face=(int)(key/16384),u=(int)(key%128),v=(int)(key/128%128);assertTrue(u<64&&v<64,""+key);crossed|=face!=Face.TOP.ordinal();for(long n:NativeGroundStreaming.neighbours(small,key)){assertTrue(n>=0&&n%128<64&&n/128%128<64);assertTrue(java.util.Arrays.stream(NativeGroundStreaming.neighbours(small,n)).anyMatch(k->k==key));}}
        assertTrue(crossed);assertTrue(NativeGroundViewPolicy.nearSeam(small,pose));assertFalse(NativeGroundViewPolicy.nearSeam(NativePlanetShape.STANDARD,pose));
        // The ownership atlas puts the halo ring right beyond the planet's last tile.
        var surface=new NativeGroundViewCache.Surface(1,new int[256],new int[256]);long edge=NativeGroundOwnership.key(small,0,1024+8,8);assertNotEquals(0,edge/16384);
        var atlas=NativeGroundOwnership.atlas(small,new NativeGroundOwnership.Snapshot(java.util.Map.of(edge,surface),new java.util.BitSet()));assertEquals(127,atlas[64+1+NativeGroundOwnership.SIDE*(0+1)]&255);assertEquals(0,atlas[65+1+NativeGroundOwnership.SIDE*(0+1)]);
        // The far mesh stands on the smaller sphere and closes at its own edges and vertices.
        NativeGroundExteriorMesh.Heights flat=new NativeGroundExteriorMesh.Heights(){public double node(Face f,int x,int z){return 5;}public Double edited(Face f,int x,int z){return null;}};
        for(var f:Face.values()){assertEquals(small.radius()+5,NativeGroundExteriorMesh.node(small,flat,f,1024,1024).length(),1e-9);assertEquals(small.radius()+5,NativeGroundExteriorMesh.centre(small,flat,f,1023,0).length(),.2);assertEquals(2,NativeGroundExteriorMesh.anomalyBeams(small,f,768,768,128).size());assertEquals(0,NativeGroundExteriorMesh.anomalyBeams(small,f,256,256,128).size());}
        var seam=NativeGroundExteriorMesh.node(small,flat,Face.TOP,1024,300);var other=small.canonical(Face.TOP,new Point(1024.5,0,300.5)).pose();boolean met=false;for(int u:new int[]{0,1024})for(int v=0;v<=1024;v++)for(boolean swap:new boolean[]{false,true})if(!met&&NativeGroundExteriorMesh.node(small,flat,other.face(),swap?v:u,swap?u:v).sub(seam).length()<1e-9)met=true;
        assertTrue(met,"the neighbouring face has the same edge vertex");
    }
    @Test void theSolidCornerClosureStandsOnEachPlanetsOwnVertices(){
        var mask=GroundSpace.DEFAULT_MASK;var small=new NativePlanetShape(1024);
        for(var f:Face.values())for(int u:new int[]{0,15,16,1007,1008,1023,1024,2032,2047})for(int v:new int[]{0,15,16,1008,1023,2047})for(int h:new int[]{-9,-8,0,127,128}){var cell=new CubeSurfaceTopology.Cell(f,u,h,v);
            assertEquals(GroundSpace.mask(mask,cell),NativeAnomaly.mask(NativePlanetShape.STANDARD,mask,cell),cell.toString());assertEquals(GroundSpace.column(mask,cell),NativeAnomaly.column(NativePlanetShape.STANDARD,mask,cell));
            boolean corner=u<1024&&v<1024&&Math.min(u,1023-u)<16&&Math.min(v,1023-v)<16;assertEquals(corner,NativeAnomaly.column(small,mask,cell),cell.toString());assertEquals(corner&&h>=-8&&h<128,NativeAnomaly.mask(small,mask,cell));}
        for(int in:new int[]{0,15,-1,-16,1023,1008,1024,1039})assertTrue(NativeAnomaly.closed(small,in,16),""+in);for(int out:new int[]{16,-17,1007,1040,2040})assertFalse(NativeAnomaly.closed(small,out,16),""+out);
    }
}
