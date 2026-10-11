package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Replayed residency/admission failures, not a substitute for Minecraft/GPU acceptance. */
class NativeCanonicalStreamingTest {
    @BeforeAll static void boot(){NativePlanetEcologyTest.boot();}
    @Test void everyDirectedSeamRetainsBothWindowsThroughTwoHundredReversals(){
        for(var face:Face.values())for(int[] uv:new int[][]{{0,1024},{2047,1024},{1024,0},{1024,2047}}){
            var before=new NativeGroundTopology.Pose(face,new Point(uv[0]+.5,20,uv[1]+.5));
            var move=new Point(uv[0]==0?-1:uv[0]==2047?1:0,0,uv[1]==0?-1:uv[1]==2047?1:0);
            var after=NativeGroundTopology.canonical(face,before.point().add(move)).pose();
            var stream=new NativeGroundStreaming();stream.update(before,8);var first=stream.resident();assertEquals(289,first.size());
            stream.update(after,8);assertTrue(stream.resident().containsAll(first));var warm=stream.resident();
            for(int i=0;i<200;i++){stream.update(i%2==0?before:after,8);assertEquals(warm,stream.resident());assertTrue(stream.resident().containsAll(stream.required()));assertTrue(stream.resident().size()<=441);}
            assertEquals(warm,NativeGroundStreaming.admit(warm,Map.of(),NativeGroundViewPolicy.DETAIL_BYTES));
        }
    }
    @Test void budgetPressureAlsoKeepsTheSameMeshesAcrossRepeatedSeamCrossings(){
        for(var face:Face.values())for(int[] uv:new int[][]{{0,1024},{2047,1024},{1024,0},{1024,2047}}){
            var before=new NativeGroundTopology.Pose(face,new Point(uv[0]+.5,20,uv[1]+.5));var move=new Point(uv[0]==0?-1:uv[0]==2047?1:0,0,uv[1]==0?-1:uv[1]==2047?1:0);var after=NativeGroundTopology.canonical(face,before.point().add(move)).pose();
            var stream=new NativeGroundStreaming();var costs=new HashMap<Long,Long>();Set<Long> selected=Set.of(),warm=null;
            for(int i=0;i<200;i++){stream.update(i%2==0?before:after,8);for(long key:stream.resident())costs.put(key,2L*1024*1024);selected=NativeGroundStreaming.admit(stream.resident(),costs,NativeGroundViewPolicy.DETAIL_BYTES,selected);if(i==2)warm=selected;if(i>2)assertEquals(warm,selected);assertEquals(48,selected.size());}
        }
    }
    @Test void newerIdenticalSnapshotsDoNotDirtyGeometryButChangedLightDoes(){
        var invalidations=new ArrayList<Long>();var cache=new NativeGroundViewCache(invalidations::add);var values=new int[NativeGroundChunkSnapshot.VALUES];Arrays.fill(values,Block.getId(Blocks.AIR.defaultBlockState()));
        var first=NativeGroundChunkSnapshot.fromStates(0,0,0,1,values);cache.accept(first);assertEquals(1,invalidations.size());
        assertTrue(cache.accept(new NativeGroundChunkSnapshot(0,0,0,2,first.palette(),first.packed(),first.lights())));assertEquals(2,cache.version(0));assertEquals(1,invalidations.size());
        var light=first.lights();light[0]=0;cache.accept(new NativeGroundChunkSnapshot(0,0,0,3,first.palette(),first.packed(),light));assertEquals(2,invalidations.size());
    }
    @Test void walkingAwayReleasesOnlyTheOuterRetentionBandAndTeleportIsBounded(){
        var stream=new NativeGroundStreaming();stream.update(new NativeGroundTopology.Pose(Face.TOP,new Point(1024,20,1024)),8);var old=stream.resident();
        stream.update(new NativeGroundTopology.Pose(Face.TOP,new Point(1025,20,1025)),8);assertSame(old,stream.resident());
        for(int u=1040;u<1700;u+=16){var pose=new NativeGroundTopology.Pose(Face.TOP,new Point(u,20,1024));stream.update(pose,8);assertTrue(NativeGroundStreaming.footprint(pose,10).containsAll(stream.resident()));assertTrue(stream.resident().size()<=441);}
        stream.update(new NativeGroundTopology.Pose(Face.BOTTOM,new Point(500,20,500)),8);assertEquals(289,stream.resident().size());assertTrue(Collections.disjoint(old,stream.resident()));
        stream.update(new NativeGroundTopology.Pose(Face.BOTTOM,new Point(500,257,500)),8);assertTrue(stream.resident().isEmpty());
    }
    @Test void canonicalOwnersDoNotChangeWhenThePlayersCurrentFaceChanges(){
        var surface=NativeGroundOwnershipTest.surface(1,127,Blocks.STONE);
        var images=Map.of(1L,surface,16385L,surface);var selection=new NativeGroundOwnership.Snapshot(images,new BitSet());
        var original=NativeGroundOwnership.atlas(selection);
        for(int face=0;face<6;face++){assertEquals(NativeGroundOwnership.Owner.DETAIL,selection.owner(1));assertEquals(NativeGroundOwnership.Owner.DETAIL,selection.owner(16385));assertArrayEquals(original,NativeGroundOwnership.atlas(selection));}
    }
    @Test void measuredOverBudgetWorkConvergesInsteadOfEvictingAndRebuildingForever(){
        var order=NativeGroundStreaming.footprint(new NativeGroundTopology.Pose(Face.TOP,new Point(1024,20,1024)),8);
        var measured=new HashMap<Long,Long>();var meshes=new HashSet<Long>();var builds=new HashMap<Long,Integer>();long budget=NativeGroundViewPolicy.DETAIL_BYTES;
        for(int frame=0;frame<2000;frame++){
            var admitted=NativeGroundStreaming.admit(order,measured,budget);meshes.retainAll(admitted);
            var missing=admitted.stream().filter(k->!meshes.contains(k)).findFirst();if(missing.isPresent()){
                long key=missing.get();builds.merge(key,1,Integer::sum);measured.put(key,2L*1024*1024);
                if(NativeGroundStreaming.admit(order,measured,budget).contains(key))meshes.add(key);
            }
            assertTrue(meshes.stream().mapToLong(measured::get).sum()<=budget);
        }
        assertEquals(48,meshes.size());assertTrue(builds.size()<=49);assertTrue(builds.values().stream().allMatch(n->n==1));
        measured.put(order.iterator().next(),NativeGroundStreaming.MAX_MESH_BYTES+1);assertFalse(NativeGroundStreaming.admit(order,measured,budget).contains(order.iterator().next()));
    }
    @Test void pinnedSnapshotsOutliveColdLruReadsAndHardCountIsStillEnforced(){
        var cache=new NativeGroundViewCache(k->{});cache.retain(Set.of(0L));
        var states=new int[NativeGroundChunkSnapshot.VALUES];Arrays.fill(states,Block.getId(Blocks.AIR.defaultBlockState()));
        var base=NativeGroundChunkSnapshot.fromStates(0,0,0,1,states);
        cache.accept(base);
        for(int k=1;k<=530;k++)cache.accept(new NativeGroundChunkSnapshot(0,k%128,k/128,1,base.palette(),base.packed()));
        assertTrue(cache.detailed(0));assertEquals(512,cache.detailCount());assertTrue(cache.detailBytes()<=NativeGroundViewCache.BYTE_LIMIT);assertFalse(cache.detailed(1));
        cache.clear();assertEquals(0,cache.detailBytes());
    }
    @Test void largerWorkingSetEventuallyFillsAndRefreshesWithoutFarEditPollution(){
        var ordered=NativeGroundStreaming.footprint(new NativeGroundTopology.Pose(Face.TOP,new Point(.5,20,1024)),8);var resident=new HashSet<Long>();
        for(int i=0;i<60;i++){var keys=NativeGroundStreaming.request(ordered,i,resident::contains);assertTrue(keys.size()<=NativeGroundStreaming.REQUEST_TILES);assertEquals(keys.size(),new HashSet<>(keys).size());assertTrue(ordered.containsAll(keys));resident.addAll(keys);}
        assertEquals(ordered,resident);var refreshed=new HashSet<Long>();for(int i=0;i<ordered.size();i++)refreshed.addAll(NativeGroundStreaming.request(ordered,i,k->true));assertEquals(ordered,refreshed);
    }
    @Test void enclosedVoxelOptimizationPreservesCaveWallsAndChunkBoundaryFaces(){
        var values=new int[NativeGroundChunkSnapshot.VALUES];Arrays.fill(values,Block.getId(Blocks.STONE.defaultBlockState()));
        values[8+16*(8+16*64)]=Block.getId(Blocks.AIR.defaultBlockState());
        var image=NativeGroundChunkSnapshot.fromStates(0,0,0,1,values);
        assertTrue(image.enclosed(3,0,3));assertFalse(image.enclosed(8,0,8));assertFalse(image.enclosed(7,0,8));assertFalse(image.enclosed(8,1,8));assertFalse(image.enclosed(8,-1,8));assertFalse(image.enclosed(0,0,8));assertFalse(image.enclosed(15,0,8));assertFalse(image.enclosed(8,-64,8));
    }
    @Test void runtimeUsesOneGroundAdapterAndCanonicalLoadTickets()throws Exception{
        var root=Path.of("src/main/java/dev/starfold");var renderer=Files.readString(root.resolve("NativeGroundRenderer.java"));var tickets=Files.readString(root.resolve("NativeGroundTickets.java"));var client=Files.readString(root.resolve("NativeGroundViewClient.java"));
        assertTrue(renderer.contains("NativeGroundOwnership.atlas(NativeGroundClient.shape(),next,window)"));assertFalse(renderer.contains("NativeGroundOwnership.atlas(next,nativeFace)"));assertFalse(renderer.contains("nativeVolumeReady"));assertFalse(renderer.contains("ownership.rebase"));
        assertTrue(renderer.contains("NativeGroundStreaming.admit"));assertTrue(renderer.contains("commitDetails(retained)"));assertTrue(client.contains("cache.retain(streaming.resident())"));assertTrue(tickets.contains("addRegionTicket(VIEW,access.chunk(key),0,key)"));assertTrue(tickets.contains("loaded.addAll(NativeImageStream.owners(level))"));
    }
}
