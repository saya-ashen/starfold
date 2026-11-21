package dev.starfold.ground;

import com.mojang.serialization.Lifecycle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/** Packet/index inputs only: no server, world, chunk loading or light scheduler is started. */
final class ImageStreamTestFixtures {
    static HolderLookup.Provider lookup;
    static Registry<Biome> biomes;
    static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-64, 384);

    static synchronized void boot() {
        if (lookup != null) return;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME)
                .listElements()
                .forEach(holder -> Registry.register(registry, holder.key(), holder.value()));
        biomes = registry.freeze();
    }

    static Owner owner(ChunkPos pos) {
        try {
            // LevelChunk normally needs a live Level. These tests supply only the public data
            // accessors consumed by packet serialization/indexing, backed by a real ProtoChunk.
            var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) field.get(null);
            var owner = (Owner) unsafe.allocateInstance(Owner.class);
            owner.pos = pos;
            owner.data = new ProtoChunk(pos, UpgradeData.EMPTY, HEIGHT, biomes, null);
            owner.entities = new CountingEntities();
            return owner;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    static final class Owner extends LevelChunk {
        ChunkPos pos;
        ProtoChunk data;
        CountingEntities entities;
        int tableReads;

        private Owner() {
            super(null, new ChunkPos(0, 0));
            throw new AssertionError("Requires a live Level; use owner()");
        }

        // Equal positions deliberately compare equal, so tests detect replacing identity keys
        // with ordinary map keys even for separate owner objects at the same chunk address.
        @Override
        public boolean equals(Object other) {
            return other instanceof Owner that && pos.equals(that.pos);
        }

        @Override
        public int hashCode() {
            return pos.hashCode();
        }

        @Override
        public ChunkPos getPos() {
            return pos;
        }

        @Override
        public LevelChunkSection[] getSections() {
            return data.getSections();
        }

        @Override
        public Collection<Map.Entry<Heightmap.Types, Heightmap>> getHeightmaps() {
            return data.getHeightmaps();
        }

        @Override
        public Map<BlockPos, BlockEntity> getBlockEntities() {
            tableReads++;
            return entities;
        }

        @Override
        public int getMinBuildHeight() {
            return HEIGHT.getMinBuildHeight();
        }

        @Override
        public int getHeight() {
            return HEIGHT.getHeight();
        }
    }

    static final class CountingEntities extends LinkedHashMap<BlockPos, BlockEntity> {
        int visits;

        @Override
        public Collection<BlockEntity> values() {
            var values = super.values();
            return new AbstractCollection<>() {
                @Override
                public int size() {
                    return values.size();
                }

                @Override
                public Iterator<BlockEntity> iterator() {
                    var it = values.iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return it.hasNext();
                        }

                        @Override
                        public BlockEntity next() {
                            visits++;
                            return it.next();
                        }
                    };
                }
            };
        }
    }
}
