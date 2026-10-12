package dev.starfold.probe;

import dev.starfold.planet.PlanetSystem;

/** Opt-in per-tick stream census; no sampling thread or production logging. */
public final class RelightProbe {
    public static final boolean ENABLED = Boolean.getBoolean("starfold.relightProbe");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("starfold-probe");

    public static void tick(int tick, long start) {
        if (ENABLED)
            LOG.info(
                    "PROBE RELIGHT_TICK wall {} tick {} ns {}",
                    System.currentTimeMillis(),
                    tick,
                    System.nanoTime() - start);
    }

    public static void stream(
            net.minecraft.server.level.ServerPlayer player,
            int queued,
            int processed,
            long nanos,
            int sent,
            int pending,
            int remaining,
            int dirty,
            int lit,
            long oldestAge) {
        if (ENABLED)
            LOG.info(
                    "PROBE RELIGHT wall {} game {} planet {} y {} queued {} processed {} ns {} sent {} pending {} remaining {} dirty {} lit {} oldestAge {}",
                    System.currentTimeMillis(),
                    player.level().getGameTime(),
                    PlanetSystem.planet(player),
                    player.getY(),
                    queued,
                    processed,
                    nanos,
                    sent,
                    pending,
                    remaining,
                    dirty,
                    lit,
                    oldestAge);
    }

    private static long lastChange = Long.MIN_VALUE;

    public static void changed(
            net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos owner) {
        if (!ENABLED || level.getGameTime() % 20 != 0 || lastChange == level.getGameTime()) return;
        lastChange = level.getGameTime();
        LOG.info(
                "PROBE RELIGHT_CHANGE game {} pos {} state {} stack {}",
                lastChange,
                owner,
                level.getBlockState(owner),
                java.util.Arrays.toString(Thread.currentThread().getStackTrace()));
    }

    public static void mismatch(
            int planet, long image, boolean identity, byte[] emitted, byte[] reference) {
        if (!ENABLED) return;
        LOG.info(
                "PROBE RELIGHT_MISMATCH planet {} image {} identity {} emittedBytes {} referenceBytes {} firstByte {}",
                planet,
                new net.minecraft.world.level.ChunkPos(image),
                identity,
                emitted.length,
                reference.length,
                java.util.Arrays.mismatch(emitted, reference));
        try {
            var dir = java.nio.file.Path.of("../build/space/relight1-light");
            java.nio.file.Files.createDirectories(dir);
            var name = planet + "-" + image;
            java.nio.file.Files.write(dir.resolve(name + "-emitted.bin"), emitted);
            java.nio.file.Files.write(dir.resolve(name + "-reference.bin"), reference);
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private RelightProbe() {}
}
