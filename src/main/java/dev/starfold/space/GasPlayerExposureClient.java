package dev.starfold.space;

import dev.starfold.network.StarfoldNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;

/** Ship-independent personal pressure overlay. Initialize once on the client, without a hull gate. */
public final class GasPlayerExposureClient {
    private static final GasPlayerExposureCache cache = new GasPlayerExposureCache();
    private static long clientTick;
    private static ClientLevel level;
    private static LocalPlayer player;

    public static void initialize() {
        StarfoldNetwork.clientReceiver(GasPlayerExposurePacket.TYPE, (packet, context) -> {
            reconcile();
            cache.receive(packet, SpaceClientHost.get().ephemeris(), clientTick);
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) -> {
                    if (clientTick < Long.MAX_VALUE) clientTick++;
                    reconcile();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) -> {
                    cache.reset();
                    level = null;
                    player = null;
                    clientTick = 0;
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderGuiEvent.Post event) ->
                        render(event.getGuiGraphics()));
    }

    private static void reconcile() {
        var mc = Minecraft.getInstance();
        if (level != mc.level || player != mc.player) {
            cache.invalidate();
            level = mc.level;
            player = mc.player;
        }
        long session = SpaceClient.session();
        cache.focus(level == null || player == null || session == 0 ? null
                : new GasPlayerExposureCache.Identity(level.dimension().location(), session));
    }

    private static void render(GuiGraphics graphics) {
        var mc = Minecraft.getInstance();
        reconcile();
        if (mc.options.hideGui || mc.level == null || mc.player == null) return;
        var packet = cache.current(clientTick);
        if (packet == null
                || !GasPlayerExposureCache.compatible(packet, SpaceClientHost.get().ephemeris()))
            return;
        int width = mc.getWindow().getGuiScaledWidth();
        int y = Math.max(6, mc.getWindow().getGuiScaledHeight() - 140);
        for (var line : GasPlayerExposureHud.lines(packet)) {
            String text = I18n.get(line.key(), line.arguments().toArray());
            text = mc.font.plainSubstrByWidth(text, Math.max(0, width - 16));
            graphics.drawString(mc.font, text, (width - mc.font.width(text)) / 2,
                    y, line.color(), true);
            y += mc.font.lineHeight + 1;
        }
    }

    private GasPlayerExposureClient() {}
}
