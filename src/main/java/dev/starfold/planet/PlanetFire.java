package dev.starfold.planet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Clean saved fire (including non-ticking soul fire) after chunk publication, never during IO/load. */
public final class PlanetFire {
    private static final ConcurrentLinkedQueue<LevelChunk> loaded = new ConcurrentLinkedQueue<>();

    public static boolean fire(BlockState state) {
        // Intrinsic fire remains identifiable before datapack tags have been bound.
        return state.getBlock() instanceof BaseFireBlock || state.is(BlockTags.FIRE);
    }

    public static boolean burning(BlockState state) {
        return fire(state)
                || state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT);
    }

    public static BlockState extinguished(BlockState state) {
        return state.getBlock() instanceof CampfireBlock
                ? state.setValue(CampfireBlock.LIT, false)
                : Blocks.AIR.defaultBlockState();
    }

    public static void initialize() {
        NeoForge.EVENT_BUS.addListener(
                (ChunkEvent.Load event) -> {
                    if (event.getLevel() instanceof ServerLevel level
                            && PlanetLevels.is(level)
                            && event.getChunk() instanceof LevelChunk chunk) loaded.add(chunk);
                });
        NeoForge.EVENT_BUS.addListener(
                (LevelTickEvent.Post event) -> {
                    if (!(event.getLevel() instanceof ServerLevel level)) return;
                    var pending = new java.util.ArrayList<LevelChunk>();
                    loaded.removeIf(
                            chunk -> {
                                if (chunk.getLevel() != level) return false;
                                pending.add(chunk);
                                return true;
                            });
                    for (var chunk : pending)
                        if (level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z)
                                == chunk) clean(level, chunk);
                });
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> loaded.clear());
    }

    private static void clean(ServerLevel level, LevelChunk chunk) {
        var origin = chunk.getPos().getWorldPosition();
        if (!PlanetConditions.airless(level, origin)) return;
        for (int index = 0; index < chunk.getSectionsCount(); index++) {
            var section = chunk.getSection(index);
            if (!section.maybeHas(PlanetFire::burning)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(index) * 16;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        var state = section.getBlockState(x, y, z);
                        if (burning(state)) {
                            var pos = new BlockPos(origin.getX() + x, baseY + y, origin.getZ() + z);
                            level.setBlock(pos, extinguished(state), 3);
                        }
                    }
        }
    }

    private PlanetFire() {}
}
