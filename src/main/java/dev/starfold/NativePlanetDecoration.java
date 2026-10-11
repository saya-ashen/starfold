package dev.starfold;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** The features of a planet's biomes and the pieces of its structures, put on a chunk as the overworld puts them: by
 *  the biome's own lists, in the game's own order. So whatever a data pack adds to a biome's features, or to the
 *  structures of a biome, appears on the planets that have that biome. Three things are left to the planet: its own
 *  ores (and with that step the patches of dirt and gravel), its own trees where it grows them, and lakes, which
 *  would spill over the seam of a face. */
public final class NativePlanetDecoration {
    static final Set<GenerationStep.Decoration> SKIPPED=EnumSet.of(GenerationStep.Decoration.LAKES,GenerationStep.Decoration.UNDERGROUND_ORES);
    private final BiomeSource biomes;private final Supplier<List<FeatureSorter.StepFeatureData>> steps;
    private final Map<PlacedFeature,Boolean> trees=new ConcurrentHashMap<>();
    public NativePlanetDecoration(BiomeSource biomes){
        this.biomes=biomes;steps=com.google.common.base.Suppliers.memoize(()->FeatureSorter.buildFeaturesPerStep(List.copyOf(biomes.possibleBiomes()),biome->biome.value().getGenerationSettings().features(),true));
    }
    /** Whether a feature grows trees, alone or as one of the things it chooses between. */
    boolean tree(PlacedFeature feature){return trees.computeIfAbsent(feature,f->f.getFeatures().anyMatch(c->c.feature()==Feature.TREE));}
    /** @param features the biome's features are placed  @param structures the pieces of structures that begin nearby are placed
     *  @param ownTrees the planet grows its own trees, so the biome's are left out */
    public void apply(ChunkGenerator generator,WorldGenLevel level,ChunkAccess chunk,StructureManager manager,boolean features,boolean structures,boolean ownTrees){
        if(!features&&!structures)return;
        ChunkPos pos=chunk.getPos();SectionPos section=SectionPos.of(pos,level.getMinSection());BlockPos origin=section.origin();
        var byStep=new HashMap<Integer,List<Structure>>();if(structures&&manager.shouldGenerateStructures())for(var structure:level.registryAccess().registryOrThrow(Registries.STRUCTURE))byStep.computeIfAbsent(structure.step().ordinal(),k->new ArrayList<>()).add(structure);
        var list=features?steps.get():List.<FeatureSorter.StepFeatureData>of();
        var random=new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));long seed=random.setDecorationSeed(level.getSeed(),origin.getX(),origin.getZ());
        Set<Holder<Biome>> present=new HashSet<>();ChunkPos.rangeClosed(section.chunk(),1).forEach(near->{for(var part:level.getChunk(near.x,near.z).getSections())part.getBiomes().getAll(present::add);});present.retainAll(biomes.possibleBiomes());
        var area=writable(chunk);
        try{
            for(int step=0,count=Math.max(GenerationStep.Decoration.values().length,list.size());step<count;step++){
                int index=0;
                for(var structure:byStep.getOrDefault(step,List.of())){random.setFeatureSeed(seed,index++,step);manager.startsForStructure(section,structure).forEach(start->start.placeInChunk(level,manager,generator,random,area,pos));}
                if(step>=list.size()||step<GenerationStep.Decoration.values().length&&SKIPPED.contains(GenerationStep.Decoration.values()[step]))continue;
                var data=list.get(step);var chosen=new TreeSet<Integer>();
                for(var biome:present){var of=biome.value().getGenerationSettings().features();if(step<of.size())of.get(step).stream().map(Holder::value).forEach(feature->chosen.add(data.indexMapping().applyAsInt(feature)));}
                for(int at:chosen){var feature=data.features().get(at);if(ownTrees&&tree(feature))continue;random.setFeatureSeed(seed,at,step);feature.placeWithBiomeCheck(level,generator,random,origin);}
            }
        }finally{level.setCurrentlyGenerating(null);}
    }
    /** What decoration may write for a chunk: itself and its eight neighbours, as on any world. */
    private static BoundingBox writable(ChunkAccess chunk){var pos=chunk.getPos();int x=pos.getMinBlockX(),z=pos.getMinBlockZ();var height=chunk.getHeightAccessorForGeneration();return new BoundingBox(x,height.getMinBuildHeight()+1,z,x+15,height.getMaxBuildHeight()-1,z+15);}
}
