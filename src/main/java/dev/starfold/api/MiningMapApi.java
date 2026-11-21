package dev.starfold.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Optional, plugin-neutral bridge for owner-private survey snapshots. No world sampling lives here.
 * Client access is on the client thread. Planet slots are persisted storage identities; body is the
 * current catalog index resolved by the server when syncing (-1 if unavailable).
 */
public final class MiningMapApi {
    public static final int MAX_SITES = 32;
    public static final int MAX_DEPTH = 512;
    public static final int AREA = 5;
    public static final int MAX_ORES = 64;

    /** Counts of blocks actually examined, with inclusive depths below the platform (1..512). */
    public record Ore(String blockId, int count, int minDepth, int maxDepth) {
        public Ore {
            if (blockId == null
                    || blockId.length() > 256
                    || !blockId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                    || count < 1
                    || count > AREA * AREA * MAX_DEPTH
                    || minDepth < 1
                    || maxDepth < minDepth
                    || maxDepth > MAX_DEPTH)
                throw new IllegalArgumentException("Invalid survey ore");
        }
    }

    /**
     * completedAt is Unix epoch milliseconds. scannedBlocks includes examined air/non-ore blocks;
     * scannedDepth is the deepest examined layer. Empty ores means none found in that scan only.
     * markerName is empty when an ordinary private waypoint could not be created or no longer
     * exists.
     */
    public record Survey(
            String dimension,
            int planetSlot,
            int body,
            int face,
            double u,
            double v,
            int x,
            int y,
            int z,
            long completedAt,
            int scannedDepth,
            int scannedBlocks,
            String markerName,
            List<Ore> ores) {
        public Survey {
            if (dimension == null
                    || dimension.length() > 256
                    || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                    || planetSlot < 0
                    || body < -1
                    || face < 0
                    || face >= 6
                    || !Double.isFinite(u)
                    || !Double.isFinite(v)
                    || u < 0
                    || v < 0
                    || completedAt < 0
                    || scannedDepth < 1
                    || scannedDepth > MAX_DEPTH
                    || scannedBlocks < 1
                    || scannedBlocks > AREA * AREA * scannedDepth
                    || markerName == null
                    || !markerName.isEmpty() && !markerName.matches("[\\p{L}\\p{N}_-]{1,32}"))
                throw new IllegalArgumentException("Invalid survey");
            ores = List.copyOf(ores);
            if (ores.size() > MAX_ORES)
                throw new IllegalArgumentException("Too many survey ore types");
            var ids = new HashSet<String>();
            int total = 0;
            for (var ore : ores) {
                if (!ids.add(ore.blockId()) || ore.maxDepth() > scannedDepth)
                    throw new IllegalArgumentException(
                            "Invalid survey ore depths or duplicate type");
                total += ore.count();
            }
            if (total > scannedBlocks)
                throw new IllegalArgumentException("Survey count exceeds examined blocks");
        }
    }

    private static List<Survey> clientSurveys = List.of();
    private static Supplier<List<Survey>> provider = () -> clientSurveys;

    /** Empty when no optional survey producer is installed or before the owner's sync arrives. */
    public static List<Survey> surveys() {
        return List.copyOf(provider.get());
    }

    /** Optional producers may supply their own client-thread snapshot store. */
    public static void registerClientProvider(Supplier<List<Survey>> source) {
        provider = Objects.requireNonNull(source);
    }

    /** Replaces the default client store; this is display data, never server authority. */
    public static void acceptClientSurveys(List<Survey> surveys) {
        if (surveys.size() > MAX_SITES) throw new IllegalArgumentException("Too many surveys");
        clientSurveys = List.copyOf(surveys);
    }

    public static void clearClientSurveys() {
        clientSurveys = List.of();
    }

    private MiningMapApi() {}
}
