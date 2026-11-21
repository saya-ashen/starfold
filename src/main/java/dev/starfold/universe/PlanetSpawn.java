package dev.starfold.universe;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** Only fresh/debug terrain entries choose dry land. Saved navigation retains its exact location. */
public final class PlanetSpawn {
    public static Point site(ServerLevel level, int face) {
        return site(level, 0, face);
    }

    public static Point site(ServerLevel level, int planet, int face) {
        PlanetShape.valid(planet);
        var generator =
                ((SystemChunkGenerator) level.getChunkSource().getGenerator()).planet(planet);
        int[] location = dryLand(generator.terrain(), CubeSurfaceTopology.Face.VALUES[face]);
        int x = PlanetStorage.offset(planet) + face * 4096 + location[0],
                z = PlanetStorage.offsetZ(planet) + location[1];
        // Level.getHeight does not load an absent chunk: it returns min build height.
        // Request the FULL native chunk first, then read its authoritative heightmap.
        var chunk = level.getChunk(x >> 4, z >> 4);
        int y = entryY(chunk, x, z);
        y = ecologyEntryY(chunk, x, z);
        if (y + 2 >= level.getMaxBuildHeight()
                || !level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).isAir()
                || !level.getBlockState(new net.minecraft.core.BlockPos(x, y + 1, z)).isAir())
            throw new IllegalStateException(
                    "No unobstructed native terrain entry; no blocks changed");
        return new Point(x + .5, y, z + .5);
    }

    public static int entryY(net.minecraft.world.level.chunk.ChunkAccess chunk, int x, int z) {
        if (chunk.getPos().x != (x >> 4) || chunk.getPos().z != (z >> 4))
            throw new IllegalArgumentException("Entry chunk owner");
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 2;
    }

    public static int ecologyEntryY(
            net.minecraft.world.level.chunk.ChunkAccess chunk, int x, int z) {
        return Math.max(
                entryY(chunk, x, z),
                chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 2);
    }

    public static int[] dryLand(PlanetTerrain terrain, CubeSurfaceTopology.Face face) {
        int centre = terrain.shape().size() / 2;
        for (int r = 0; r <= centre * 3 / 4; r += 16)
            for (int x = -r; x <= r; x += 16)
                for (int z = -r; z <= r; z += 16)
                    if (Math.max(Math.abs(x), Math.abs(z)) == r
                            && drySurface(terrain, face, centre + x, centre + z))
                        return new int[] {centre + x, centre + z};
        throw new IllegalStateException("No safe dry native terrain entry on face " + face);
    }

    private static boolean drySurface(
            PlanetTerrain terrain, CubeSurfaceTopology.Face face, int u, int v) {
        int ground = terrain.groundY(face, u, v);
        if (ground < 129 || terrain.submerged(face, u, v, ground)) return false;
        // Use the full production column: shallow bands return AIR outside their stored range.
        var column = terrain.column(face, u, v);
        if (ground < column.bottom() || ground + 1 >= column.end()) return false;
        // Ordinary snow is a safe cap; require two real air cells above it.
        int clearance = ground + (column.state(ground + 1).is(Blocks.SNOW) ? 2 : 1);
        if (clearance + 1 >= column.end()) return false;
        var surface = column.state(ground);
        return surface.isSolid()
                && surface.getFluidState().isEmpty()
                && !surface.is(Blocks.MAGMA_BLOCK)
                && !surface.is(Blocks.CACTUS)
                && !surface.is(Blocks.POWDER_SNOW)
                && !surface.is(Blocks.POINTED_DRIPSTONE)
                && !surface.is(Blocks.LAVA)
                && !surface.is(Blocks.FIRE)
                && !surface.is(Blocks.SOUL_FIRE)
                && !surface.is(Blocks.CAMPFIRE)
                && !surface.is(Blocks.SOUL_CAMPFIRE)
                && column.state(clearance).isAir()
                && column.state(clearance + 1).isAir();
    }

    private PlanetSpawn() {}
}
