package dev.starfold.render;

import dev.starfold.Sources;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Captured mesh tint resolves actual ecology roles without a ClientLevel or GPU. */
class GroundDetailTintTest {
    @Test void capturedBlocksAndLiquidsUseActualEcologyRolesThroughTheSharedTintGetter() throws Exception {
        PlanetTerrainTest.registry();
        var registry = new MappedRegistry<Biome>(Registries.BIOME, com.mojang.serialization.Lifecycle.stable());
        var ids = new ArrayList<ResourceLocation>();
        for (int i = 0; i < 4; i++) {
            var id = ResourceLocation.parse("test:detail_role_" + i);
            ids.add(id);
            Registry.register(registry, id, new Biome.BiomeBuilder().hasPrecipitation(true).temperature(.7f).downfall(.8f)
                    .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).waterFogColor(0).skyColor(0)
                            .grassColorOverride(0x102030 + i).foliageColorOverride(0x405060 + i).waterColor(0x708090 + i).build())
                    .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build());
        }
        var access = new RegistryAccess.ImmutableRegistryAccess(List.of(registry.freeze()));
        var terrain=PlanetBiomeFixtures.tinted(ids);
        // Match surfaceTint's canonical face storage coordinates, not the observer's biome.
        var resolved = PlanetTint.view(null, access, 0, terrain, ids.getFirst());
        var captured = new GroundMeshContext(0, terrain.shape(), terrain, 0, 0, resolved, 1, 1);
        var other = new GroundMeshContext(1, terrain.shape(), terrain, 4096, 8192,
                PlanetTint.view(null, access, 1, terrain, ids.getFirst()), 2, 2);
        assertEquals(new BlockPos(4097, 128, 8194), other.storage(new BlockPos(1, 128, 2)));
        assertEquals(new BlockPos(1, 128, 2), captured.storage(new BlockPos(1, 128, 2)));
        var seen = new HashSet<ResourceLocation>();
        ColorResolver grass = Biome::getGrassColor, foliage = (b, x, z) -> b.getFoliageColor(), water = (b, x, z) -> b.getWaterColor();
        for (var face : Face.values()) for (int z = 2; z < terrain.shape().size(); z += 64)
            for (int x = 2; x < terrain.shape().size(); x += 64) {
                var expected=ResourceLocation.parse(terrain.biome(face,x,z));
                if (!seen.add(expected)) continue;
                var storage = PlanetTint.position(0, face.ordinal(), x, 128, z);
                int role = ids.indexOf(expected);
                assertEquals(0x102030 + role, captured.tint().getBlockTint(captured.storage(storage), grass));
                assertEquals(0x405060 + role, captured.tint().getBlockTint(captured.storage(storage), foliage));
                assertEquals(0x708090 + role, captured.tint().getBlockTint(captured.storage(storage), water));
            }
        assertEquals(Set.copyOf(ids), seen, "exercise all four actual ecology roles");
        assertSwitchLifecycle(captured);
    }

    private static void assertSwitchLifecycle(GroundMeshContext source) throws Exception {
        var session = new java.util.concurrent.atomic.AtomicLong(1);
        var captured = new GroundMeshContext(source.planet(), source.shape(), source.terrain(),
                source.offsetX(), source.offsetZ(), source.tint(), session.get(), GroundMeshResources.generation());
        var position = new BlockPos(32, 128, 32);
        ColorResolver water = (biome, x, z) -> biome.getWaterColor();
        int color = captured.tint().getBlockTint(captured.storage(position), water);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var released = new java.util.concurrent.atomic.AtomicInteger();
        var published = new java.util.concurrent.atomic.AtomicInteger();
        var lease = new GroundMeshLease(released::incrementAndGet);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var task = worker.submit(() -> {
                assertTrue(lease.begin());
                entered.countDown();
                try {
                    assertTrue(resume.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    assertEquals(position, captured.storage(position));
                    assertSame(source.tint(), captured.tint());
                    assertEquals(color, captured.tint().getBlockTint(captured.storage(position), water));
                    if (captured.current(session.get())) published.incrementAndGet();
                } finally { lease.end(); }
                return null;
            });
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(captured.current(session.get()));
            session.incrementAndGet(); // A -> B
            assertFalse(captured.current(session.get()));
            session.incrementAndGet(); // B -> A still has a new generation
            assertFalse(captured.current(session.get()));
            session.incrementAndGet(); // disconnect
            lease.close();
            assertEquals(0, released.get());
            resume.countDown();
            task.get(5, java.util.concurrent.TimeUnit.SECONDS);
            lease.close();
            assertEquals(0, published.get());
            assertEquals(1, released.get());
            GroundMeshResources.invalidate();
            GroundMeshResources.endReload();
            assertFalse(captured.current(captured.session()), "reload also fences an otherwise matching session");
        } finally {
            resume.countDown();
            worker.shutdownNow();
            GroundMeshResources.endReload();
        }
    }
}
