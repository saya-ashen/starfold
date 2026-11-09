package dev.starfold.render;

import dev.starfold.worldgen.lod.VoxelColumns;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** Bounded transport for optional derived geometry; no legacy SavedData reader. */
final class GroundVoxelCodec {
    static void write(RegistryFriendlyByteBuf buffer, VoxelColumns image) {
        buffer.writeBoolean(image != null);
        if (image == null) return;
        buffer.writeInt(image.floor());
        buffer.writeInt(image.ceiling());
        buffer.writeVarIntArray(image.offsets());
        buffer.writeVarIntArray(image.ends());
        buffer.writeVarIntArray(image.states());
    }

    static VoxelColumns read(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) return null;
        return new VoxelColumns(
                buffer.readInt(),
                buffer.readInt(),
                buffer.readVarIntArray(VoxelColumns.COLUMNS + 1),
                buffer.readVarIntArray(VoxelColumns.MAX_RUNS),
                buffer.readVarIntArray(VoxelColumns.MAX_RUNS));
    }

    private GroundVoxelCodec() {}
}
