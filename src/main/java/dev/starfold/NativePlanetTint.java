package dev.starfold;

import dev.starfold.CubeSurfaceTopology.Face;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** A far mesh must not query the observer's loaded chunks for another body's biome.
 * Coordinates are canonical storage coordinates; the saved generator supplies the biome even when unloaded. */
public final class NativePlanetTint {
    public static BlockPos position(int planet,int face,int x,int y,int z){return new BlockPos(NativePlanetStorage.offset(planet)+face*4096+x,y,NativePlanetStorage.offsetZ(planet)+z);}
    public static ResourceKey<Biome> biome(NativePlanetTerrain terrain,ResourceLocation fixed,int face,int x,int z){
        if(terrain.ecology()==null)return ResourceKey.create(Registries.BIOME,fixed);
        var f=Face.values()[face];
        if(terrain.sea()!=null&&terrain.groundY(f,x,z)<terrain.sea().level())return Biomes.OCEAN;
        return switch(terrain.ecology().region(f,x,z)){case FOREST->Biomes.FOREST;case PLAINS->Biomes.PLAINS;case DESERT->Biomes.DESERT;};
    }
    public static BlockAndTintGetter view(BlockAndTintGetter level,net.minecraft.core.RegistryAccess registries,int planet,NativePlanetTerrain terrain,ResourceLocation fixed){
        return new BlockAndTintGetter(){
            public BlockState getBlockState(BlockPos p){return level.getBlockState(p);}
            public FluidState getFluidState(BlockPos p){return level.getFluidState(p);}
            public BlockEntity getBlockEntity(BlockPos p){return level.getBlockEntity(p);}
            public int getHeight(){return level.getHeight();}public int getMinBuildHeight(){return level.getMinBuildHeight();}
            public float getShade(net.minecraft.core.Direction d,boolean shade){return level.getShade(d,shade);}
            public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine(){return level.getLightEngine();}
            public int getBlockTint(BlockPos p,ColorResolver resolver){
                int x=p.getX()-NativePlanetStorage.offset(planet),z=p.getZ()-NativePlanetStorage.offsetZ(planet);
                var key=biome(terrain,fixed,Math.floorDiv(x,4096),Math.floorMod(x,4096),z);
                return resolver.getColor(registries.registryOrThrow(Registries.BIOME).getHolderOrThrow(key).value(),p.getX(),p.getZ());
            }
        };
    }
    private NativePlanetTint(){}
}
