package dev.starfold.worldgen.lod;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.*;
import dev.starfold.worldgen.surface.SurfaceFunction;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VoxelFarTest {
    @BeforeAll static void boot() { PlanetTerrainTest.registry(); }
    static VoxelColumn column() {
        return new VoxelColumn(List.of(new VoxelColumn.Run(-63,128,Blocks.STONE.defaultBlockState()),
                new VoxelColumn.Run(160,168,Blocks.OAK_LOG.defaultBlockState()),
                new VoxelColumn.Run(168,176,Blocks.OAK_LEAVES.defaultBlockState()),
                new VoxelColumn.Run(240,242,Blocks.GOLD_BLOCK.defaultBlockState())));
    }
    @Test void recursiveParentsKeepGapsAndExposedMaterialsWithoutExpandingHeight() {
        var c=column();
        for(int step=2;step<=64;step*=2) {
            c=VoxelColumn.reduce(List.of(c,c,c,c),step);
            assertEquals(-63,c.runs().getFirst().bottom());
            assertEquals(242,c.runs().getLast().top());
            if(step<=32) assertTrue(c.state(220).isAir(),"roof/ground gap at "+step);
            assertTrue(c.runs().stream().anyMatch(r->r.block().is(Blocks.GOLD_BLOCK)));
            assertEquals(c,VoxelColumn.unpack(c.packed(),0,c.runs().size()));
        }
    }
    @Test void startupAndRecursiveBandsNeverInventHeightAboveGroundOrCrown() {
        var ground = new VoxelColumn(List.of(new VoxelColumn.Run(-63,129,Blocks.GRASS_BLOCK.defaultBlockState())));
        var tree = new VoxelColumn(List.of(new VoxelColumn.Run(-63,129,Blocks.GRASS_BLOCK.defaultBlockState()),
                new VoxelColumn.Run(133,141,Blocks.OAK_LEAVES.defaultBlockState())));
        for (int step = 4; step <= 64; step *= 2) {
            // Cold cache: the representative column is reduced directly to the requested band.
            assertEquals(ground,VoxelColumn.reduce(List.of(ground),step));
            var startup = VoxelColumn.reduce(List.of(tree),step);
            assertEquals(141,startup.runs().getLast().top());
            assertTrue(startup.state(141).isAir());
        }
        for (int step = 2; step <= 64; step *= 2) {
            tree = VoxelColumn.reduce(List.of(ground,ground,ground,tree),step);
            assertEquals(141,tree.runs().getLast().top());
            var current = tree;
            var mesh = VoxelMesher.boxes(0,0,step,step,step,(x,z)->current);
            assertTrue(mesh.stream().flatMap(q->q.points().stream()).allMatch(p->p.y()<=141));
        }
    }
    @Test void isolatedCrownWinsOverBuriedGroundAndNoRunIsAnAverageColour() {
        var ground=new VoxelColumn(List.of(new VoxelColumn.Run(-64,128,Blocks.STONE.defaultBlockState())));
        var tree=column();
        var parent=VoxelColumn.reduce(List.of(ground,ground,ground,tree),4);
        assertTrue(parent.state(170).is(Blocks.OAK_LEAVES));
        assertTrue(parent.state(200).isAir());
        var mesh=VoxelMesher.boxes(0,0,8,8,4,(x,z)->parent);
        assertTrue(mesh.stream().anyMatch(q->q.axis()==2 && q.material().is(Blocks.GOLD_BLOCK)));
    }
    @Test void equalHeightMinorityFlecksCannotRepaintAWholeMipByRegistryOrder() {
        var a = Blocks.STONE.defaultBlockState();
        var b = Blocks.GLOWSTONE.defaultBlockState();
        var fleck = net.minecraft.world.level.block.Block.getId(a) < net.minecraft.world.level.block.Block.getId(b) ? a : b;
        var ground = fleck == a ? b : a;
        var plain = new VoxelColumn(List.of(new VoxelColumn.Run(-64, 128, ground)));
        var spot = new VoxelColumn(List.of(new VoxelColumn.Run(-64, 127, ground),
                new VoxelColumn.Run(127, 128, fleck)));
        for (int step = 2; step <= 64; step *= 2) {
            for (int position = 0; position < 4; position++) {
                var children = new ArrayList<>(List.of(plain, plain, plain, plain));
                children.set(position, spot);
                var parent = VoxelColumn.reduce(children, step);
                assertSame(ground, parent.state(127));
                assertEquals(128, parent.runs().getLast().top());
                assertEquals(parent, VoxelColumn.unpack(parent.packed(), 0, parent.runs().size()));
            }
            spot = VoxelColumn.reduce(List.of(plain, plain, plain, spot), step);
            assertSame(ground, spot.state(127));
        }
    }

    @Test void capDropsDeepBoundariesBeforeExposedRoofAndNeverFillsAirGaps() {
        var source=new ArrayList<VoxelColumn.Run>();
        for(int i=0;i<48;i++) source.add(new VoxelColumn.Run(i,i+1,(i%2==0 ? Blocks.STONE:Blocks.DIRT).defaultBlockState()));
        source.add(new VoxelColumn.Run(100,102,Blocks.GOLD_BLOCK.defaultBlockState()));
        var c=VoxelColumn.compose(source);
        assertEquals(VoxelColumn.MAX_RUNS,c.runs().size());
        assertTrue(c.state(80).isAir()); assertTrue(c.state(101).is(Blocks.GOLD_BLOCK));
    }
    @Test void waterKeepsLitSeabedAndOnlyEmitsATranslucentSurface() {
        var column=new VoxelColumn(List.of(new VoxelColumn.Run(-64,126,Blocks.SAND.defaultBlockState()),
                new VoxelColumn.Run(126,128,Blocks.WATER.defaultBlockState())));
        for(int step=2;step<=64;step*=2) {
            column=VoxelColumn.reduce(List.of(column,column,column,column),step);
            assertTrue(column.state(125).is(Blocks.SAND));
            assertTrue(column.state(127).is(Blocks.WATER));
        }
        var c=column;
        var quads=VoxelMesher.boxes(0,0,16,16,1,(x,z)->c);
        var water=quads.stream().filter(q->q.material().is(Blocks.WATER)).toList();
        assertEquals(1,water.size());
        assertEquals(3,water.getFirst().axis());
        assertEquals(128-1.0/9,water.getFirst().points().getFirst().y(),1e-6);
        assertTrue(quads.stream().anyMatch(q->q.material().is(Blocks.SAND)&&q.axis()==3));
        var terrain=new PlanetTerrain(42);
        var function=new SurfaceFunction(terrain);
        boolean found=false;
        for(int z=100;z<terrain.shape().size()-100&&!found;z+=20) for(int x=100;x<terrain.shape().size()-100;x+=20) {
            int ground=terrain.groundY(Face.TOP,x,z);
            if(terrain.sea()==null||ground>=terrain.sea().level()
                    || function.visible(Face.TOP,x,z).block().getFluidState().isEmpty())continue;
            var actual=VoxelColumn.visible(function,Face.TOP,x,z);
            assertTrue(actual.state(ground).getFluidState().isEmpty());
            assertFalse(actual.state(ground+1).getFluidState().isEmpty());
            found=true;break;
        }
        assertTrue(found);
    }

    @Test void packedCacheIsRecursiveAndPublishedOnlyAfterAllLevels() throws Exception {
        var terrain=new PlanetTerrain(42);
        var f=new SurfaceFunction(terrain);
        // Small fixture avoids retaining a full production planet in the test JVM.
        var cache=new VoxelSurfaceCache(16);
        var published=new BitSet();
        cache.fill(f,1,Face.TOP,8,8,p->published.set(p));
        assertEquals(6,published.cardinality());
        var completedColumn=cache.column(Face.TOP,0,0,4);
        cache.fill(f,1,Face.TOP,8,8,p->fail("retry must preserve published patches"));
        assertEquals(completedColumn,cache.column(Face.TOP,0,0,4));
        for(var face:Face.values()) {
            var children=new ArrayList<VoxelColumn>();
            for(int dz=0;dz<2;dz++) for(int dx=0;dx<2;dx++) children.add(cache.column(face,dx*4,dz*4,4));
            assertEquals(VoxelColumn.reduce(children,8),cache.column(face,0,0,8));
        }
        assertTrue(cache.retainedBytes()>0);
        assertTrue(cache.retainedBytes()<6L*16*16*VoxelColumn.MAX_RUNS*8);
        System.out.println("Voxel fixture packed bytes="+cache.retainedBytes());
    }

    @Test void diskCopyRoundTripsEveryLevelAndRejectsForeignOrTruncatedData() throws Exception {
        var cache=new VoxelSurfaceCache(16);
        cache.fill(new SurfaceFunction(new PlanetTerrain(42)),1,Face.TOP,8,8,p->{});
        assertTrue(cache.complete());
        var bytes=new java.io.ByteArrayOutputStream();
        cache.write(new java.io.DataOutputStream(bytes));
        var copy=new VoxelSurfaceCache(16);
        var published=new BitSet();
        assertTrue(copy.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())),published::set));
        assertEquals(6,published.cardinality());
        assertTrue(copy.complete());
        for(var face:Face.values()) for(int step=4;step<=64;step*=2) for(int z=0;z<16;z+=step) for(int x=0;x<16;x+=step)
            assertEquals(cache.column(face,x,z,step),copy.column(face,x,z,step));
        var wrongSize=new VoxelSurfaceCache(32);
        assertFalse(wrongSize.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())),p->fail()));
        var truncated=new VoxelSurfaceCache(16);
        var cut=java.util.Arrays.copyOf(bytes.toByteArray(),bytes.size()/2);
        assertThrows(java.io.EOFException.class,()->truncated.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(cut)),p->fail()));
        assertFalse(truncated.complete());
        assertNull(truncated.column(Face.TOP,0,0,4));
    }
}
