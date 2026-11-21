package dev.starfold.worldgen.surface;

import dev.starfold.worlds.fixtures.ExperienceFixtures;

import java.nio.file.Files;
import java.nio.file.Path;

/** Fresh-JVM production-key probe using the same registry-only launcher as surface evaluations. */
public final class SurfaceKeyProcessProbe {
    private SurfaceKeyProcessProbe() {}

    public static void main(String[] args) throws Exception {
        var wrapper = Class.forName("net.neoforged.fml.junit.LaunchWrapper");
        var loader = (ClassLoader) wrapper.getMethod("getTransformingLoader").invoke(null);
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class.forName(
                            "dev.starfold.worldgen.surface.SurfaceKeyProcessProbe$Fixture",
                            true,
                            loader)
                    .getMethod("writeKey", String.class)
                    .invoke(null, args[0]);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    /** Loaded through NeoForge so Minecraft and mod classes receive their normal transforms. */
    public static final class Fixture {
        public static void writeKey(String result) throws Exception {
            ExperienceFixtures.boot();
            SurfaceTestTags.bind();
            Files.writeString(
                    Path.of(result), SurfaceKey.of(SurfaceCacheTest.identityTerrain()).toString());
        }
    }
}
