package dev.starfold.render;

import net.minecraft.client.Minecraft;

/** World AA uses 4×MSAA and TAA. Hand and HUD are drawn after the temporal resolve. */
public final class GroundAntiAliasing {
    private static int displayHeight;
    private GroundAntiAliasing() {}

    /** Logical output height remains fixed when only the world's sampling target grows. */
    public static int detailHeight() {
        return displayHeight > 0 ? displayHeight : Minecraft.getInstance().getWindow().getScreenHeight();
    }
    public static void begin() {
        displayHeight = Minecraft.getInstance().getWindow().getScreenHeight();
    }

    static void initialize(net.neoforged.bus.api.IEventBus bus) {
        bus.addListener((net.neoforged.neoforge.client.event.RegisterShadersEvent e) -> GroundTemporalFilter.reset());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) -> {
                    GroundTemporalFilter.clear(); GroundEdgeFilter.endNative(); GroundEdgeFilter.clear();
                });
    }
}
