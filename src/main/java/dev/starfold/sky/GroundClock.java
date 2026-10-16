package dev.starfold.sky;

import dev.starfold.chart.GroundAir;
import dev.starfold.chart.GroundSpace;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetSystem;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Celestial metadata only. Buildings, inventory and block entities stay in native chunk saves. */
public final class GroundClock extends SavedData {
    public static final String FILE = "starfold_native_ground_clock_v1";
    public final GroundEphemerisSave clock;
    public GroundSpace.Config mask = GroundSpace.DEFAULT_MASK;
    public GroundAir.Config air = GroundAir.DEFAULT;

    public GroundClock(GroundEphemerisSave clock) {
        this.clock = clock;
    }

    public static GroundClock fresh(boolean exists) {
        if (exists)
            throw new IllegalStateException(
                    "Existing native clock failed to load; refusing overwrite");
        var value = new GroundClock(GroundEphemerisSave.fresh(false));
        value.setDirty();
        return value;
    }

    public static GroundClock load(CompoundTag tag, HolderLookup.Provider lookup) {
        var value = new GroundClock(GroundEphemerisSave.load(tag, lookup));
        if (tag.contains("native_mask")) {
            if (!tag.contains("native_mask", 10))
                throw new IllegalArgumentException("Native mask type");
            var m = tag.getCompound("native_mask");
            if (!m.contains("core", 3) || !m.contains("height", 3))
                throw new IllegalArgumentException("Native mask fields");
            value.mask = new GroundSpace.Config(m.getInt("core"), m.getInt("height"));
        }
        if (tag.contains("native_air")) {
            if (!tag.contains("native_air", 10))
                throw new IllegalArgumentException("Native air type");
            var a = tag.getCompound("native_air");
            if (!a.contains("start", 3) || !a.contains("end", 3))
                throw new IllegalArgumentException("Native air fields");
            value.air = new GroundAir.Config(a.getInt("start"), a.getInt("end"));
        }
        return value;
    }

    public static GroundClock get(ServerLevel level) {
        if (!PlanetLevels.is(level)) throw new IllegalArgumentException("Dimension");
        var storage = level.getDataStorage();
        var value =
                storage.computeIfAbsent(
                        new Factory<>(
                                () -> {
                                    fresh(
                                            ((dev.starfold.mixin.SavedDataFileAccess) storage)
                                                    .starfold$spaceDataFile(FILE)
                                                    .exists());
                                    // Generated systems begin at sunrise; authored phases start at
                                    // zero.
                                    var model = PlanetSystem.model(level);
                                    var saved =
                                            level.getChunkSource().getGenerator()
                                                            instanceof PlanetSystem.Saved s
                                                    ? s
                                                    : null;
                                    var begun =
                                            new GroundClock(
                                                    new GroundEphemerisSave(
                                                            model,
                                                            saved != null && saved.startsWithShip()
                                                                    ? GroundDaylight.midMorning(
                                                                            model,
                                                                            PlanetSystem.shape(
                                                                                    level, 0))
                                                                    : saved != null
                                                                                    && saved
                                                                                            .authored()
                                                                            ? 0
                                                                            : GroundDaylight
                                                                                    .sunrise(
                                                                                            model,
                                                                                            PlanetSystem
                                                                                                    .shape(
                                                                                                            level,
                                                                                                            0))));
                                    begun.setDirty();
                                    return begun;
                                },
                                GroundClock::load,
                                null),
                        FILE);
        if (value.clock.model.count() < PlanetSystem.count(level)
                && level.getChunkSource().getGenerator() instanceof PlanetSystem.Saved grown
                && grown.beyond(level)) {
            value.clock.model = PlanetSystem.model(level);
            value.setDirty();
        }
        if (value.clock.model.count() != PlanetSystem.count(level))
            throw new IllegalStateException("Saved native system/clock mismatch; refusing upgrade");
        return value;
    }

    public void advance() {
        clock.advance();
        setDirty();
    }

    public void skip(long ticks) {
        clock.skip(ticks);
        setDirty();
    }

    public void spin(boolean on) {
        clock.spin(on);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        var m = new CompoundTag();
        m.putInt("core", mask.core());
        m.putInt("height", mask.height());
        tag.put("native_mask", m);
        var a = new CompoundTag();
        a.putInt("start", air.start());
        a.putInt("end", air.end());
        tag.put("native_air", a);
        return clock.save(tag, lookup);
    }
}
