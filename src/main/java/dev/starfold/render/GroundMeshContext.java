package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;

/** Captured on the client thread; the tint cache belongs to this mesh only. */
record GroundMeshContext(int planet, PlanetShape shape, PlanetTerrain terrain,
                         int offsetX, int offsetZ, BlockAndTintGetter tint,
                         long session, long resources) {
    static GroundMeshContext capture() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var level = mc.level;
        var profile = GroundViewClient.profile();
        int planet = GroundClient.planet();
        var terrain = GroundViewClient.terrain();
        var tint = profile == null || level == null ? level : PlanetTint.view(
                level, level.registryAccess(), planet, terrain, profile.biome(),
                mc.options.biomeBlendRadius().get());
        return new GroundMeshContext(planet, GroundClient.shape(), terrain,
                GroundClient.storageOffset(), GroundClient.storageOffsetZ(), tint,
                GroundRenderer.meshSession(), GroundMeshResources.generation());
    }

    BlockPos storage(BlockPos pos) { return pos.offset(offsetX, 0, offsetZ); }

    boolean current() {
        return current(GroundRenderer.meshSession());
    }

    boolean current(long activeSession) {
        return session == activeSession && GroundMeshResources.current(resources);
    }
}
