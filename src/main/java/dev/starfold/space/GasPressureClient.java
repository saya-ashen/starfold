package dev.starfold.space;

import dev.starfold.chart.GasOcclusion;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.ship.GasPressureCache;
import dev.starfold.ship.GasPressurePacket;
import dev.starfold.ship.HelmClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/** Read-only sampled hull pressure and lock-independent warning. Camera fog remains eye-based. */
public final class GasPressureClient {
    private static final GasPressureCache cache = new GasPressureCache();
    private static long clientTick;

    public static void initialize() {
        StarfoldNetwork.clientReceiver(
                GasPressurePacket.TYPE,
                (packet, context) -> {
                    reconcile();
                    cache.receive(packet, SpaceClientHost.get().ephemeris(), clientTick);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) -> {
                    if (clientTick < Long.MAX_VALUE) clientTick++;
                    reconcile();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) ->
                        cache.focus(null));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderGuiEvent.Post event) ->
                        warning(event.getGuiGraphics()));
    }

    private static boolean reconcile() {
        var mc = Minecraft.getInstance();
        long session = SpaceClient.session();
        if (mc.level == null || mc.player == null || session == 0) {
            cache.focus(null);
            return false;
        }
        var hull = HelmClient.body();
        dev.ryanhcode.sable.sublevel.SubLevel current =
                hull == null
                        ? dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(mc.player)
                        : hull;
        if (current == null || current.isRemoved() || current.getLevel() != mc.level) {
            cache.focus(null);
            return false;
        }
        cache.focus(
                new GasPressureCache.Identity(
                        mc.level.dimension().location(), session, current.getUniqueId()));
        return true;
    }

    private static GasPressurePacket packet() {
        if (!reconcile()) return null;
        var current = cache.current(clientTick);
        // Model replacement cannot expose old IDs/policies even before another pressure packet
        // arrives.
        return current != null
                        && GasPressureCache.compatible(current, SpaceClientHost.get().ephemeris())
                ? current
                : null;
    }

    public static CelestialNavigation.GasReading selected(int bodyIndex) {
        var current = packet();
        if (current == null || current.status() != GasPressurePacket.Status.KNOWN) return null;
        var reading =
                current.readings().stream()
                        .filter(r -> r.bodyIndex() == bodyIndex)
                        .findFirst()
                        .orElse(null);
        if (reading == null) return null;
        var policy = SpaceClientHost.get().ephemeris().catalogBody(bodyIndex).gas().orElseThrow();
        return new CelestialNavigation.GasReading(
                reading.depth(),
                reading.pressure(),
                Math.max(0, policy.maxDepth() - reading.depth()));
    }

    private static void warning(net.minecraft.client.gui.GuiGraphics graphics) {
        var mc = Minecraft.getInstance();
        var model = SpaceClientHost.get().ephemeris();
        if (mc.options.hideGui || model == null || !GasOcclusion.present(model) || !reconcile())
            return;
        var current = packet();
        String message;
        int color;
        if (current == null || current.status() != GasPressurePacket.Status.KNOWN) {
            message = I18n.get("hud.starfold.instrument_gas_unknown");
            color = 0xffe9af52;
        } else {
            var ambient = current.readings().getFirst();
            if (ambient.pressure() <= 0) return;
            var policy = model.catalogBody(ambient.bodyIndex()).gas().orElseThrow();
            message =
                    I18n.get(
                            "hud.starfold.instrument_gas_warning",
                            I18n.get(policy.name()),
                            Math.round(ambient.pressure() * 100));
            color = ambient.pressure() >= .5 ? 0xffff6655 : 0xffe9af52;
        }
        int width = mc.getWindow().getGuiScaledWidth();
        message = mc.font.plainSubstrByWidth(message, Math.max(0, width - 16));
        graphics.drawString(
                mc.font,
                message,
                (width - mc.font.width(message)) / 2,
                Math.max(6, mc.getWindow().getGuiScaledHeight() - 96),
                color,
                true);
    }

    private GasPressureClient() {}
}
