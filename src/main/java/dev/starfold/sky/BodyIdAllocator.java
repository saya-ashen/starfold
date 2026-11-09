package dev.starfold.sky;

import dev.starfold.chart.BodyId;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetSystem;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** World-owned lifetime counters. An empty region is still reserved; no counter ever rewinds. */
public final class BodyIdAllocator extends SavedData {
    public static final String FILE = "starfold_body_ids";
    private final Map<Integer, Integer> nextIndices = new TreeMap<>();
    private int nextRegion = 1;

    public BodyIdAllocator() {
        nextIndices.put(0, 0);
    }

    /** Reserve authored identities before any runtime allocation. */
    public void reserve(List<GroundEphemeris.Body> bodies) {
        for (var body : bodies) {
            var id = body.id();
            int next = Math.addExact(id.index(), 1);
            if (next > nextIndices.getOrDefault(id.region(), -1)) {
                nextIndices.put(id.region(), next);
                nextRegion = Math.max(nextRegion, Math.addExact(id.region(), 1));
                setDirty();
            }
        }
    }

    public int allocateRegion() {
        if (nextRegion == Integer.MAX_VALUE)
            throw new IllegalStateException("Body regions exhausted");
        int region = nextRegion++;
        nextIndices.put(region, 0);
        setDirty();
        return region;
    }

    /** Allocate in any reserved region, on the server thread, even after discovery has ended. */
    public BodyId allocate(int region) {
        Integer next = nextIndices.get(region);
        if (next == null) throw new IllegalArgumentException("Unknown body region: " + region);
        if (next == Integer.MAX_VALUE) throw new IllegalStateException("Body indices exhausted");
        nextIndices.put(region, next + 1);
        setDirty();
        return new BodyId(region, next);
    }

    public void requireRegion(int region) {
        if (!nextIndices.containsKey(region))
            throw new IllegalArgumentException("Unknown body region: " + region);
    }

    public void requireAllocated(BodyId id) {
        requireRegion(id.region());
        if (id.index() >= nextIndices.get(id.region()))
            throw new IllegalArgumentException("Body identity outside allocated counter");
    }

    /** The same allocator in worlds with or without a universe layout. */
    public static BodyIdAllocator get(ServerLevel level) {
        if (!PlanetLevels.is(level)) throw new IllegalArgumentException("Dimension");
        return get(level, PlanetSystem.model(level));
    }

    /** Load before Universe generation; callers supply only the authored home identities. */
    public static BodyIdAllocator get(ServerLevel level, GroundEphemeris.Model home) {
        var storage = level.getDataStorage();
        var ids =
                storage.computeIfAbsent(
                        new Factory<>(
                                () -> {
                                    if (((dev.starfold.mixin.SavedDataFileAccess) storage)
                                            .starfold$spaceDataFile(FILE)
                                            .exists())
                                        throw new IllegalStateException(
                                                "Existing body allocator failed to load; refusing overwrite");
                                    if (((dev.starfold.mixin.SavedDataFileAccess) storage)
                                            .starfold$spaceDataFile(GroundClock.FILE)
                                            .exists())
                                        throw new IllegalStateException(
                                                "Existing world is missing body counters; refusing reset");
                                    var made = new BodyIdAllocator();
                                    made.reserve(home.bodies());
                                    made.setDirty();
                                    return made;
                                },
                                BodyIdAllocator::load,
                                null),
                        FILE);
        for (var body : home.bodies()) ids.requireAllocated(body.id());
        return ids;
    }

    public static BodyIdAllocator load(CompoundTag tag, HolderLookup.Provider lookup) {
        if (!tag.contains("format", 3) || tag.getInt("format") != 1)
            throw new IllegalArgumentException("Unsupported body allocator format; expected 1");
        if (!tag.contains("next_region", 3)
                || tag.getInt("next_region") < 1
                || !tag.contains("next_indices", 9))
            throw new IllegalArgumentException("Missing body counters");
        var ids = new BodyIdAllocator();
        ids.nextIndices.clear();
        ids.nextRegion = tag.getInt("next_region");
        for (var raw : (ListTag) tag.get("next_indices")) {
            if (!(raw instanceof CompoundTag entry)
                    || !entry.contains("region", 3)
                    || !entry.contains("next_index", 3))
                throw new IllegalArgumentException("Body counter fields");
            int region = entry.getInt("region"), next = entry.getInt("next_index");
            if (region < 0
                    || region >= ids.nextRegion
                    || next < 0
                    || ids.nextIndices.putIfAbsent(region, next) != null)
                throw new IllegalArgumentException("Invalid body counter");
        }
        if (!ids.nextIndices.containsKey(0))
            throw new IllegalArgumentException("Missing home counter");
        return ids;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putInt("format", 1);
        tag.putInt("next_region", nextRegion);
        var entries = new ListTag();
        nextIndices.forEach(
                (region, next) -> {
                    var entry = new CompoundTag();
                    entry.putInt("region", region);
                    entry.putInt("next_index", next);
                    entries.add(entry);
                });
        tag.put("next_indices", entries);
        return tag;
    }
}
