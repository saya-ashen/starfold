package dev.starfold.ship;

import dev.starfold.planet.PlanetLevels;

import net.minecraft.server.level.ServerPlayer;

import org.joml.Vector3d;

/** Aboard's seam grace is not a fall-damage grace period: outside the deck footprint use vanilla immediately. */
public final class RiderFallContext {
    public static double displacement(ServerPlayer player, RiderFall rule, double worldY) {
        if (!PlanetLevels.is(player.level())) return rule.step(null, 0, worldY);
        var body = SableFrames.aboard(player);
        if (body == null || body.isRemoved() || body.getLevel() != player.level())
            return rule.step(null, 0, worldY);
        var local =
                body.logicalPose()
                        .transformPositionInverse(
                                new Vector3d(player.getX(), player.getY(), player.getZ()));
        var bounds = body.getPlot().getBoundingBox();
        if (bounds == null
                || local.x < bounds.minX()
                || local.x > bounds.maxX() + 1
                || local.z < bounds.minZ()
                || local.z > bounds.maxZ() + 1) return rule.step(null, 0, worldY);
        // A bounding rectangle alone would exempt a step into a hole or past a narrow deck.
        // Keep hull-relative falling only above actual collision geometry of this plot column.
        int x = (int) Math.floor(local.x), z = (int) Math.floor(local.z);
        for (int y = Math.min(bounds.maxY(), (int) Math.floor(local.y)); y >= bounds.minY(); y--) {
            var pos = new net.minecraft.core.BlockPos(x, y, z);
            var state = player.level().getBlockState(pos);
            for (var box : state.getCollisionShape(player.level(), pos).toAabbs())
                if (local.x - x >= box.minX
                        && local.x - x <= box.maxX
                        && local.z - z >= box.minZ
                        && local.z - z <= box.maxZ
                        && y + box.maxY <= local.y + .001) return rule.step(body, local.y, worldY);
        }
        return rule.step(null, 0, worldY);
    }

    private RiderFallContext() {}
}
