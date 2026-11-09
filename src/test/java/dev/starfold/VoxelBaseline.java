package dev.starfold;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Exact frozen b112 classes, isolated test resources; no dependency on a previous build directory. */
public final class VoxelBaseline {
    private VoxelBaseline() {}

    public static InputStream open(String path) throws Exception {
        var resource =
                VoxelBaseline.class.getResourceAsStream("/ca-voxel-b11239ce/" + path + ".bin");
        if (resource == null) throw new IllegalStateException("Missing baseline " + path);
        byte[] bytes;
        try (resource) {
            bytes = resource.readAllBytes();
        }
        try (var manifest =
                VoxelBaseline.class.getResourceAsStream("/ca-voxel-b11239ce/manifest.json")) {
            var json =
                    com.google.gson.JsonParser.parseReader(
                                    new java.io.InputStreamReader(
                                            manifest, java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
            String expected = json.getAsJsonObject("class_sha256").get(path).getAsString();
            String actual =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equals(expected))
                throw new IllegalStateException("Baseline hash mismatch " + path);
        }
        return new java.io.ByteArrayInputStream(bytes);
    }
}
