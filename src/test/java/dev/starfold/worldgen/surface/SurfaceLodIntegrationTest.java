package dev.starfold.worldgen.surface;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.worlds.fixtures.ExperienceFixtures;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import org.junit.jupiter.api.*;

import java.util.*;

class SurfaceLodIntegrationTest {
    @BeforeAll
    static void boot() throws Exception {
        ExperienceFixtures.boot();
        SurfaceTestTags.bind();
    }

    @Test
    void profileTransportsResolvedTemplatesAndClientMatchesServerIncludingSeams() {
        for (String type : List.of("lush", "home", "mushroom", "ice", "basalt")) {
            var generator = SurfaceGenerationTest.generator(type);
            var profile = PlanetPackets.Profile.of(17, generator);
            var raw = Unpooled.buffer();
            PlanetPackets.Profile decoded;
            try {
                var buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
                PlanetPackets.Profile.CODEC.encode(buf, profile);
                assertTrue(raw.readableBytes() < 1_000_000);
                decoded = PlanetPackets.Profile.CODEC.decode(buf);
                assertEquals(profile, decoded);
                assertFalse(buf.isReadable());
            } finally {
                raw.release();
            }
            assertTrue(decoded.surfaceCompatible());
            var server = new SurfaceFunction(generator.terrain());
            var client = new SurfaceFunction(decoded.terrain());
            assertEquals(SurfaceKey.of(server.terrain()), SurfaceKey.of(client.terrain()));
            int size = generator.terrain().shape().size();
            var random = new Random(42);
            for (int i = 0; i < 1200; i++) {
                var face = Face.values()[i % 6];
                int u = i < 100 ? i % 2 * (size - 1) : random.nextInt(size),
                        v = random.nextInt(size);
                assertEquals(
                        server.visible(face, u, v),
                        client.visible(face, u, v),
                        type + " " + face + " " + u + "," + v);
            }
        }
    }

    @Test
    void progressivePublicationIsCompleteAndMatchesBlockingBuild() throws Exception {
        var f = new SurfaceFunction(SurfaceGenerationTest.generator("asteroid").terrain());
        var cache = new SurfaceCache(SurfaceKey.of(f.terrain()));
        var published = new HashSet<Integer>();
        cache.fill(
                f,
                4,
                Face.NORTH,
                128,
                128,
                p -> {
                    synchronized (published) {
                        assertTrue(published.add(p));
                    }
                });
        int size = f.terrain().shape().size(), width = (size + 63) / 64;
        assertEquals(6 * width * width, published.size());
        var serial = SurfaceCache.build(f, 1, Face.TOP, 0, 0);
        for (var face : Face.values())
            for (int level = 4; level <= 64; level *= 2)
                for (int v = 0; v < size; v += level)
                    for (int u = 0; u < size; u += level)
                        assertEquals(serial.cell(face, u, v, level), cache.cell(face, u, v, level));
    }

    @Test
    void lushCacheBuildMatchesIndependentSamplesIncludingPartialFaceEdges() throws Exception {
        var codec = dev.starfold.universe.PlanetChunkGenerator.CODEC.codec();
        var json =
                codec.encodeStart(ExperienceFixtures.ops(), SurfaceGenerationTest.generator("lush"))
                        .getOrThrow()
                        .getAsJsonObject();
        // Keep resolved lush templates, using the smallest supported planet instead of a timing
        // workload.
        json.getAsJsonObject("planet_generation").addProperty("size", 416);
        json.getAsJsonObject("planet_generation").addProperty("depth", 191);
        var f =
                new SurfaceFunction(
                        codec.parse(ExperienceFixtures.ops(), json).getOrThrow().terrain());
        int size = f.terrain().shape().size();
        assertEquals(416, size);
        var cache = SurfaceCache.build(f, 2, Face.NORTH, size / 2, size / 2);
        for (var face : Face.values())
            for (int level : new int[] {4, 16, 64})
                for (int at : new int[] {0, size / 2 / level * level, (size - 1) / level * level}) {
                    assertEquals(f.visibleExact(face, at, at), f.visible(face, at, at));
                    var exact = new SurfaceFunction.Aggregate();
                    for (int v = at; v < Math.min(at + level, size); v++)
                        for (int u = at; u < Math.min(at + level, size); u++)
                            exact.add(f.point(face, u, v));
                    var cell = cache.cell(face, at, at, level);
                    SurfaceCacheTest.compactEqual(exact.cell(), cell);
                    assertFalse(cell.materials().isEmpty());
                }
    }
}
