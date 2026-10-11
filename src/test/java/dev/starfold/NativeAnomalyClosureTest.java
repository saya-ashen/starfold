package dev.starfold;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeAnomalyClosureTest {
    @BeforeAll static void boot(){NativePlanetTerrainTest.registry();}
    @Test void everyOwnedCornerColumnIsSolidFromItsOwnFloorThroughTheMaskTop(){
        for(int size:new int[]{512,1024,2048}){int depth=Math.max(191,(int)Math.round(size/Math.PI));var generation=new NativePlanetGeneration("temperate","mining",1,1,0,.115,size,depth,NativePlanetGeneration.CORE_SHELL);var terrain=new NativePlanetTerrain(7,3,generation);var mask=GroundSpace.DEFAULT_MASK;
            for(var face:CubeSurfaceTopology.Face.values())for(int x:new int[]{0,15,16,size-17,size-16,size-1})for(int z:new int[]{0,15,16,size-17,size-16,size-1}){
                boolean inside=NativeAnomaly.column(terrain.shape(),mask,new CubeSurfaceTopology.Cell(face,x,0,z));var column=terrain.column(face,x,z);
                for(int y=terrain.floor();y<=terrain.closureTop();y++){var state=column.state(y);assertEquals(inside,state.is(NativeAnomaly.CORE_BLOCK),face+" "+x+","+y+","+z);if(inside){assertFalse(state.getCollisionShape(null,BlockPos.ZERO).isEmpty());assertSame(state,terrain.state(face,x,y,z));if(y>=120)assertTrue(NativeAnomaly.mask(terrain.shape(),mask,new CubeSurfaceTopology.Cell(face,x,y-128,z)));}}
                assertTrue(column.state(terrain.floor()-1).isAir());assertFalse(column.state(terrain.closureTop()+1).is(NativeAnomaly.CORE_BLOCK));if(inside){assertEquals(255,terrain.surfaceY(face,x,z));assertSame(column.state(255),terrain.surfaceState(face,x,z));}else assertSame(NativeCore.shell(),column.state(terrain.floor()));
            }
        }
    }
    @Test void realChunkSectionsAndBaseColumnsContainTheClosureNotJustLevelReads(){
        var settings=new net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings(java.util.Optional.of(net.minecraft.core.HolderSet.direct()),NativePlanetTerrainTest.biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS),java.util.List.of());settings.updateLayers();
        for(int version:new int[]{2,3}){var generator=new NativePlanetChunkGenerator(settings,7,version);var height=LevelHeightAccessor.create(-64,384);for(var face:CubeSurfaceTopology.Face.values()){
            int x=face.ordinal()*4096;var chunk=new ProtoChunk(new ChunkPos(x>>4,0),UpgradeData.EMPTY,height,NativePlanetTerrainTest.biomes,null);generator.fillFromNoise(null,null,null,chunk).join();var column=generator.getBaseColumn(x,0,height,null);
            for(int y=-64;y<=255;y++){var state=chunk.getSection(chunk.getSectionIndex(y)).getBlockState(0,y&15,0);assertTrue(state.is(NativeAnomaly.CORE_BLOCK));assertSame(state,column.getBlock(y));}
            assertEquals(255,chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,0,0));assertEquals(256,generator.getBaseHeight(x,0,net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,height,null));
        }}
    }
}
