package dev.starfold.ground;

import dev.starfold.compat.create.CreateBlockEntityImages;
import dev.starfold.compat.mekanism.MekanismBlockEntityImages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Converts block entity update data from its storage face to the receiving player's image. */
final class BlockEntityImages {
    static CompoundTag tag(CompoundTag original, BlockPos owner, BlockPos image,
            BlockEntityType<?> type, int turns, ServerPlayer player, ObserverFrame frame) {
        var tag = original.copy();
        if (tag.contains("x")) {
            tag.putInt("x", image.getX());
            tag.putInt("z", image.getZ());
        }
        var id = type == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        if (id != null) {
            switch (id.getNamespace()) {
                case "create" -> CreateBlockEntityImages.relabel(tag, id.getPath(), owner, turns, player, frame);
                case "mekanism" -> {
                    if (turns % 4 != 0) MekanismBlockEntityImages.rotateTransmitter(tag, turns);
                    if (id.getPath().equals("dynamic_tank") || id.getPath().equals("dynamic_valve")) {
                        MekanismBlockEntityImages.relabelTank(tag, player, frame);
                    }
                }
            }
        }
        return tag;
    }

    private BlockEntityImages() {}
}
