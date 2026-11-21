package dev.starfold.mining;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Client-only registration; the menu and all machine actions remain server-owned. */
@EventBusSubscriber(
        modid = "starfold_mining",
        bus = EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class MiningClient {
    private MiningClient() {}

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(StarfoldMining.MENU.get(), MiningScreen::new);
        event.register(StarfoldMining.OIL_MENU.get(), OilScreen::new);
    }
}
