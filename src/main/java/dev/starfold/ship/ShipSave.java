package dev.starfold.ship;

import dev.starfold.mixin.SavedDataFileAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The system level's persistent ship identities and body-relative places. */
public final class ShipSave extends SavedData {
    public static final String FILE = "starfold_ships_v1";
    private static final int FORMAT = 5;
    private final Map<UUID, UUID> owners = new LinkedHashMap<>();

    /** The first actual pilot registers an unclaimed hull; later pilots cannot take it over. */
    public boolean claim(UUID ship, UUID player) {
        if (!records.containsKey(ship) || owners.putIfAbsent(ship, player) != null) return false;
        setDirty();
        return true;
    }

    public boolean owns(UUID player, UUID ship) { return player.equals(owners.get(ship)); }

    public List<ShipRecord> owned(UUID player) {
        return list().stream().filter(r -> owns(player, r.uuid())).toList();
    }
    private final Map<Integer, Integer> nextIndices = new LinkedHashMap<>();
    private final Map<UUID, ShipRecord> records = new LinkedHashMap<>();

    public List<ShipRecord> list() {
        return records.values().stream()
                .sorted(java.util.Comparator.comparingInt(r -> r.id().index()))
                .toList();
    }

    public ShipRecord find(UUID uuid) {
        return records.get(uuid);
    }

    public ShipRecord add(
            int region,
            UUID uuid,
            ResourceLocation dimension,
            Optional<String> name,
            ShipRecord.State state) {
        if (records.containsKey(uuid)) throw new IllegalArgumentException("Duplicate ship UUID");
        int nextIndex = nextIndices.getOrDefault(region, 0);
        if (nextIndex == Integer.MAX_VALUE)
            throw new IllegalStateException("Ship indices exhausted");
        var record =
                new ShipRecord(
                        new ShipId(region, nextIndex),
                        uuid,
                        dimension,
                        name,
                        state,
                        Optional.empty());
        records.put(uuid, record);
        nextIndices.put(region, nextIndex + 1);
        setDirty();
        return record;
    }

    public void refresh(UUID uuid, Optional<String> name, ShipRecord.State state) {
        var old = records.get(uuid);
        if (old == null) throw new IllegalArgumentException("Unknown ship");
        if (state instanceof ShipRecord.OnSlot slot
                && slot.place().isEmpty()
                && old.state() instanceof ShipRecord.OnSlot previous
                && slot.slot() == previous.slot()) state = previous;
        var next = new ShipRecord(old.id(), uuid, old.dimension(), name, state, old.locator());
        if (!next.equals(old)) {
            records.put(uuid, next);
            setDirty();
        }
    }

    /** A null result leaves the last written address intact. */
    public void pointerSaved(UUID uuid, ShipRecord.Locator locator) {
        if (locator == null) return;
        var old = records.get(uuid);
        if (old == null) return;
        var next =
                new ShipRecord(
                        old.id(),
                        uuid,
                        old.dimension(),
                        old.name(),
                        old.state(),
                        Optional.of(locator));
        if (!next.equals(old)) {
            records.put(uuid, next);
            setDirty();
        }
    }

    public void remove(UUID uuid) {
        owners.remove(uuid);
        if (records.remove(uuid) != null) setDirty();
    }

    public static ShipSave fresh(boolean exists) {
        if (exists)
            throw new IllegalStateException("Existing ship save unreadable; refusing overwrite");
        return new ShipSave();
    }

    public static ShipSave get(ServerLevel level) {
        if (!PlanetLevels.is(level)) throw new IllegalArgumentException("Ship system level");
        var storage = level.getDataStorage();
        return storage.computeIfAbsent(
                new Factory<>(
                        () ->
                                fresh(
                                        ((SavedDataFileAccess) storage)
                                                .starfold$spaceDataFile(FILE)
                                                .exists()),
                        ShipSave::load,
                        null),
                FILE);
    }

    public static ShipSave load(CompoundTag tag, HolderLookup.Provider lookup) {
        ShipCodec.require(tag, "format", Tag.TAG_INT);
        if (tag.getInt("format") != FORMAT)
            throw new IllegalArgumentException("Ship format; old saves are not supported");
        ShipCodec.require(tag, "next_indices", Tag.TAG_LIST);
        ShipCodec.require(tag, "records", Tag.TAG_LIST);
        ShipCodec.require(tag, "owners", Tag.TAG_LIST);
        var saved = new ShipSave();
        var counters = (ListTag) tag.get("next_indices");
        if (!counters.isEmpty() && counters.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Ship counters type");
        for (var entry : counters) {
            var counter = (CompoundTag) entry;
            ShipCodec.require(counter, "region", Tag.TAG_INT);
            ShipCodec.require(counter, "next_index", Tag.TAG_INT);
            int region = counter.getInt("region"), nextIndex = counter.getInt("next_index");
            if (region < 0
                    || nextIndex < 0
                    || saved.nextIndices.putIfAbsent(region, nextIndex) != null)
                throw new IllegalArgumentException("Ship counter");
        }
        var list = (ListTag) tag.get("records");
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Ship records type");
        var ids = new java.util.HashSet<ShipId>();
        for (var entry : list) {
            var record = ShipCodec.read((CompoundTag) entry);
            if (record.id().index() >= saved.nextIndices.getOrDefault(record.id().region(), 0)
                    || !ids.add(record.id())
                    || saved.records.putIfAbsent(record.uuid(), record) != null)
                throw new IllegalArgumentException("Duplicate ship or invalid counter");
        }
        var owners = tag.getList("owners", Tag.TAG_COMPOUND);
        if (owners.size() != ((ListTag) tag.get("owners")).size()) throw new IllegalArgumentException("Ship owners type");
        for (var entry : owners) {
            var owner = (CompoundTag) entry;
            if (!owner.hasUUID("ship") || !owner.hasUUID("player")) throw new IllegalArgumentException("Ship owner UUID");
            var ship = owner.getUUID("ship");
            if (!saved.records.containsKey(ship) || saved.owners.putIfAbsent(ship, owner.getUUID("player")) != null)
                throw new IllegalArgumentException("Ship owner");
        }
        return saved;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putInt("format", FORMAT);
        var counters = new ListTag();
        nextIndices.forEach(
                (region, nextIndex) -> {
                    var counter = new CompoundTag();
                    counter.putInt("region", region);
                    counter.putInt("next_index", nextIndex);
                    counters.add(counter);
                });
        tag.put("next_indices", counters);
        var list = new ListTag();
        for (var record : list()) list.add(ShipCodec.write(record));
        tag.put("records", list);
        var ownership = new ListTag();
        owners.forEach((ship, player) -> {
            var owner = new CompoundTag();
            owner.putUUID("ship", ship); owner.putUUID("player", player);
            ownership.add(owner);
        });
        tag.put("owners", ownership);
        return tag;
    }
}
