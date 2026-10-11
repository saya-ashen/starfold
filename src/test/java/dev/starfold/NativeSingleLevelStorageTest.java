package dev.starfold;

import com.mojang.serialization.JsonOps;
import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Storage/generation regression only. Does NOT stand in for a live A-to-B client test. */
class NativeSingleLevelStorageTest {
    final NativePlanetEcologyTest fixture=new NativePlanetEcologyTest();
    @BeforeAll static void boot(){NativePlanetEcologyTest.boot();}
    NativeSystemChunkGenerator generator(){
        var a=fixture.generator(4);
        var settings=new net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings(Optional.of(net.minecraft.core.HolderSet.direct()),NativePlanetEcologyTest.palette.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.STONY_PEAKS),List.of());
        settings.getLayersInfo().add(new net.minecraft.world.level.levelgen.flat.FlatLayerInfo(1,Blocks.BEDROCK));settings.updateLayers();
        var b=new NativePlanetChunkGenerator(settings,20261002L,5,Optional.empty(),Optional.of(new NativePlanetGeneration("rocky","rocky",1,1.2,.04,.09)));
        return new NativeSystemChunkGenerator(NativePlanetStorage.LAYOUT,a,b);
    }
    ProtoChunk chunk(NativeSystemChunkGenerator g,int x,int z){
        var c=new ProtoChunk(new ChunkPos(x,z),UpgradeData.EMPTY,NativePlanetEcologyTest.HEIGHT,NativePlanetEcologyTest.palette,null);
        g.createBiomes(null,null,null,c).join();c.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
        assertSame(c,g.fillFromNoise(null,null,null,c).join());return c;
    }
    @Test void everyCanonicalChunkHasOneInvertiblePlanetQualifiedAddress(){
        var seen=new HashSet<ChunkPos>();
        for(int id=0;id<2;id++)for(var face:Face.values())for(int u=0;u<128;u++)for(int v=0;v<128;v++){
            var owner=new NativePlanetStorage.Owner(id,new Cell(face,u*16,0,v*16));var pos=owner.block();
            long key=NativePlanetStorage.key(pos.getX()>>4,pos.getZ()>>4);
            assertEquals(owner.key(),key);assertEquals(new ChunkPos(pos),NativePlanetStorage.chunk(key));assertTrue(seen.add(new ChunkPos(pos)));
            assertEquals(id,NativePlanetStorage.planet(pos));
        }
        assertEquals(196608,seen.size());
    }
    @Test void legacyAAddressesAndSixFaceTopologyStayByteForByteEquivalent(){
        for(var face:Face.values())for(int u:new int[]{0,1,1024,2047})for(int v:new int[]{0,1,1024,2047}){
            var c=new Cell(face,u,-100,v);assertEquals(NativeGroundTopology.block(c),NativePlanetStorage.block(0,c));
            var p=new NativeGroundTopology.Pose(face,new Point(u+.5,100,v+.5));
            assertEquals(NativeGroundTopology.feet(p),NativePlanetStorage.feet(0,p));
            for(int id=0;id<2;id++)assertEquals(p,NativePlanetStorage.body(id,NativePlanetStorage.feet(id,p)));
        }
    }
    @Test void everyHaloAndCornerRoutesWithinItsOwnPlanetWithSameRotation(){
        for(int id=0;id<2;id++)for(var f:Face.values())for(int u:new int[]{-1,0,1024,2047,2048})for(int v:new int[]{-1,0,1024,2047,2048}){
            var local=new BlockPos(f.ordinal()*4096+u,90,v);var query=local.offset(NativePlanetStorage.offset(id),0,0);
            var expected=NativeGroundTopology.address(local);var actual=NativePlanetStorage.address(query);
            assertEquals(id,actual.owner().planet());assertEquals(expected.turns(),actual.turns());
            assertEquals(expected.owner().offset(NativePlanetStorage.offset(id),0,0),actual.owner().block());
            assertEquals(Blocks.OAK_STAIRS.defaultBlockState(),actual.oriented().write(actual.oriented().read(Blocks.OAK_STAIRS.defaultBlockState())));
        }
    }
    @Test void neighborCallbacksKeepTargetAndGhostSourceOnTheSamePlanet(){
        for(int id=0;id<2;id++)for(var f:Face.values()){
            var target=new BlockPos(f.ordinal()*4096+2048,128,1024);var source=target.west();
            var expected=NativeGroundTopology.notification(target,source);int offset=NativePlanetStorage.offset(id);
            var actual=NativePlanetStorage.notification(target.offset(offset,0,0),source.offset(offset,0,0));
            assertEquals(expected.target().offset(offset,0,0),actual.target());assertEquals(expected.source().offset(offset,0,0),actual.source());assertEquals(expected.turns(),actual.turns());
        }
        assertThrows(IllegalArgumentException.class,()->NativePlanetStorage.notification(new BlockPos(1024,128,1024),new BlockPos(33792,128,1024)));
    }
    @Test void gapsAndInvalidLayoutNeverAliasAPlanet(){
        for(int x:new int[]{-1,128,255,1408,1536,1800,2047,2176,3456,4096})assertEquals(-1,NativePlanetStorage.key(x,64));
        assertEquals(-1,NativePlanetStorage.key(64,-1));assertEquals(-1,NativePlanetStorage.key(2112,128));
        assertThrows(IllegalArgumentException.class,()->NativePlanetStorage.planet(28000,100));
        assertThrows(IllegalArgumentException.class,()->NativePlanetStorage.planet(Double.NaN,0));
        assertThrows(IllegalArgumentException.class,()->NativePlanetStorage.chunk(-1));assertThrows(IllegalArgumentException.class,()->NativePlanetStorage.chunk(196608));
        var g=generator();assertThrows(IllegalArgumentException.class,()->new NativeSystemChunkGenerator("unknown",g.planet(0),g.planet(1)));
        assertThrows(IllegalArgumentException.class,()->new NativeSystemChunkGenerator(NativePlanetStorage.LAYOUT,g.planet(1),g.planet(0)));
    }
    @Test void oneGeneratorWritesBothPlanetsIntoDifferentRealChunksAndMatchesAllHeightQueries(){
        var g=generator();
        for(int id=0;id<2;id++)for(var f:Face.values()){
            var c=chunk(g,NativePlanetStorage.offset(id)/16+f.ordinal()*256+64,64);
            for(int x=0;x<16;x+=5)for(int z=0;z<16;z+=5){
                int wx=c.getPos().getMinBlockX()+x,wz=c.getPos().getMinBlockZ()+z;
                var column=g.getBaseColumn(wx,wz,NativePlanetEcologyTest.HEIGHT,null);
                var expected=g.planet(id).getBaseColumn(wx-NativePlanetStorage.offset(id),wz,NativePlanetEcologyTest.HEIGHT,null);
                for(int y=-64;y<320;y++){assertSame(expected.getBlock(y),column.getBlock(y));assertSame(column.getBlock(y),c.getBlockState(new BlockPos(wx,y,wz)));}
                for(var type:List.of(Heightmap.Types.WORLD_SURFACE_WG,Heightmap.Types.OCEAN_FLOOR_WG))assertEquals(c.getHeight(type,x,z)+1,g.getBaseHeight(wx,wz,type,NativePlanetEcologyTest.HEIGHT,null));
            }
        }
    }
    @Test void bReallyStoresRockBiomesInsteadOfReusingAsClimate(){
        var g=generator();var b=chunk(g,2112,64);var a=chunk(g,64,64);
        for(int x=0;x<4;x++)for(int z=0;z<4;z++){
            assertTrue(b.getNoiseBiome(2112*4+x,32,64*4+z).is(net.minecraft.world.level.biome.Biomes.STONY_PEAKS));
            assertEquals(g.planet(0).getBiomeSource().getNoiseBiome(64*4+x,32,64*4+z,null),a.getNoiseBiome(64*4+x,32,64*4+z));
        }
    }
    @Test void persistedLayoutAndIndependentPoliciesRegenerateIdenticalChunks(){
        var ops=RegistryOps.create(JsonOps.INSTANCE,NativePlanetEcologyTest.lookup);var g=generator();
        var saved=NativeSystemChunkGenerator.CODEC.codec().encodeStart(ops,g).getOrThrow();
        var copy=NativeSystemChunkGenerator.CODEC.codec().parse(ops,saved).getOrThrow();
        assertEquals(NativePlanetStorage.LAYOUT,saved.getAsJsonObject().get("storage_layout").getAsString());
        for(int id=0;id<2;id++){
            assertEquals(g.planet(id).seed(),copy.planet(id).seed());assertEquals(g.planet(id).generation(),copy.planet(id).generation());
            int x=NativePlanetStorage.offset(id)/16+64;assertArrayEquals(fixture.values(chunk(g,x,64)),fixture.values(chunk(copy,x,64)));
        }
        var invalid=saved.deepCopy().getAsJsonObject();invalid.addProperty("storage_layout","v99");
        assertThrows(RuntimeException.class,()->NativeSystemChunkGenerator.CODEC.codec().parse(ops,invalid).getOrThrow());
        invalid.remove("storage_layout");assertTrue(NativeSystemChunkGenerator.CODEC.codec().parse(ops,invalid).error().isPresent());
    }
    @Test void bEditCannotOverwriteAAndSurfaceStageDoesNotPaintOverEither(){
        var g=generator();var a=chunk(g,64,64);var b=chunk(g,2112,64);var posA=new BlockPos(1024,250,1024);var posB=posA.offset(32768,0,0);
        a.setBlockState(posA,Blocks.CHEST.defaultBlockState(),false);b.setBlockState(posB,Blocks.GOLD_BLOCK.defaultBlockState(),false);
        g.buildSurface(null,null,null,a);g.buildSurface(null,null,null,b);
        assertTrue(a.getBlockState(posA).is(Blocks.CHEST));assertTrue(b.getBlockState(posB).is(Blocks.GOLD_BLOCK));
        assertNotEquals(NativePlanetStorage.address(posA).owner().key(),NativePlanetStorage.address(posB).owner().key());
    }
    @Test void voidBetweenStorageOwnersIsActuallyEmpty(){
        var g=generator();for(int x:new int[]{-1,128,1536,1800,2176,3456}){
            var c=chunk(g,x,64);assertTrue(Arrays.stream(c.getSections()).allMatch(LevelChunkSection::hasOnlyAir));
            assertEquals(-64,g.getBaseHeight(x*16,1024,Heightmap.Types.WORLD_SURFACE_WG,NativePlanetEcologyTest.HEIGHT,null));
            assertTrue(g.getBaseColumn(x*16,1024,NativePlanetEcologyTest.HEIGHT,null).getBlock(0).isAir());
        }
    }
}
