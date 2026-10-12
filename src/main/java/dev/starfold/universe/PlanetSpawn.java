package dev.starfold.universe;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/** Only fresh/debug terrain entries choose dry land. Saved navigation retains its exact location. */
public final class PlanetSpawn {
    public static Point site(ServerLevel level, int face) {
        return site(level, 0, face);
    }

    public static Point site(ServerLevel level, int planet, int face) {
        PlanetShape.valid(planet);
        var source = level.getChunkSource().getGenerator();
        PlanetChunkGenerator generator =
                source instanceof SystemChunkGenerator system
                        ? system.planet(planet)
                        : source instanceof PlanetChunkGenerator single ? single : null;
        if (generator == null)
            return new Point(
                    face * 4096 + PlanetShape.STANDARD.half() + .5,
                    129,
                    PlanetShape.STANDARD.half() + .5);
        int[] location = dryLand(generator.terrain(), CubeSurfaceTopology.Face.values()[face]);
        boolean system = source instanceof SystemChunkGenerator;
        int x = (system ? PlanetStorage.offset(planet) : 0) + face * 4096 + location[0],
                z = (system ? PlanetStorage.offsetZ(planet) : 0) + location[1];
        // Level.getHeight does not load an absent chunk: it returns min build height.
        // Request the FULL native chunk first, then read its authoritative heightmap.
        var chunk = level.getChunk(x >> 4, z >> 4);
        int y = entryY(chunk, x, z);
        if (generator.version() == PlanetTerrain.ECOLOGY_VERSION) y = ecologyEntryY(chunk, x, z);
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
                            && terrain.groundY(face, centre + x, centre + z) >= 129)
                        return new int[] {centre + x, centre + z};
        return new int[] {centre, centre};
    }

    private PlanetSpawn() {}
}
