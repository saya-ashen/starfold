package dev.starfold.render;

import dev.starfold.planet.PlanetStorage;
import dev.starfold.planet.PlanetSystem;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.*;
import java.util.*;

/** Vanilla public client update tags only, never saveWithoutMetadata/inventory save snapshots. */
public final class GroundDisplayEntities {
    public record Image(BlockPos pos, ResourceLocation type, CompoundTag update) {
        public Image {
            if (pos == null || type == null || update == null)
                throw new IllegalArgumentException("Native display entity");
            pos = pos.immutable();
            update = update.copy();
        }

        @Override
        public CompoundTag update() {
            return update.copy();
        }
    }

    public static List<Image> capture(LevelChunk chunk, HolderLookup.Provider registry) {
        var out = new ArrayList<Image>();
        int bytes = 0;
        for (var be : chunk.getBlockEntities().values()) {
            var position = be.getBlockPos();
            if (PlanetSystem.shared(chunk.getLevel()))
                position = PlanetStorage.local(PlanetStorage.planet(position), position);
            var image =
                    new Image(
                            position,
                            BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()),
                            be.getUpdateTag(registry));
            try {
                var data = new ByteArrayOutputStream();
                try (var stream = new DataOutputStream(data)) {
                    NbtIo.write(image.update, stream);
                }
                bytes += data.size() + 128;
                if (bytes > 128 * 1024 || out.size() >= 1024)
                    throw new IllegalStateException(
                            "Native public BlockEntity display budget exceeded; refusing truncated image");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            out.add(image);
        }
        return List.copyOf(out);
    }

    public static BlockEntity create(
            Image image,
            net.minecraft.world.level.Level level,
            net.minecraft.world.level.block.state.BlockState state) {
        var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(image.type());
        if (type == null || !state.hasBlockEntity() || !type.isValid(state))
            throw new IllegalArgumentException("Native display entity type/state mismatch");
        var be = type.create(image.pos(), state);
        if (be == null) throw new IllegalArgumentException("Native display entity factory");
        be.setLevel(level);
        be.loadWithComponents(image.update(), level.registryAccess());
        return be;
    }

    private GroundDisplayEntities() {}
}
