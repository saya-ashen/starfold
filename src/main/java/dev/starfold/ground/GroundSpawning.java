package dev.starfold.ground;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Vanilla's horizontal spawning radius measured in the observer's continuous face chart. */
public final class GroundSpawning {
    private GroundSpawning() {}

    public static boolean close(PlanetAccess access, double x, double z, ChunkPos chunk) {
        double cx = chunk.getMiddleBlockX(), cz = chunk.getMiddleBlockZ();
        if (access.inChart(BlockPos.containing(x, 128, z))
                && access.inChart(BlockPos.containing(cx, 128, cz))) {
            var image = access.image(x, z, new Point(cx, 128, cz));
            if (image == null) return false;
            cx = image.x();
            cz = image.z();
        }
        double dx = cx - x, dz = cz - z;
        return dx * dx + dz * dz < 128 * 128;
    }
}
