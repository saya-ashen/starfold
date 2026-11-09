package dev.starfold.worldgen.surface;

import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTint;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Resolved L5 templates, not generated terrain. Synced biome registries omit feature lists. */
public record SurfacePolicy(String identity, String compressed) {
    private static final int LIMIT = 900_000, RAW_LIMIT = 16_000_000;
    public SurfacePolicy {
        if (identity == null || identity.length() > 128 || compressed == null || compressed.length() > LIMIT * 4 / 3 + 4)
            throw new IllegalArgumentException("Surface policy bounds");
    }
    public static SurfacePolicy of(BiomeScatter scatter) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
                out.writeInt(scatter.entries().size());
                for (var biome : scatter.entries().entrySet()) {
                    out.writeUTF(biome.getKey().unwrapKey().orElseThrow().location().toString());
                    out.writeInt(biome.getValue().size());
                    for (var entry : biome.getValue()) {
                        out.writeUTF(entry.id());
                        out.writeUTF(BuiltInRegistries.FEATURE.getKey(entry.feature()).toString());
                        out.writeDouble(entry.density());
                        out.writeInt(entry.variants().size());
                        for (var template : entry.variants()) template.write(out);
                    }
                }
            }
            if (bytes.size() > LIMIT) throw new IllegalArgumentException("Surface policy exceeds packet budget");
            return new SurfacePolicy(scatter.identity(), Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeUtf(identity,128); b.writeByteArray(Base64.getDecoder().decode(compressed));
    }
    public static SurfacePolicy read(RegistryFriendlyByteBuf b) {
        return new SurfacePolicy(b.readUtf(128),Base64.getEncoder().encodeToString(b.readByteArray(LIMIT)));
    }
    public BiomeScatter bind(PlanetTerrain terrain, ResourceLocation fixed) {
        try {
            byte[] raw;
            try (var zip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(compressed)))) {
                raw = zip.readNBytes(RAW_LIMIT+1);
                if (raw.length > RAW_LIMIT) throw new IOException("Surface policy expansion limit");
            }
            var entries = new LinkedHashMap<ResourceLocation,List<BiomeScatter.Entry>>();
            try (var in = new DataInputStream(new ByteArrayInputStream(raw))) {
                int count = count(in,256);
                for (int i=0;i<count;i++) {
                    var biome = ResourceLocation.parse(in.readUTF());
                    var list = new ArrayList<BiomeScatter.Entry>();
                    int n = count(in,256);
                    for (int j=0;j<n;j++) {
                        String id = in.readUTF();
                        var feature = BuiltInRegistries.FEATURE.get(ResourceLocation.parse(in.readUTF()));
                        double density = in.readDouble();
                        if (!VisibleFeatures.TYPES.contains(feature) || !Double.isFinite(density) || density < 0)
                            throw new IOException("Invalid scatter entry");
                        int variants = count(in,8);
                        if (variants == 0) throw new IOException("Empty variants");
                        var templates = new ArrayList<FeatureSandbox.Template>();
                        for (int k=0;k<variants;k++) templates.add(FeatureSandbox.Template.read(in));
                        list.add(new BiomeScatter.Entry(id,feature,density,templates));
                    }
                    if (entries.put(biome,List.copyOf(list)) != null) throw new IOException("Duplicate biome");
                }
                if (in.read() != -1) throw new IOException("Trailing policy bytes");
            }
            return new BiomeScatter(terrain,entries,identity,(face,u,v,ground) -> {
                // Match BiomeSource quart centres, including the last quart at face borders.
                int x=u/4*4+2,z=v/4*4+2;
                return PlanetTint.biome(terrain,fixed,face.ordinal(),x,z).location();
            });
        } catch (IOException | IllegalArgumentException e) { throw new IllegalArgumentException("Invalid surface policy",e); }
    }
    private static int count(DataInputStream in,int max) throws IOException {
        int n=in.readInt(); if(n<0 || n>max) throw new IOException("Policy count"); return n;
    }
}
