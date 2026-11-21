package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;

/** Captured on the client thread; the tint cache belongs to this mesh only. */
record GroundMeshContext(
        int planet,
        PlanetShape shape,
        PlanetTerrain terrain,
        int offsetX,
        int offsetZ,
        BlockAndTintGetter tint,
        long session,
        long resources,
        java.util.Map<Long, Integer> plantColors,
        java.util.Map<Long, dev.starfold.worldgen.surface.SurfaceFunction.Run> plantSamples) {
    private record BiomeKey(BlockAndTintGetter level, net.minecraft.core.RegistryAccess registries,
            int planet, PlanetTerrain terrain, net.minecraft.resources.ResourceLocation fixed,
            int blendRadius, long session, long resources) {}
    private static BiomeKey biomeKey;
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes =
            new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();

    static void clearBiomeCache() {
        biomeKey = null;
        biomes.clear();
    }

    GroundMeshContext(int planet, PlanetShape shape, PlanetTerrain terrain, int offsetX,
            int offsetZ, BlockAndTintGetter tint, long session, long resources,
            java.util.Map<Long, Integer> plantColors) {
        this(planet, shape, terrain, offsetX, offsetZ, tint, session, resources, plantColors,
                java.util.Map.of());
    }

    private static long sampleKey(int face, int x, int z) {
        return ((long) face << 48) | ((long) x & 0xffffff) << 24 | ((long) z & 0xffffff);
    }

    /** Pure immutable terrain input only. Models, tint views and GL stay on the render thread. */
    static java.util.Map<Long, dev.starfold.worldgen.surface.SurfaceFunction.Run> preparePlants(
            GroundQuadtreeMesher.Built built, PlanetTerrain terrain,
            java.util.function.BooleanSupplier current) {
        var samples = new java.util.HashMap<Long, dev.starfold.worldgen.surface.SurfaceFunction.Run>();
        if (terrain == null || terrain.scatter() == null) return samples;
        var data = built.vertices();
        int face = built.face().ordinal();
        for (int i = 0; i < built.vertexCount(); i += 4) {
            int offset = i * GroundQuadtreeMesher.STRIDE;
            int tag = Short.toUnsignedInt(data.getShort(offset + 8));
            int id = Short.toUnsignedInt(data.getShort(offset + 6)) | (tag >>> 8) << 16;
            if (built.materials().get(id).rgb() != -3) continue;
            double minX = Double.POSITIVE_INFINITY, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
            for (int q = 0; q < 4; q++) {
                int v = offset + q * GroundQuadtreeMesher.STRIDE;
                double x = Short.toUnsignedInt(data.getShort(v)) / 16.;
                double z = Short.toUnsignedInt(data.getShort(v + 4)) / 16.;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
            for (double z = minZ + .5; z < maxZ; z += 4)
                for (double x = minX + .5; x < maxX; x += 4) {
                    if (!current.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                    long key = sampleKey(face, (int) x, (int) z);
                    if (samples.size() < 4096 && !samples.containsKey(key))
                        samples.put(key, firstPlant(terrain, face, (int) x, (int) z));
                }
        }
        return java.util.Collections.unmodifiableMap(samples); // null values certify absent plants.
    }

    /** Immutable terrain fallback only; loaded vanilla section light is never captured here. */
    record Sky(PlanetTerrain terrain, java.util.Map<Long, Integer> levels) {
        int level(PlanetTerrain current, int face, int x, int z) {
            Integer value = current == terrain ? levels.get(sampleKey(face, x, z)) : null;
            return value != null ? value : skyLevel(current, face, x, z);
        }
    }

    static BlockPos lightPosition(int face, GroundQuadtreeMesher.Material material,
            double x, double y, double z) {
        return BlockPos.containing(face * 4096 + x + Math.signum(material.normalX()) * .01,
                material.normalY() == 0 ? Math.ceil(128 + material.topY())
                        : material.normalY() > 0 ? Math.ceil(128 + y) : 128 + y - .01,
                z + Math.signum(material.normalZ()) * .01);
    }

    static int skyLevel(PlanetTerrain terrain, int face, int x, int z) {
        var f = dev.starfold.geometry.CubeSurfaceTopology.Face.VALUES[face];
        var liquid = terrain == null ? null : terrain.liquid(f, x, z);
        return liquid == null || terrain.groundY(f, x, z) >= liquid.level()
                ? Integer.MIN_VALUE : liquid.level() + 1;
    }

    static Sky prepareSky(GroundQuadtreeMesher.Built built, PlanetTerrain terrain,
            java.util.function.BooleanSupplier current) {
        var levels = new java.util.HashMap<Long, Integer>();
        var data = built.vertices();
        int face = built.face().ordinal();
        for (int off = 0; off < data.limit(); off += GroundQuadtreeMesher.STRIDE) {
            if (!current.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            int tag = Short.toUnsignedInt(data.getShort(off + 8));
            int id = Short.toUnsignedInt(data.getShort(off + 6)) | (tag >>> 8) << 16;
            var material = built.materials().get(id);
            double y = Short.toUnsignedInt(data.getShort(off + 2)) / 16. - 2048
                    + ((tag & 4) == 0 ? material.lowerHeightOffset() : material.upperHeightOffset());
            var pos = lightPosition(face, material, Short.toUnsignedInt(data.getShort(off)) / 16.,
                    y, Short.toUnsignedInt(data.getShort(off + 4)) / 16.);
            int x = pos.getX() - face * 4096, z = pos.getZ();
            long key = sampleKey(face, x, z);
            if (levels.size() < 4096 && !levels.containsKey(key))
                levels.put(key, skyLevel(terrain, face, x, z));
        }
        return new Sky(terrain, java.util.Map.copyOf(levels));
    }

    private static dev.starfold.worldgen.surface.SurfaceFunction.Run firstPlant(
            PlanetTerrain terrain, int face, int x, int z) {
        for (var run : terrain.scatter().column(
                dev.starfold.geometry.CubeSurfaceTopology.Face.VALUES[face], x, z))
            if (dev.starfold.worldgen.lod.VoxelMesher.plant(run.block())) return run;
        return null;
    }
    GroundMeshContext(int planet, PlanetShape shape, PlanetTerrain terrain, int offsetX,
            int offsetZ, BlockAndTintGetter tint, long session, long resources) {
        this(planet, shape, terrain, offsetX, offsetZ, tint, session, resources,
                new java.util.HashMap<>());
    }

    /** Mesh-local: never reuse a tint across resource, season, planet or overlay captures. */
    int plantColor(int face, int x, int z) {
        long key = sampleKey(face, x, z);
        var cached = plantColors.get(key);
        if (cached != null) return cached;
        int color = -1;
        var run = plantSamples.containsKey(key) ? plantSamples.get(key) : firstPlant(terrain, face, x, z);
        if (run != null) color = GroundAlbedo.color(run.block(),
                PlanetTint.position(planet, face, x, run.bottom(), z), tint);
        // One 256x256 node at the unchanged four-block sample interval. Legacy whole-body
        // mesh callers share this context type: overflow must stay exact, not grow without bound.
        if (plantColors.size() < 4096) plantColors.put(key, color);
        return color; // Cache misses too; RGB values are nonnegative.
    }

    static GroundMeshContext capture() { return capture(java.util.Map.of()); }

    static GroundMeshContext capture(java.util.Map<Long, dev.starfold.worldgen.surface.SurfaceFunction.Run> samples) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var level = mc.level;
        var profile = GroundViewClient.profile();
        int planet = GroundClient.planet();
        var terrain = GroundViewClient.terrain();
        BlockAndTintGetter tint = level;
        if (profile != null && level != null) {
            var key = new BiomeKey(level, level.registryAccess(), planet, terrain, profile.biome(),
                    mc.options.biomeBlendRadius().get(), GroundRenderer.meshSession(),
                    GroundMeshResources.generation());
            if (!key.equals(biomeKey)) {
                biomes.clear();
                biomeKey = key;
            }
            tint = PlanetTint.view(level, key.registries(), planet, terrain, profile.biome(),
                    key.blendRadius(), biomes);
        }
        return new GroundMeshContext(
                planet,
                GroundClient.shape(),
                terrain,
                GroundClient.storageOffset(),
                GroundClient.storageOffsetZ(),
                tint,
                GroundRenderer.meshSession(),
                GroundMeshResources.generation(), new java.util.HashMap<>(), samples);
    }

    BlockPos storage(BlockPos pos) {
        return pos.offset(offsetX, 0, offsetZ);
    }

    boolean current() {
        return current(GroundRenderer.meshSession());
    }

    boolean current(long activeSession) {
        return session == activeSession && GroundMeshResources.current(resources);
    }
}
