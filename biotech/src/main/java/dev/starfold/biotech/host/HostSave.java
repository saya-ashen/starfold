package dev.starfold.biotech.host;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One natural encounter per planet slot in the planet level. */
public final class HostSave extends SavedData {
    public static final String FILE = "starfold_biotech_host";
    static final long RESPAWN_DELAY_TICKS = 7L * 24_000;
    private static final int LEGACY = -1;

    private final Map<Integer, Encounter> encounters = new HashMap<>();

    public static HostSave get(ServerLevel level) {
        var storage = level.getServer().overworld().getDataStorage();
        return storage.computeIfAbsent(
                new Factory<>(
                        () -> fresh(((dev.starfold.mixin.SavedDataFileAccess) storage)
                                .starfold$spaceDataFile(FILE).exists()),
                        HostSave::load,
                        null),
                FILE);
    }

    static HostSave fresh(boolean exists) {
        if (exists) throw new IllegalStateException("Existing Host encounter save failed to load");
        return new HostSave();
    }

    boolean canSpawn(int planet, long gameTime) {
        var encounter = encounters.get(planet);
        return encounter == null || encounter.defeated && gameTime >= encounter.respawnAt;
    }

    UUID activeHost(int planet) {
        var encounter = encounters.get(planet);
        return encounter == null || encounter.defeated ? null : encounter.host;
    }

    Long lastChunk(int planet) {
        return encounters.get(planet).lastChunk;
    }

    boolean track(int planet, UUID host, long chunk) {
        var legacy = encounters.get(LEGACY);
        if (legacy != null && host.equals(legacy.host) && !legacy.defeated) {
            encounters.remove(LEGACY);
            if (!encounters.containsKey(planet)) encounters.put(planet, legacy);
            setDirty();
        }
        if (!host.equals(activeHost(planet))) return false;
        var encounter = encounters.get(planet);
        if (encounter.lastChunk == null || encounter.lastChunk != chunk) {
            encounter.lastChunk = chunk;
            setDirty();
        }
        return true;
    }

    UUID legacyHost() {
        return activeHost(LEGACY);
    }

    boolean active(UUID id) {
        var encounter = find(id);
        return encounter != null && !encounter.defeated;
    }

    int livingSacs(UUID id) {
        var encounter = find(id);
        return encounter == null ? 0 : encounter.sacs.size();
    }

    void spawn(int planet, UUID id, Set<UUID> living, long chunk) {
        var encounter = new Encounter(id);
        encounter.sacs.addAll(living);
        encounter.lastChunk = chunk;
        encounters.put(planet, encounter);
        setDirty();
    }

    void sacDied(UUID owner, UUID sac) {
        var encounter = find(owner);
        if (encounter != null && encounter.sacs.remove(sac)) setDirty();
    }

    void defeat(UUID id, long gameTime) {
        var encounter = find(id);
        if (encounter == null || encounter.defeated) return;
        encounter.defeated = true;
        encounter.respawnAt = gameTime + RESPAWN_DELAY_TICKS;
        encounter.sacs.clear();
        setDirty();
    }

    public static HostSave load(CompoundTag tag, HolderLookup.Provider lookup) {
        var data = new HostSave();
        if (!tag.contains("Encounters")) {
            var legacy = readEncounter(tag);
            if (legacy != null && !legacy.defeated) data.encounters.put(LEGACY, legacy);
            return data;
        }
        for (var entry : checkedList(tag, "Encounters", 10)) {
            var value = (CompoundTag) entry;
            var encounter = readEncounter(value);
            int planet = value.getInt("Planet");
            if (!value.contains("Planet", 3) || planet < LEGACY || encounter == null
                    || data.find(encounter.host) != null
                    || data.encounters.putIfAbsent(planet, encounter) != null)
                throw new IllegalArgumentException("Invalid or duplicate Host encounter");
        }
        return data;
    }

    private static Encounter readEncounter(CompoundTag tag) {
        if (!tag.contains("Defeated", 1) || tag.contains("Host") && !tag.hasUUID("Host")
                || tag.contains("RespawnAt") && !tag.contains("RespawnAt", 4)
                || tag.contains("LastChunk") && !tag.contains("LastChunk", 4))
            throw new IllegalArgumentException("Invalid Host encounter save");
        var sacs = checkedList(tag, "Sacs", 11);
        boolean defeated = tag.getBoolean("Defeated");
        if (sacs.size() > HostRules.SAC_COUNT || defeated && !sacs.isEmpty()
                || !tag.hasUUID("Host") && (defeated || !sacs.isEmpty()))
            throw new IllegalArgumentException("Inconsistent Host encounter save");
        if (!tag.hasUUID("Host")) return null;
        var encounter = new Encounter(tag.getUUID("Host"));
        encounter.defeated = defeated;
        encounter.respawnAt = tag.getLong("RespawnAt");
        if (tag.contains("LastChunk")) encounter.lastChunk = tag.getLong("LastChunk");
        for (var sac : sacs)
            if (!encounter.sacs.add(NbtUtils.loadUUID(sac)))
                throw new IllegalArgumentException("Duplicate Host sac");
        return encounter;
    }

    private static ListTag checkedList(CompoundTag tag, String name, int type) {
        var list = tag.getList(name, type);
        if (!(tag.get(name) instanceof ListTag raw) || raw.size() != list.size())
            throw new IllegalArgumentException("Invalid Host encounter list: " + name);
        return list;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        var list = new ListTag();
        encounters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var value = new CompoundTag();
            value.putInt("Planet", entry.getKey());
            var encounter = entry.getValue();
            value.putUUID("Host", encounter.host);
            value.putBoolean("Defeated", encounter.defeated);
            value.putLong("RespawnAt", encounter.respawnAt);
            if (encounter.lastChunk != null) value.putLong("LastChunk", encounter.lastChunk);
            var sacs = new ListTag();
            encounter.sacs.stream().sorted().forEach(id -> sacs.add(NbtUtils.createUUID(id)));
            value.put("Sacs", sacs);
            list.add(value);
        });
        tag.put("Encounters", list);
        return tag;
    }

    private Encounter find(UUID host) {
        for (var encounter : encounters.values())
            if (host.equals(encounter.host)) return encounter;
        return null;
    }

    private static final class Encounter {
        private final UUID host;
        private boolean defeated;
        private long respawnAt;
        private Long lastChunk;
        private final Set<UUID> sacs = new HashSet<>();

        private Encounter(UUID host) {
            this.host = host;
        }
    }
}
