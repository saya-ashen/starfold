package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

class GroundPackedAppearanceTest {
    private ResourceManager manager(Map<String, byte[]> assets, AtomicInteger reads) {
        var resources = new LinkedHashMap<ResourceLocation, Resource>();
        assets.forEach((path, data) -> resources.put(ResourceLocation.parse("test:" + path),
                new Resource(null, () -> { reads.incrementAndGet(); return new ByteArrayInputStream(data); })));
        return (ResourceManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResourceManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("listResources")) {
                        String prefix = (String) args[0];
                        assertFalse(prefix.isEmpty(), "directory resource packs reject an empty path");
                        @SuppressWarnings("unchecked") var filter = (Predicate<ResourceLocation>) args[1];
                        var result = new LinkedHashMap<ResourceLocation, Resource>();
                        resources.forEach((id, resource) -> { if (id.getPath().startsWith(prefix + "/") && filter.test(id)) result.put(id, resource); });
                        return result;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
    @Test void stampHashesActualResolvedContentAndLayoutNotJustPackNamesAndIsComputedOnce() {
        GroundMeshResources.endReload();
        var assets = new LinkedHashMap<String, byte[]>();
        assets.put("textures/block/stone.png", new byte[] {1, 2, 3});
        assets.put("models/block/stone.json", new byte[] {4, 5});
        var reads = new AtomicInteger();
        var first = new GroundPackedAppearance.Snapshot(manager(assets, reads), GroundMeshResources.generation(), "uv-biome-tags-blend");
        String stamp = first.fingerprint(); assertNotNull(stamp); assertEquals(stamp, first.fingerprint());
        assertEquals(2, reads.get());
        var reverse = new LinkedHashMap<String, byte[]>();
        reverse.put("models/block/stone.json", new byte[] {4, 5});
        reverse.put("textures/block/stone.png", new byte[] {1, 2, 3});
        assertEquals(stamp, new GroundPackedAppearance.Snapshot(manager(reverse, reads), GroundMeshResources.generation(), "uv-biome-tags-blend").fingerprint());
        reverse.put("textures/block/stone.png", new byte[] {1, 2, 9});
        assertNotEquals(stamp, new GroundPackedAppearance.Snapshot(manager(reverse, reads), GroundMeshResources.generation(), "uv-biome-tags-blend").fingerprint());
        assertNotEquals(stamp, new GroundPackedAppearance.Snapshot(manager(assets, reads), GroundMeshResources.generation(), "different-uv-or-biome-or-blend").fingerprint());
    }
    @Test void sameGenerationCanRetryFingerprintAfterReloadFinishes() {
        GroundMeshResources.invalidate(); var reads = new AtomicInteger();
        var captured = new GroundPackedAppearance.Snapshot(manager(Map.of("textures/a.png", new byte[] {1}), reads),
                GroundMeshResources.generation(), "new-layout");
        try {
            assertNull(captured.fingerprint()); assertEquals(0, reads.get());
            GroundMeshResources.endReload();
            assertNotNull(captured.fingerprint()); assertEquals(1, reads.get());
            assertNotNull(captured.fingerprint()); assertEquals(1, reads.get());
        } finally { GroundMeshResources.endReload(); }
    }
    @Test void staleResourceGenerationCannotReadNativeReloadInputsOrReturnACacheIdentity() {
        GroundMeshResources.endReload(); var reads = new AtomicInteger();
        var captured = new GroundPackedAppearance.Snapshot(manager(Map.of("textures/a.png", new byte[] {1}), reads),
                GroundMeshResources.generation(), "old-layout");
        GroundMeshResources.invalidate();
        try { assertNull(captured.fingerprint()); assertEquals(0, reads.get()); }
        finally { GroundMeshResources.endReload(); }
    }
}
