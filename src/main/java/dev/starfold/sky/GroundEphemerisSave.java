package dev.starfold.sky;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundSolar;
import dev.starfold.universe.SystemSkyCodec;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.saveddata.SavedData;

/** Separate motion metadata: no changes to either block ledger's save format. */
public final class GroundEphemerisSave extends SavedData {
    public static final String FILE = "starfold_ground_ephemeris_v1";
    public GroundEphemeris.Model model;
    private long tick;

    public GroundEphemerisSave(GroundEphemeris.Model model, long tick) {
        if (model == null) throw new IllegalArgumentException("Model");
        GroundEphemeris.time(tick);
        this.model = model;
        this.tick = tick;
    }

    public long tick() {
        return tick;
    }

    public void spin(boolean enabled) {
        model = GroundEphemeris.spinning(model, enabled, tick);
        setDirty();
    }

    public void skip(long ticks) {
        if (ticks < 0 || tick > GroundEphemeris.MAX_TICK - ticks)
            throw new IllegalStateException("Universe clock exhausted");
        tick += ticks;
        setDirty();
    }

    public void advance() {
        if (tick >= GroundEphemeris.MAX_TICK)
            throw new IllegalStateException("Universe clock exhausted");
        tick++;
        setDirty();
    }

    public static GroundEphemerisSave fresh(boolean exists) {
        if (exists)
            throw new IllegalStateException(
                    "Existing ephemeris failed to load; refusing overwrite");
        var s = new GroundEphemerisSave(GroundSolar.defaults(), 0);
        s.setDirty();
        return s;
    }

    public static CompoundTag modelTag(GroundEphemeris.Model model) {
        return (CompoundTag) SystemSkyCodec.MODEL.encodeStart(NbtOps.INSTANCE, model).getOrThrow();
    }

    public static GroundEphemeris.Model model(CompoundTag tag) {
        return SystemSkyCodec.MODEL
                .parse(NbtOps.INSTANCE, tag)
                .getOrThrow(IllegalArgumentException::new);
    }

    public static GroundEphemerisSave load(CompoundTag tag, HolderLookup.Provider lookup) {
        if (!tag.contains("format", 3) || tag.getInt("format") != SystemSkyCodec.FORMAT)
            throw new IllegalArgumentException(
                    "Unsupported ephemeris format; expected 5 (old saves are not supported)");
        if (!tag.contains("tick", 4) || !tag.contains("motion", 10))
            throw new IllegalArgumentException("Missing ephemeris tick or motion");
        return new GroundEphemerisSave(model(tag.getCompound("motion")), tag.getLong("tick"));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putInt("format", SystemSkyCodec.FORMAT);
        tag.putLong("tick", tick);
        tag.put("motion", modelTag(model));
        return tag;
    }
}
