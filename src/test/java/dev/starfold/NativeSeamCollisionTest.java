package dev.starfold;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** No ClientLevel/GPU: exercises the same collision adapter with explicit residency and snapshots. */
class NativeSeamCollisionTest {
    @BeforeAll static void boot(){NativePlanetEcologyTest.boot();}

    @Test void receivedAirAcrossEveryEdgeIsPassableEvenOutsideVanillaChunkWindow(){
        var access=NativePlanetAccess.SHARED;
        for(int planet=0;planet<2;planet++)for(var face:CubeSurfaceTopology.Face.values())
            for(int[] uv:new int[][]{{-1,1024},{2048,1024},{1024,-1},{1024,2048}}){
                var source=new NativePlanetAccessTest.BlocksView();
                var query=new BlockPos(NativePlanetStorage.offset(planet)+face.ordinal()*4096+uv[0],150,uv[1]);
                var expected=access.address(query).owner();
                var view=NativeGroundTerrain.view(source,access,GroundSpace.DEFAULT_MASK,p->false,p->{
                    assertTrue(access.inChart(p));
                    assertEquals(planetOf(expected),planetOf(p));
                    return Blocks.AIR.defaultBlockState();
                });
                assertTrue(view.getBlockState(query).isAir());
                assertFalse(NativeGroundTerrain.overlaps(view,CollisionContext.empty(),new AABB(query).deflate(.05)));
                assertTrue(source.reads.isEmpty(),"Missing native chunks must not be queried/generated");
            }
    }
    private int planetOf(BlockPos pos){return NativePlanetStorage.planet(pos);}

    @Test void unknownStillBlocksAndFullSnapshotArrivalRemovesOnlyTheFalseWall(){
        var source=new NativePlanetAccessTest.BlocksView();
        var access=NativePlanetAccess.SHARED;
        var query=new BlockPos(32768+2048,150,1024);
        var cache=new NativeGroundViewCache(k->{});
        var view=NativeGroundTerrain.view(source,access,GroundSpace.DEFAULT_MASK,p->false,
            p->cache.canonical(NativePlanetStorage.local(1,p)));
        assertTrue(NativeGroundTerrain.overlaps(view,CollisionContext.empty(),new AABB(query).deflate(.05)));
        var owner=NativePlanetStorage.local(1,access.address(query).owner());
        var cell=NativeGroundTopology.cell(owner);
        var values=new int[NativeGroundChunkSnapshot.VALUES];
        Arrays.fill(values,Block.getId(Blocks.AIR.defaultBlockState()));
        cache.accept(NativeGroundChunkSnapshot.fromStates(cell.face().ordinal(),cell.u()/16,cell.v()/16,1,values));
        assertTrue(view.getBlockState(query).isAir());
        assertFalse(NativeGroundTerrain.overlaps(view,CollisionContext.empty(),new AABB(query).deflate(.05)));
        cache.clear();
        assertSame(Blocks.BEDROCK.defaultBlockState(),view.getBlockState(query));
    }

    @Test void synchronizedStairsKeepTheirOrientedShapeRatherThanBecomingAirOrFullCubes(){
        var access=NativePlanetAccess.SHARED;
        for(int planet=0;planet<2;planet++)for(var face:CubeSurfaceTopology.Face.values()){
            var source=new NativePlanetAccessTest.BlocksView();
            var query=new BlockPos(NativePlanetStorage.offset(planet)+face.ordinal()*4096+2048,150,1024);
            var address=access.address(query);
            var stair=Blocks.OAK_STAIRS.defaultBlockState();
            var view=NativeGroundTerrain.view(source,access,GroundSpace.DEFAULT_MASK,p->false,
                p->p.equals(address.owner())?address.write(stair):Blocks.AIR.defaultBlockState());
            assertSame(stair,view.getBlockState(query));
            double volume=view.getBlockState(query).getCollisionShape(view,query).toAabbs().stream()
                .mapToDouble(b->b.getXsize()*b.getYsize()*b.getZsize()).sum();
            assertEquals(.75,volume,1e-12);
        }
    }

    @Test void residentBlocksOverrideOldSnapshotAndMaskStillWins(){
        var source=new NativePlanetAccessTest.BlocksView();
        var query=new BlockPos(32768+1024,150,1024);
        source.blocks.put(query,Blocks.STONE.defaultBlockState());
        var view=NativeGroundTerrain.view(source,NativePlanetAccess.SHARED,GroundSpace.DEFAULT_MASK,
            p->true,p->{fail("Resident chunks take precedence");return null;});
        assertSame(Blocks.STONE.defaultBlockState(),view.getBlockState(query));
        var masked=NativeGroundTerrain.view(source,NativePlanetAccess.SHARED,GroundSpace.DEFAULT_MASK,
            p->{fail("Mask must precede residency");return false;},p->Blocks.AIR.defaultBlockState());
        assertSame(Blocks.BEDROCK.defaultBlockState(),masked.getBlockState(new BlockPos(32769,128,1)));
    }

    @Test void collisionReadDoesNotInventMissingColumnsFromTheGenerator()throws Exception{
        var source=Files.readString(Path.of("src/main/java/dev/starfold/NativeGroundViewClient.java"));
        int start=source.indexOf("static net.minecraft.world.level.block.state.BlockState collisionState(");
        int end=source.indexOf("private static final Map<Long,Map<",start);
        assertTrue(start>=0&&end>start);
        var method=source.substring(start,end);
        assertTrue(method.contains("cache.canonical(owner)"));
        assertTrue(method.contains("if(state==null)return null"));
        assertTrue(method.contains("deltas.get(owner)"));
        assertFalse(method.contains("generated("));
        assertTrue(source.contains("world==level&&samePlanet(owner)?collisionState(NativeGroundClient.local(owner)):null"));
    }
}
