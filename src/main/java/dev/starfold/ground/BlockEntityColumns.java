package dev.starfold.ground;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;

/** Per-send, identity-keyed owner snapshots; bucket order matches the owner's original traversal. */
final class BlockEntityColumns {
    private final Map<LevelChunk, List<List<BlockEntity>>> owners = new IdentityHashMap<>();

    List<BlockEntity> get(LevelChunk owner, int x, int z) {
        var columns = owners.computeIfAbsent(owner, BlockEntityColumns::index);
        var entities = columns.get(x + 16 * z);
        return entities == null ? List.of() : entities;
    }

    private static List<List<BlockEntity>> index(LevelChunk owner) {
        var columns = new ArrayList<List<BlockEntity>>(Collections.nCopies(256, null));
        for (var entity : owner.getBlockEntities().values()) {
            var at = entity.getBlockPos();
            int column = (at.getX() & 15) + 16 * (at.getZ() & 15);
            var entities = columns.get(column);
            if (entities == null) {
                entities = new ArrayList<>();
                columns.set(column, entities);
            }
            entities.add(entity);
        }
        return columns;
    }
}
