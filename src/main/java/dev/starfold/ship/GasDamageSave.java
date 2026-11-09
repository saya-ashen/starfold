package dev.starfold.ship;

import dev.starfold.mixin.SavedDataFileAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.UUID;

/** Only recent successful structural-write times. Never stores hull HP or performs offline damage. */
public final class GasDamageSave extends SavedData {
    public static final String FILE = "starfold_gas_damage_v1";
    private final GasDamageCooldown cooldown;

    private GasDamageSave(GasDamageCooldown cooldown) {
        this.cooldown = cooldown;
    }

    static GasDamageSave fresh(boolean exists) {
        if (exists)
            throw new IllegalStateException("Existing gas cooldown unreadable; refusing overwrite");
        return new GasDamageSave(new GasDamageCooldown());
    }

    static GasDamageSave get(ServerLevel level) {
        if (!PlanetLevels.is(level)) throw new IllegalArgumentException("Gas damage system level");
        var storage = level.getDataStorage();
        return storage.computeIfAbsent(
                new Factory<>(
                        () ->
                                fresh(
                                        ((SavedDataFileAccess) storage)
                                                .starfold$spaceDataFile(FILE)
                                                .exists()),
                        GasDamageSave::load,
                        null),
                FILE);
    }

    boolean ready(UUID hull, long tick, int interval) {
        return cooldown.ready(hull, tick, interval);
    }

    /** Transient; does not dirty the save. */
    void inherit(UUID piece, UUID source) {
        cooldown.inherit(piece, source);
    }

    void succeeded(UUID hull, long tick, int interval) {
        cooldown.succeeded(hull, tick, interval);
        setDirty();
    }

    public static GasDamageSave load(CompoundTag tag, HolderLookup.Provider lookup) {
        if (!tag.contains("format", Tag.TAG_INT)
                || tag.getInt("format") != 1
                || !tag.contains("watermark", Tag.TAG_LONG)
                || !(tag.get("successes") instanceof ListTag list)
                || list.size() > GasDamageCooldown.MAX_RECENT)
            throw new IllegalArgumentException("Gas cooldown format");
        var stamps = new ArrayList<GasDamageCooldown.Stamp>();
        for (var raw : list) {
            if (!(raw instanceof CompoundTag entry)
                    || !entry.hasUUID("hull")
                    || !entry.contains("tick", Tag.TAG_LONG))
                throw new IllegalArgumentException("Gas cooldown entry");
            stamps.add(new GasDamageCooldown.Stamp(entry.getUUID("hull"), entry.getLong("tick")));
        }
        return new GasDamageSave(
                new GasDamageCooldown(
                        new GasDamageCooldown.Snapshot(tag.getLong("watermark"), stamps)));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        // Serialize the already-observed watermark; never borrow wall time or access a hull.
        var snapshot = cooldown.snapshot();
        tag.putInt("format", 1);
        tag.putLong("watermark", snapshot.watermark());
        var list = new ListTag();
        for (var stamp : snapshot.stamps()) {
            var entry = new CompoundTag();
            entry.putUUID("hull", stamp.hull());
            entry.putLong("tick", stamp.tick());
            list.add(entry);
        }
        tag.put("successes", list);
        return tag;
    }
}
