package dev.starfold.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.*;

/** Places the pieces of planned structures; biome features belong to the pointwise surface composition. */
public final class PlanetDecoration {
    /** Mirror vanilla's AIR -> placed state notification for POIs prefilled by pointwise generation. */
    public static void generatedPois(ChunkAccess chunk,java.util.function.BiConsumer<BlockPos,net.minecraft.world.level.block.state.BlockState> placed) {
        for(int i=0;i<chunk.getSectionsCount();i++) {
            var section=chunk.getSection(i);
            if(!section.maybeHas(net.minecraft.world.entity.ai.village.poi.PoiTypes::hasPoi))continue;
            var sectionPos=SectionPos.of(chunk.getPos(),chunk.getSectionYFromSectionIndex(i));
            sectionPos.blocksInside().forEach(pos->{
                var state=section.getBlockState(pos.getX()&15,pos.getY()&15,pos.getZ()&15);
                if(net.minecraft.world.entity.ai.village.poi.PoiTypes.hasPoi(state))placed.accept(pos.immutable(),state);
            });
        }
    }

    public static void apply(
            ChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            StructureManager manager) {
        if (!manager.shouldGenerateStructures()) return;
        var pos = chunk.getPos();
        SectionPos section = SectionPos.of(pos, level.getMinSection());
        BlockPos origin = section.origin();
        var byStep = new HashMap<Integer, List<Structure>>();
        for (var structure : level.registryAccess().registryOrThrow(Registries.STRUCTURE))
            byStep.computeIfAbsent(structure.step().ordinal(), k -> new ArrayList<>())
                    .add(structure);
        var random =
                new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long seed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
        var area = writable(chunk);
        try {
            for (int step = 0; step < GenerationStep.Decoration.values().length; step++) {
                int index = 0;
                for (var structure : byStep.getOrDefault(step, List.of())) {
                    random.setFeatureSeed(seed, index++, step);
                    manager.startsForStructure(section, structure)
                            .forEach(
                                    start ->
                                            start.placeInChunk(
                                                    level, manager, generator, random, area, pos));
                }
            }
        } finally {
            level.setCurrentlyGenerating(null);
        }
    }

    private static BoundingBox writable(ChunkAccess chunk) {
        var pos = chunk.getPos();
        int x = pos.getMinBlockX(), z = pos.getMinBlockZ();
        var height = chunk.getHeightAccessorForGeneration();
        return new BoundingBox(
                x,
                height.getMinBuildHeight() + 1,
                z,
                x + 15,
                height.getMaxBuildHeight() - 1,
                z + 15);
    }
}
