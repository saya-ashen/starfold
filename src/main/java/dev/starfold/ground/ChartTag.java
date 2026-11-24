package dev.starfold.ground;

import dev.starfold.geometry.ChartOrientation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;

/** Transports offsets in fields identified by their data format. */
public final class ChartTag {
    private ChartTag() {}

    public static void rotateOffset(CompoundTag tag, String field, int turns) {
        NbtUtils.readBlockPos(tag, field).ifPresent(offset -> tag.put(field,
                NbtUtils.writeBlockPos(ChartOrientation.offset(offset, turns))));
    }

    public static void rotateOffsets(ListTag entries, String field, int turns) {
        for (var entry : entries) {
            if (entry instanceof CompoundTag tag) rotateOffset(tag, field, turns);
        }
    }
}
