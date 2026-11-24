package dev.starfold.compat.mekanism;

import dev.starfold.planet.PlanetAccess;

import mekanism.common.lib.math.voxel.VoxelCuboid;
import net.minecraft.core.BlockPos;

import java.util.Set;

/** A tank's rectangular geometry in the chart of its controller. */
public record MekanismTankChart(BlockPos reference, VoxelCuboid bounds) {
    public static MekanismTankChart find(BlockPos reference, Set<BlockPos> members,
            VoxelCuboid minimum, VoxelCuboid maximum) {
        var access = PlanetAccess.SHARED;
        if (!access.inChart(reference)) return null;
        int face = access.face(reference.getX());
        int otherFace = -1;
        BlockPos min = reference;
        BlockPos max = reference;
        for (var owner : members) {
            if (!access.inChart(owner)) return null;
            int ownerFace = access.face(owner.getX());
            if (ownerFace != face) {
                if (otherFace != -1 && otherFace != ownerFace) return null;
                otherFace = ownerFace;
            }
            var local = access.adjacentImage(reference, owner);
            if (local == null) return null;
            min = new BlockPos(Math.min(min.getX(), local.getX()), Math.min(min.getY(), local.getY()),
                    Math.min(min.getZ(), local.getZ()));
            max = new BlockPos(Math.max(max.getX(), local.getX()), Math.max(max.getY(), local.getY()),
                    Math.max(max.getZ(), local.getZ()));
        }
        if (otherFace == -1) return null;
        var bounds = new VoxelCuboid(min, max);
        return bounds.greaterOrEqual(minimum) && maximum.greaterOrEqual(bounds)
                ? new MekanismTankChart(reference, bounds) : null;
    }

    public BlockPos image(BlockPos owner) {
        var projected = PlanetAccess.SHARED.adjacentImage(reference, owner);
        return projected == null ? owner : projected;
    }

    public interface View {
        MekanismTankChart starfold$tankChart();
    }

    public interface Data {
        void starfold$setTankChart(MekanismTankChart chart);
    }
}
