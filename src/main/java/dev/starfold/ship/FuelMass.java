package dev.starfold.ship;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Per-tank deltas, sampled at most once a second; never rebuilds the hull's mass tracker. */
final class FuelMass {
    private long updated = Long.MIN_VALUE;
    private Map<BlockPos, Double> applied = Map.of();

    boolean due(long tick) {
        return updated == Long.MIN_VALUE || tick - updated >= 20;
    }

    double total() {
        return applied.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    void update(long tick, Map<BlockPos, Double> sampled, BiConsumer<BlockPos, Double> add) {
        if (!due(tick)) return;
        var deltas = new HashMap<>(sampled);
        applied.forEach((pos, mass) -> deltas.merge(pos, -mass, Double::sum));
        deltas.forEach((pos, mass) -> {
            if (mass != 0) add.accept(pos, mass);
        });
        applied = Map.copyOf(sampled);
        updated = tick;
    }
}
