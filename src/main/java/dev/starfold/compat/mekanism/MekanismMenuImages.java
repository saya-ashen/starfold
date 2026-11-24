package dev.starfold.compat.mekanism;

import dev.starfold.ground.ObserverFrame;
import dev.starfold.ground.PacketMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload;

import java.nio.ByteBuffer;
import java.util.Set;

/** These Mekanism menu codecs begin their additional data with a packed block position. */
public final class MekanismMenuImages {
    private static final Set<String> TILE_MENUS = Set.of("energy_cube", "factory", "dynamic_tank");

    public static AdvancedOpenScreenPayload relabel(
            ServerPlayer player, ObserverFrame frame, AdvancedOpenScreenPayload payload) {
        var id = BuiltInRegistries.MENU.getKey(payload.menuType());
        if (id == null || !id.getNamespace().equals("mekanism")
                || !TILE_MENUS.contains(id.getPath()) || payload.additionalData().length < Long.BYTES)
            return payload;
        var owner = BlockPos.of(ByteBuffer.wrap(payload.additionalData()).getLong());
        var image = PacketMap.plane(player, frame, owner);
        if (image == null || image.equals(owner)) return payload;
        byte[] data = payload.additionalData().clone();
        ByteBuffer.wrap(data).putLong(image.asLong());
        return new AdvancedOpenScreenPayload(payload.windowId(), payload.menuType(), payload.name(), data);
    }

    private MekanismMenuImages() {}
}
