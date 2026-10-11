package dev.starfold;

import net.minecraft.core.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

/** Bake prefers datapack stems; keep explicit saved generator choices, never upgrade defaults. */
public final class NativePlanetWorldPreset {
    public static Registry<LevelStem> registryFor(WorldDimensions dimensions,Registry<LevelStem> original){
        var replacements=new java.util.LinkedHashMap<ResourceKey<LevelStem>,LevelStem>();
        for(var dimension:java.util.List.of(NativeGroundServer.PLANET,NativeGroundServer.ROCK)){
            var key=ResourceKey.create(net.minecraft.core.registries.Registries.LEVEL_STEM,dimension.location());var choice=dimensions.dimensions().get(key);
            if(choice!=null&&choice.generator() instanceof NativeSystemChunkGenerator){if(!dimension.equals(NativeGroundServer.PLANET))throw new IllegalArgumentException("Shared system must use one native level");replacements.put(key,choice);continue;}
            if(choice==null||!(choice.generator() instanceof NativePlanetChunkGenerator g)||g.version()<NativePlanetTerrain.MINING_VERSION||original.get(key)==choice)continue;
            if(dimension.equals(NativeGroundServer.ROCK)&&(!g.multiple()||g.version()!=5))throw new IllegalArgumentException("Invalid saved rocky system");
            replacements.put(key,choice);
        }
        if(replacements.isEmpty())return original;
        var copy=new MappedRegistry<LevelStem>(original.key(),original.registryLifecycle());
        for(var entry:original.entrySet())Registry.register(copy,entry.getKey(),replacements.getOrDefault(entry.getKey(),entry.getValue()));
        for(var entry:replacements.entrySet())if(!original.containsKey(entry.getKey()))Registry.register(copy,entry.getKey(),entry.getValue());
        return copy.freeze();
    }
    private NativePlanetWorldPreset(){}
}
