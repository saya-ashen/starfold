package dev.starfold.compat.mekanism;

import dev.starfold.geometry.ChartOrientation;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.ground.PacketMap;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/** Projects Mekanism block entity update data into the receiving chart. */
public final class MekanismBlockEntityImages {
    public static void rotateTransmitter(CompoundTag tag, int turns) {
        if (!tag.contains("connections", Tag.TAG_BYTE) || !tag.contains("acceptors", Tag.TAG_BYTE)
                || !tag.contains("connection", Tag.TAG_INT_ARRAY)) return;
        int[] modes = tag.getIntArray("connection");
        var directions = Direction.values();
        if (modes.length != directions.length) return;
        int[] rotated = new int[modes.length];
        int connections = tag.getByte("connections"), acceptors = tag.getByte("acceptors");
        int rotatedConnections = 0, rotatedAcceptors = 0;
        for (Direction direction : directions) {
            int from = direction.ordinal();
            int to = ChartOrientation.direction(direction, turns).ordinal();
            rotated[to] = modes[from];
            rotatedConnections |= ((connections >> from) & 1) << to;
            rotatedAcceptors |= ((acceptors >> from) & 1) << to;
        }
        tag.putByte("connections", (byte) rotatedConnections);
        tag.putByte("acceptors", (byte) rotatedAcceptors);
        tag.putIntArray("connection", rotated);
    }

    public static void relabelTank(CompoundTag tag, ServerPlayer player, ObserverFrame frame) {
        var min = NbtUtils.readBlockPos(tag, "min");
        var max = NbtUtils.readBlockPos(tag, "max");
        if (min.isEmpty() || max.isEmpty()) return;
        var access = PlanetAccess.of(player.level());
        if (!access.inChart(min.get()) || !access.inChart(max.get())) return;
        // Bounds are stored in the structure chart. Valve locations are already storage addresses.
        var first = PacketMap.plane(player, frame, access.address(min.get()).owner());
        var last = PacketMap.plane(player, frame, access.address(max.get()).owner());
        if (first == null || last == null) return;
        var imageMin = new BlockPos(Math.min(first.getX(), last.getX()), first.getY(), Math.min(first.getZ(), last.getZ()));
        var imageMax = new BlockPos(Math.max(first.getX(), last.getX()), last.getY(), Math.max(first.getZ(), last.getZ()));
        tag.put("min", NbtUtils.writeBlockPos(imageMin));
        tag.put("max", NbtUtils.writeBlockPos(imageMax));
        if (tag.contains("render_location")) tag.put("render_location", NbtUtils.writeBlockPos(imageMin.above()));
        var valves = tag.getList("valve", Tag.TAG_COMPOUND);
        for (int i = 0; i < valves.size(); i++) {
            var valve = valves.getCompound(i);
            NbtUtils.readBlockPos(valve, "position").ifPresent(owner -> {
                var image = PacketMap.plane(player, frame, owner);
                if (image == null) return;
                valve.put("position", NbtUtils.writeBlockPos(image));
                var side = Direction.from3DDataValue(valve.getInt("side"));
                int turns = PacketMap.turns(access, frame, owner.getX() + .5, owner.getY(), owner.getZ() + .5);
                valve.putInt("side", ChartOrientation.direction(side, turns).get3DDataValue());
            });
        }
    }

    private MekanismBlockEntityImages() {}
}
