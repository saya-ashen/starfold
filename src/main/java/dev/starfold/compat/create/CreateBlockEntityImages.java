package dev.starfold.compat.create;

import com.simibubi.create.content.decoration.girder.GirderEncasedShaftBlock;
import com.simibubi.create.content.kinetics.base.AbstractEncasedShaftBlock;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.chainDrive.ChainDriveBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;

import dev.starfold.geometry.ChartOrientation;
import dev.starfold.ground.ChartTag;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.ground.PacketMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.UnaryOperator;

/** Projects Create-specific block entity data into the receiving chart. */
public final class CreateBlockEntityImages {
    public static void relabel(CompoundTag tag, String path, BlockPos owner, int turns,
            ServerPlayer player, ObserverFrame frame) {
        if (turns % 4 != 0
                && tag.contains("Speed", Tag.TAG_FLOAT)) {
            var state = player.level().getBlockState(owner);
            var block = state.getBlock();
            if ((block instanceof ShaftBlock || block instanceof AbstractEncasedShaftBlock
                    || block instanceof GirderEncasedShaftBlock || block instanceof ICogWheel
                    || block instanceof ChainDriveBlock) && block instanceof IRotate rotate) {
                rotateShaftSpeed(tag, rotate.getRotationAxis(state), turns);
            }
        }
        if (turns % 4 != 0) {
            if (path.equals("mechanical_arm")) {
                ChartTag.rotateOffsets(tag.getList("InteractionPoints", Tag.TAG_COMPOUND), "Pos", turns);
            } else if (path.equals("chain_conveyor")) {
                var connections = tag.getList("Connections", Tag.TAG_INT_ARRAY);
                for (int i = 0; i < connections.size(); i++) {
                    if (connections.get(i) instanceof IntArrayTag array) {
                        int[] coordinates = array.getAsIntArray();
                        if (coordinates.length == 3) {
                            var offset = new BlockPos(coordinates[0], coordinates[1], coordinates[2]);
                            connections.set(i, NbtUtils.writeBlockPos(ChartOrientation.offset(offset, turns)));
                        }
                    }
                }
                ChartTag.rotateOffsets(tag.getList("TravellingPackages", Tag.TAG_COMPOUND), "Target", turns);
                ChartTag.rotateOffset(tag, "DestroyEffect", turns);
            }
        }
        relabelPositions(tag, path, position -> PacketMap.plane(player, frame, position));
    }

    static void relabelPositions(CompoundTag tag, String path, UnaryOperator<BlockPos> project) {
        switch (path) {
            case "fluid_tank", "creative_fluid_tank" -> {
                relabelPosition(tag, "Controller", project);
                relabelPosition(tag, "LastKnownPos", project);
            }
            case "belt" -> relabelPosition(tag, "Controller", project);
        }
    }

    private static void relabelPosition(CompoundTag tag, String key, UnaryOperator<BlockPos> project) {
        NbtUtils.readBlockPos(tag, key).ifPresent(owner -> {
            var image = project.apply(owner);
            if (image != null) tag.put(key, NbtUtils.writeBlockPos(image));
        });
    }

    static void rotateShaftSpeed(CompoundTag tag, Direction.Axis axis, int turns) {
        // Create interprets RPM around the positive axis in the chart it renders.
        if (ChartOrientation.axisSign(axis, turns) < 0) tag.putFloat("Speed", -tag.getFloat("Speed"));
    }

    private CreateBlockEntityImages() {}
}
