package dev.starfold.ground;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class WorldSessionSave extends SavedData {
    boolean created, complete, folded, crust;
    final java.util.Set<String> blockedBlocks = new java.util.TreeSet<>();

    private record Home(
            String dimension,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            boolean flying) {}

    private final Map<UUID, Home> homes = new HashMap<>();

    public static WorldSessionSave get(ServerLevel level) {
        return level.getDataStorage()
                .computeIfAbsent(
                        new Factory<>(WorldSessionSave::new, WorldSessionSave::load, null),
                        "starfold_v1");
    }

    public void remember(ServerPlayer player) {
        homes.put(
                player.getUUID(),
                new Home(
                        player.level().dimension().location().toString(),
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        player.getYRot(),
                        player.getXRot(),
                        player.getAbilities().flying));
        setDirty();
    }

    public boolean restore(ServerPlayer player) {
        Home home = homes.get(player.getUUID());
        if (home == null) return false;
        ResourceLocation id = ResourceLocation.tryParse(home.dimension);
        if (id == null) return false;
        ServerLevel level =
                player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) return false;
        player.teleportTo(level, home.x, home.y, home.z, home.yaw, home.pitch);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        player.getAbilities().flying = player.getAbilities().mayfly && home.flying;
        player.onUpdateAbilities();
        homes.remove(player.getUUID());
        setDirty();
        return true;
    }

    private static WorldSessionSave load(CompoundTag tag, HolderLookup.Provider lookup) {
        WorldSessionSave result = new WorldSessionSave();
        result.created = tag.getBoolean("created");
        result.complete = tag.getBoolean("complete");
        result.folded = tag.getBoolean("folded");
        result.crust = tag.getBoolean("crust");
        for (var entry : tag.getList("blockedBlocks", 8))
            result.blockedBlocks.add(entry.getAsString());
        for (var entry : tag.getList("homes", 10)) {
            CompoundTag t = (CompoundTag) entry;
            if (!t.hasUUID("player")) continue;
            double x = t.getDouble("x"), y = t.getDouble("y"), z = t.getDouble("z");
            float yaw = t.getFloat("yaw"), pitch = t.getFloat("pitch");
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(z)
                    || !Float.isFinite(yaw)
                    || !Float.isFinite(pitch)) continue;
            result.homes.put(
                    t.getUUID("player"),
                    new Home(
                            t.getString("dimension"), x, y, z, yaw, pitch, t.getBoolean("flying")));
        }
        return result;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putBoolean("created", created);
        tag.putBoolean("complete", complete);
        tag.putBoolean("folded", folded);
        tag.putBoolean("crust", crust);
        ListTag blocked = new ListTag();
        blockedBlocks.forEach(id -> blocked.add(net.minecraft.nbt.StringTag.valueOf(id)));
        tag.put("blockedBlocks", blocked);
        ListTag list = new ListTag();
        homes.forEach(
                (id, home) -> {
                    CompoundTag t = new CompoundTag();
                    t.putUUID("player", id);
                    t.putString("dimension", home.dimension);
                    t.putDouble("x", home.x);
                    t.putDouble("y", home.y);
                    t.putDouble("z", home.z);
                    t.putFloat("yaw", home.yaw);
                    t.putFloat("pitch", home.pitch);
                    t.putBoolean("flying", home.flying);
                    list.add(t);
                });
        tag.put("homes", list);
        return tag;
    }
}
