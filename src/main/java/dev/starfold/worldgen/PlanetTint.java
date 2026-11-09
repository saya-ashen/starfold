package dev.starfold.worldgen;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.PlanetStorage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** A far mesh must not query the observer's loaded chunks for another body's biome.
 * Coordinates are canonical storage coordinates; the saved generator supplies the biome even when unloaded. */
public final class PlanetTint {
    public static BlockPos position(int planet, int face, int x, int y, int z) {
        return new BlockPos(
                PlanetStorage.offset(planet) + face * 4096 + x,
                y,
                PlanetStorage.offsetZ(planet) + z);
    }

    public static ResourceKey<Biome> biome(
            PlanetTerrain terrain, ResourceLocation fixed, int face, int x, int z) {
        return ResourceKey.create(Registries.BIOME,ResourceLocation.parse(terrain.biome(Face.VALUES[face],x,z)));
    }

    public static boolean known(
            dev.starfold.universe.PlanetPackets.Profile profile,
            net.minecraft.core.RegistryAccess registries) {
        return registries
                .registry(Registries.BIOME)
                .map(r -> profile.biomesKnown(r::containsKey))
                .orElse(false);
    }

    public static BlockAndTintGetter view(
            BlockAndTintGetter level,
            net.minecraft.core.RegistryAccess registries,
            int planet,
            PlanetTerrain terrain,
            ResourceLocation fixed) {
        return view(level,registries,planet,terrain,fixed,0);
    }

    public static BlockAndTintGetter view(BlockAndTintGetter level,net.minecraft.core.RegistryAccess registries,int planet,
                                         PlanetTerrain terrain,ResourceLocation fixed,int blendRadius) {
        var registry = registries.registry(Registries.BIOME);
        var outside = ResourceKey.create(Registries.BIOME, fixed);
        var biomes=new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<Holder<Biome>>();
        java.util.function.Function<BlockPos,Holder<Biome>> at = p -> {
            long address=ChunkPos.asLong(p.getX(),p.getZ());
            var cached=biomes.getAndMoveToLast(address);
            if(cached!=null)return cached;
            int x = p.getX()-PlanetStorage.offset(planet), z = p.getZ()-PlanetStorage.offsetZ(planet);
            int face = Math.floorDiv(x,4096), u = Math.floorMod(x,4096);
            var key = face < 0 || face >= 6 || u >= terrain.shape().size() || z < 0 || z >= terrain.shape().size()
                    ? outside
                    : biome(terrain,fixed,face,u,z);
            var holder=registry.orElseThrow().getHolder(key).or(() -> registry.orElseThrow().getHolder(ResourceKey.create(Registries.BIOME,fixed)))
                    .or(() -> registry.orElseThrow().getHolder(Biomes.PLAINS)).orElseThrow();
            biomes.putAndMoveToLast(address,holder);
            if(biomes.size()>65536)biomes.removeFirst();
            return holder;
        };
        // Reuse vanilla's seeded quart zoom; the biome source itself samples quart centres.
        var manager = level instanceof Level l ? l.getBiomeManager().withDifferentSource(
                (x,y,z) -> at.apply(new BlockPos(x*4+2,y*4+2,z*4+2))) : null;
        var colors=new java.util.IdentityHashMap<ColorResolver,it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap>();
        return new BlockAndTintGetter() {
            public BlockState getBlockState(BlockPos p) {
                return level.getBlockState(p);
            }

            public FluidState getFluidState(BlockPos p) {
                return level.getFluidState(p);
            }

            public BlockEntity getBlockEntity(BlockPos p) {
                return level.getBlockEntity(p);
            }

            public int getHeight() {
                return level.getHeight();
            }

            public int getMinBuildHeight() {
                return level.getMinBuildHeight();
            }

            public float getShade(net.minecraft.core.Direction d, boolean shade) {
                return level.getShade(d, shade);
            }

            public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
                return level.getLightEngine();
            }

            public int getBlockTint(BlockPos p, ColorResolver resolver) {
                var cache=colors.computeIfAbsent(resolver,r->{
                    var map=new it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap();
                    map.defaultReturnValue(-1);return map;
                });
                long key=p.asLong();
                int old=cache.getAndMoveToLast(key);
                if(old!=-1)return old;
                int color=calculateTint(p,resolver);
                cache.putAndMoveToLast(key,color);
                if(cache.size()>65536)cache.removeFirstInt();
                return color;
            }

            private int calculateTint(BlockPos p,ColorResolver resolver) {
                if (registry.isEmpty()) return 0xffffff;
                int red=0,green=0,blue=0;
                for (int z=-blendRadius;z<=blendRadius;z++) for (int x=-blendRadius;x<=blendRadius;x++) {
                    var pos = p.offset(x,0,z);
                    var biome = manager == null ? at.apply(pos) : manager.getBiome(pos);
                    int color = resolver.getColor(biome.value(),pos.getX(),pos.getZ());
                    red += color >> 16 & 255; green += color >> 8 & 255; blue += color & 255;
                }
                int count=(2*blendRadius+1)*(2*blendRadius+1);
                return (red/count)<<16 | (green/count)<<8 | blue/count;
            }
        };
    }

    private PlanetTint() {}
}
