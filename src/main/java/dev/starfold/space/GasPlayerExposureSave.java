package dev.starfold.space;

import dev.starfold.chart.GasPlayerExposureValues;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;

/** Lives in Entity's NeoForgeData, not the celestial catalog or a ship record. */
public final class GasPlayerExposureSave {
    public static final String KEY = "starfold:gas_player_exposure";

    public static GasPlayerExposureValues read(CompoundTag persistent) {
        if (!persistent.contains(KEY)) return GasPlayerExposureValues.initial();
        if (!persistent.contains(KEY, Tag.TAG_COMPOUND))
            return GasPlayerExposureValues.fromFields(Map.of("invalid", true));
        var record = persistent.getCompound(KEY);
        var fields = new HashMap<String, Object>();
        // A present empty/partial record is corrupt, not an uninitialized player.
        for (String key : GasPlayerExposureValues.initial().toFields().keySet())
            fields.put(key, record.contains(key, Tag.TAG_INT) ? record.getInt(key) : "invalid");
        return GasPlayerExposureValues.fromFields(fields);
    }

    public static void write(CompoundTag persistent, GasPlayerExposureValues values) {
        var record = new CompoundTag();
        values.toFields().forEach(record::putInt);
        persistent.put(KEY, record);
    }

    private GasPlayerExposureSave() {}
}
