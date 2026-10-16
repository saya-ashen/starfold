package dev.starfold.ship;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Live samples update records; losing a live handle does not itself delete an identity. */
public final class ShipObserver {
    public record Sample(
            ResourceLocation dimension, Optional<String> name, ShipRecord.State state) {}

    private final ShipSave saved;
    private final Map<UUID, Supplier<Sample>> live = new LinkedHashMap<>();
    private final Map<UUID, ShipRecord.Place> holds = new LinkedHashMap<>();

    public ShipObserver(ShipSave saved) {
        this.saved = java.util.Objects.requireNonNull(saved);
    }

    public void added(UUID uuid, Supplier<Sample> source) {
        var record = saved.find(uuid);
        if (record != null && record.state() instanceof ShipRecord.InSpace space)
            holds.putIfAbsent(uuid, space.place());
        update(uuid, source.get());
        live.put(uuid, source);
    }

    public void removed(UUID uuid, boolean destroyed) {
        live.remove(uuid);
        holds.remove(uuid);
        if (destroyed) saved.remove(uuid);
    }

    /** The server calls this once a second, after coordinate handovers. */
    public void refresh() {
        live.forEach((uuid, source) -> update(uuid, source.get()));
    }

    /** Serialization supplies a final sample before the live handle disappears. */
    public void serialized(UUID uuid, Sample sample) {
        update(uuid, sample);
    }

    /** Keep one slow-capture place; a null capture leaves a fast hull coasting without a hold. */
    public ShipRecord.Place bind(UUID uuid, boolean piloted, Supplier<ShipRecord.Place> capture) {
        if (piloted) {
            holds.remove(uuid);
            return null;
        }
        return holds.computeIfAbsent(uuid, ignored -> capture.get());
    }

    public ShipRecord.Place hold(UUID uuid) {
        return holds.get(uuid);
    }

    private void update(UUID uuid, Sample sample) {
        if (sample.state() instanceof ShipRecord.OnSlot) holds.remove(uuid);
        var record = saved.find(uuid);
        if (record == null) saved.add(uuid, sample.dimension(), sample.name(), sample.state());
        else {
            if (!record.dimension().equals(sample.dimension()))
                throw new IllegalArgumentException("Ship dimension changed");
            saved.refresh(uuid, sample.name(), sample.state());
        }
    }
}
