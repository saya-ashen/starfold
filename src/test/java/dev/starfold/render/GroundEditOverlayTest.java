package dev.starfold.render;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worlds.fixtures.ExperienceFixtures;
import dev.starfold.universe.*;
import dev.starfold.worldgen.surface.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GroundEditOverlayTest {
    @BeforeAll static void boot() throws Exception { ExperienceFixtures.boot(); SurfaceTestTags.bind(); }
    static PlanetChunkGenerator generator() { return generator("asteroid"); }
    static PlanetChunkGenerator generator(String name) {
        var type=ExperienceFixtures.registryLookup().lookupOrThrow(PlanetType.KEY).listElements()
                .filter(h->h.key().location().toString().equals("starfold_worlds:"+name)).findFirst().orElseThrow().value();
        return type.planet(42,Optional.of(type.minSize()));
    }
    static ProtoChunk chunk(PlanetChunkGenerator g,int x,int z) {
        var chunk=new ProtoChunk(new ChunkPos(x,z),UpgradeData.EMPTY,LevelHeightAccessor.create(g.getMinY(),g.getGenDepth()),
                ExperienceFixtures.biomeRegistryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME),null);
        dev.starfold.worldgen.SystemGeneration.fill(g, chunk).join(); return chunk;
    }
    static void height(ProtoChunk chunk) { Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.WORLD_SURFACE)); }
    @Test void uneditedChunkHasNoEntryAndRevertRemovesPersistedEntryAndStreamsRemoval() throws Exception {
        var g=generator(); var f=new SurfaceFunction(g.terrain()); var chunk=chunk(g,12,12); height(chunk);
        long key=GroundSurfaceIndex.key(12,12);
        var index=new GroundSurfaceIndex(); index.baseline(f);
        index.loaded(chunk);
        assertTrue(index.coverage().isEmpty()); assertNull(index.surface(key)); assertFalse(index.dirty(key));
        // Even explicitly queued unchanged data compares equal; it must not survive in the overlay.
        index.edited(chunk); index.finishPending();
        assertTrue(index.coverage().isEmpty()); assertNull(index.surface(key));
        var pos=new BlockPos(200,300,200); var original=chunk.getBlockState(pos);
        chunk.setBlockState(pos,Blocks.GOLD_BLOCK.defaultBlockState(),false); height(chunk);
        index.edited(chunk);
        while(index.dirty(key)) assertTrue(index.advance(137,List.of(),Long.MAX_VALUE)<=137);
        assertNotNull(index.surface(key)); assertTrue(index.coverage().get((int)key));
        var loaded=GroundSurfaceIndex.load(index.save(new net.minecraft.nbt.CompoundTag(),null),null);
        assertTrue(loaded.surface(key).sameColumns(index.surface(key)));
        var eye=g.terrain().shape().direction(Face.TOP,200,200).scale(g.terrain().shape().radius()+100);
        var sent=new HashMap<Long,Long>(); var stream=new GroundSurfaceStream();
        assertEquals(key,stream.next(index,sent,g.terrain().shape(),eye)); sent.put(key,index.surfaceRevision(key));
        chunk.setBlockState(pos,original,false); height(chunk); index.edited(chunk); index.finishPending();
        assertNull(index.surface(key)); assertFalse(index.coverage().get((int)key));
        assertEquals(0,index.save(new net.minecraft.nbt.CompoundTag(),null).getList("surfaces",10).size());
        assertEquals(key,stream.next(index,sent,g.terrain().shape(),eye));
        var remove=GroundViewPackets.Surface.removal(17,key,index.surfaceRevision(key));
        var raw=Unpooled.buffer();
        try {
            var buf=new RegistryFriendlyByteBuf(raw,RegistryAccess.EMPTY);
            GroundViewPackets.Surface.CODEC.encode(buf,remove);
            assertEquals(remove,GroundViewPackets.Surface.CODEC.decode(buf)); assertFalse(buf.isReadable());
        } finally {raw.release();}
        assertEquals(-1,new GroundSurfaceStream().next(index,new HashMap<>(),g.terrain().shape(),eye));
    }
    @Test void initialObjectChunksAlsoCompareEqualWithoutPersistingGeneratedTerrain() {
        for(String type:List.of("home","lush","mushroom","ice")) {
            var g=generator(type); var f=new SurfaceFunction(g.terrain());
            for(var face:Face.values()) {
                int centre=g.terrain().shape().chunks()/2;
                var chunk=chunk(g,face.ordinal()*256+centre,centre); height(chunk);
                var index=new GroundSurfaceIndex(); index.baseline(f); index.edited(chunk); index.finishPending();
                assertTrue(index.coverage().isEmpty(),type+" "+face);
                assertEquals(0,index.save(new net.minecraft.nbt.CompoundTag(),null).getList("surfaces",10).size());
            }
        }
    }
    @Test void productionMergedFieldEqualsScratchAggregationAndPublishedSnapshotSurvivesReplacement() throws Exception {
        var g=generator(); var t=g.terrain(); var f=new SurfaceFunction(t); var shape=t.shape();
        var cache=new GroundMipCache();
        long key=GroundSurfaceIndex.key(12,12);
        int[] h=new int[256],m=new int[256]; Arrays.fill(h,210); Arrays.fill(m,net.minecraft.world.level.block.Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()));
        var image=new GroundSurface(100,h,m);
        try {
            var field=cache.field(shape,t,k->k==key ? image : null,0);
            for(int level:new int[]{2,4,8,16,32,64}) {
                int u=192/level*level,v=u;
                var actual=field.cell(Face.TOP,u,v,level);
                assertNotNull(actual.voxels());
                assertEquals(-1,actual.rgb());
                assertTrue(actual.voxels().runs().stream().anyMatch(r->r.block().is(Blocks.GOLD_BLOCK)));
                assertEquals(211,actual.height()+128);
            }
            var next=new GroundSurface(102,java.util.stream.IntStream.range(0,256).map(i->250).toArray(),m);
            var replacement=cache.field(shape,t,k->k==key ? next : null,0);
            assertEquals(211-128,field.cell(Face.TOP,192,192,16).height());
            assertEquals(251-128,replacement.cell(Face.TOP,192,192,16).height());
            assertTrue(cache.poll().get(0));
        } finally {cache.clear(); GroundBaseCache.clear();}
    }
    @Test void levelTwoForestsAreAreaFilteredLikeTheCoarseCacheRatherThanDetachedTemplates() {
        var t=generator("lush").terrain(); var function=new SurfaceFunction(t);
        int u=-1,v=-1;Face selectedFace=Face.TOP;
        search: for(var face:Face.VALUES)for(int z=96;z<t.shape().size()-96;z+=2) for(int x=96;x<t.shape().size()-96;x+=2)
            if(function.sample(face,x,z,2).canopyCoverage()>0) {u=x;v=z;selectedFace=face;break search;}
        assertTrue(u>=0,"fixture contains a forest");
        int patch=GroundViewPolicy.patch(GroundOwnership.key(t.shape(),selectedFace.ordinal(),u,v));
        var cache=new GroundMipCache();
        try {
            var field=cache.field(t.shape(),t,k->null,patch);
            var actual=field.cell(selectedFace,u,v,2);
            var children=new ArrayList<dev.starfold.worldgen.lod.VoxelColumn>();
            for(int z=0;z<2;z++) for(int x=0;x<2;x++) children.add(dev.starfold.worldgen.lod.VoxelColumn.visible(function,selectedFace,u+x,v+z));
            assertEquals(dev.starfold.worldgen.lod.VoxelColumn.reduce(children,2),actual.voxels());
            assertEquals(-1,actual.rgb());
            assertNotNull(field.cell(selectedFace,u,v,1).voxels());
            var quads=GroundFarMesh.build(t.shape(),selectedFace,u,v,2,2,field);
            assertTrue(quads.stream().anyMatch(q->q.material()==actual.material()));
        } finally {cache.clear(); GroundBaseCache.clear();}
    }

    @Test void clientRejectsMismatchedAlgorithmAndRemovalPreventsStaleResurrection() {
        var profile=SurfaceProfiles.current(new PlanetPackets.Profile(17,42,dev.starfold.worldgen.PlanetTerrain.VERSION)); var remote=new PlanetRemoteView();
        var incomplete = generator("lush");
        assertFalse(remote.summary(new PlanetRemotePackets.Summary(0,
                new PlanetPackets.Profile(17,42,incomplete.version(),incomplete.generation()),0,new long[0],new long[0])));
        assertTrue(remote.summary(new PlanetRemotePackets.Summary(0,profile,1,new long[]{1},new long[0])));
        var image=GroundSurfaceMipTest.image(210,Blocks.GOLD_BLOCK.defaultBlockState());
        var mismatch=new GroundViewPackets.Surface(17,0,1,image.revision(),image);
        assertFalse(mismatch.compatible()); assertFalse(remote.surface(new PlanetRemotePackets.Surface(0,mismatch)));
        assertTrue(remote.surface(new PlanetRemotePackets.Surface(0,new GroundViewPackets.Surface(17,0,image))));
        assertTrue(remote.surface(new PlanetRemotePackets.Surface(0,GroundViewPackets.Surface.removal(17,0,2))));
        assertEquals(0,remote.size());
        assertFalse(remote.surface(new PlanetRemotePackets.Surface(0,new GroundViewPackets.Surface(17,0,image))));
    }
}
