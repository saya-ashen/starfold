package dev.starfold.render;

import com.mojang.serialization.JsonOps;
import dev.starfold.mixin.GroundBiomeManagerAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.biome.Biome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Capture client identities once on render thread; hash resource contents once on a mesh worker.
 * Persistent identity includes actual atlas UV layout, resolved assets, biome tint data/zoom seed,
 * block/fluid tags, blend setting and mod versions. Reload invalidates even same-session RAM hits.
 */
final class GroundPackedAppearance {
    private static Object level;
    private static long generation = -1;
    private static int blend = -1;
    private static Snapshot cached;
    private static String tintSpace;

    static Snapshot capture() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || GroundViewClient.profile() == null) return null;
        int radius = mc.options.biomeBlendRadius().get();
        long resources = GroundMeshResources.generation();
        String space = mc.level.dimension().location() + ":" + GroundViewClient.profile().biome()
                + ":" + GroundClient.storageOffset() + ":" + GroundClient.storageOffsetZ()
                + ":" + mc.options.graphicsMode().get() + ":" + mc.options.mipmapLevels().get();
        if (level == mc.level && generation == resources && blend == radius && space.equals(tintSpace) && cached != null) return cached;
        level = mc.level; generation = resources; blend = radius; tintSpace = space;
        var identity = new StringBuilder(GroundPackedCache.VERSION).append('|').append(space).append('|').append(radius).append('|')
                .append(((GroundBiomeManagerAccess) mc.level.getBiomeManager()).starfold$zoomSeed()).append('|');
        var atlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        atlas.getTextures().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(e -> {
            var s = e.getValue();
            identity.append(e.getKey()).append(':').append(s.getU0()).append(',').append(s.getV0())
                    .append(',').append(s.getU1()).append(',').append(s.getV1()).append(';');
        });
        mc.level.registryAccess().registryOrThrow(Registries.BIOME).entrySet().stream()
                .sorted(java.util.Comparator.comparing(e -> e.getKey().location().toString())).forEach(e ->
                        identity.append(e.getKey().location()).append('=')
                                .append(Biome.NETWORK_CODEC.encodeStart(JsonOps.INSTANCE, e.getValue()).getOrThrow()).append(';'));
        for (var block : BuiltInRegistries.BLOCK) {
            identity.append(BuiltInRegistries.BLOCK.getKey(block)).append(':');
            block.builtInRegistryHolder().tags().map(t -> t.location().toString()).sorted()
                    .forEach(t -> identity.append(t).append(','));
            identity.append(';');
        }
        for (var fluid : BuiltInRegistries.FLUID) {
            identity.append(BuiltInRegistries.FLUID.getKey(fluid)).append(':');
            fluid.builtInRegistryHolder().tags().map(t -> t.location().toString()).sorted()
                    .forEach(t -> identity.append(t).append(','));
            identity.append(';');
        }
        net.neoforged.fml.ModList.get().getMods().stream().sorted(java.util.Comparator.comparing(m -> m.getModId()))
                .forEach(m -> identity.append(m.getModId()).append('=').append(m.getVersion()).append(';'));
        cached = new Snapshot(mc.getResourceManager(), resources, identity.toString());
        return cached;
    }
    static final class Snapshot {
        private final ResourceManager manager;
        private final long generation;
        private final String layout;
        private boolean attempted;
        private String fingerprint;
        Snapshot(ResourceManager manager, long generation, String layout) {
            this.manager = manager; this.generation = generation; this.layout = layout;
        }
        synchronized String fingerprint() {
            if (attempted) return fingerprint;
            GroundMeshResources.read(generation, () -> {
                attempted = true;
                try {
                    var digest = MessageDigest.getInstance("SHA-256");
                    digest.update(layout.getBytes(StandardCharsets.UTF_8));
                    var resources = new java.util.TreeMap<net.minecraft.resources.ResourceLocation,
                            net.minecraft.server.packs.resources.Resource>();
                    // An empty prefix is rejected by directory-backed packs. Enumerate the
                    // actual appearance inputs with valid resource paths, including loose packs.
                    for (String prefix : new String[] {"textures", "models", "blockstates", "atlases"})
                        resources.putAll(manager.listResources(prefix, id -> {
                            String p = id.getPath();
                            return p.endsWith(".png") || p.endsWith(".json") || p.endsWith(".mcmeta");
                        }));
                    byte[] buffer = new byte[65536];
                    for (var e : resources.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                        digest.update(e.getKey().toString().getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
                        var content = MessageDigest.getInstance("SHA-256");
                        try (var in = e.getValue().open()) {
                            int n;
                            while ((n = in.read(buffer)) != -1) content.update(buffer, 0, n);
                        }
                        digest.update(content.digest());
                    }
                    fingerprint = HexFormat.of().formatHex(digest.digest());
                } catch (IOException failure) {
                    org.slf4j.LoggerFactory.getLogger(GroundPackedAppearance.class)
                            .warn("Cannot fingerprint render resources; using resource-independent geometry cache", failure);
                } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            });
            return fingerprint;
        }
    }
    private GroundPackedAppearance() {}
}
