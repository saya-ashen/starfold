package dev.starfold.biotech.host;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Stored in the overworld: one natural encounter across all dimensions and Flesh instances. */
public final class HostSave extends SavedData {
    public static final String FILE = "starfold_biotech_host";
    private UUID host;
    private boolean defeated;
    private final Set<UUID> sacs = new HashSet<>();

    public static HostSave get(ServerLevel level) {
        var storage = level.getServer().overworld().getDataStorage();
        return storage.computeIfAbsent(new Factory<>(
                () -> fresh(((dev.starfold.mixin.SavedDataFileAccess) storage).starfold$spaceDataFile(FILE).exists()),
                HostSave::load, null), FILE);
    }

    static HostSave fresh(boolean exists) {
        if (exists) throw new IllegalStateException("Existing Host save failed to load; refusing a second encounter");
        return new HostSave();
    }

    public boolean spawned() { return host != null; }
    public boolean defeated() { return defeated; }
    public boolean owns(UUID id) { return id.equals(host); }
    public int livingSacs() { return sacs.size(); }

    public void spawn(UUID id, Set<UUID> living) {
        host = id;
        sacs.clear();
        sacs.addAll(living);
        setDirty();
    }

    public void sacDied(UUID owner, UUID sac) {
        if (owns(owner) && sacs.remove(sac)) setDirty();
    }

    public void defeat(UUID id) {
        if (!owns(id)) return;
        defeated = true;
        sacs.clear();
        setDirty();
    }

    public static HostSave load(CompoundTag tag, HolderLookup.Provider lookup) {
        if (!tag.contains("Defeated", 1) || !tag.contains("Sacs", 9)
                || tag.contains("Host") && !tag.hasUUID("Host"))
            throw new IllegalArgumentException("Invalid Host encounter save");
        var data = new HostSave();
        data.host = tag.hasUUID("Host") ? tag.getUUID("Host") : null;
        data.defeated = tag.getBoolean("Defeated");
        var list = tag.getList("Sacs", 11);
        if (list.size() != ((ListTag) tag.get("Sacs")).size() || list.size() > HostRules.SAC_COUNT)
            throw new IllegalArgumentException("Invalid Host sac list");
        for (var entry : list) {
            UUID id = NbtUtils.loadUUID(entry);
            if (!data.sacs.add(id)) throw new IllegalArgumentException("Duplicate Host sac");
        }
        if (data.host == null && (data.defeated || !data.sacs.isEmpty()) || data.defeated && !data.sacs.isEmpty())
            throw new IllegalArgumentException("Inconsistent Host encounter save");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        if (host != null) tag.putUUID("Host", host);
        tag.putBoolean("Defeated", defeated);
        var list = new ListTag();
        sacs.stream().sorted().forEach(id -> list.add(NbtUtils.createUUID(id)));
        tag.put("Sacs", list);
        return tag;
    }
}
