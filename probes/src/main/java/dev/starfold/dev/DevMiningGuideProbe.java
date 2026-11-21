package dev.starfold.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Mining-only guide operations; callers must check starfold_mining before loading this class. */
final class DevMiningGuideProbe {
    /** Click the screen's real widget, including its ordinary enabled/disabled hit test. */
    static void console(Minecraft mc, String kind, String action) {
        int x, y;
        if (kind.equals("mining") && mc.screen instanceof dev.starfold.mining.MiningScreen) {
            x =
                    switch (action) {
                        case "scan" -> 33;
                        case "toggle" -> 87;
                        default -> throw new IllegalArgumentException("Mining action: scan|toggle");
                    };
            y = 28;
        } else if (kind.equals("oil") && mc.screen instanceof dev.starfold.mining.OilScreen) {
            x =
                    switch (action) {
                        case "survey" -> 28;
                        case "build" -> 70;
                        case "pump" -> 112;
                        case "pause" -> 154;
                        default ->
                                throw new IllegalArgumentException(
                                        "Oil action: survey|build|pump|pause");
                    };
            y = 31;
        } else throw new IllegalStateException("Open the matching mining/oil console first");
        double px = (mc.screen.width - 176) / 2 + x;
        double py = (mc.screen.height - 233) / 2 + y;
        boolean enabled =
                mc.screen.children().stream()
                        .anyMatch(
                                child ->
                                        child
                                                        instanceof
                                                        net.minecraft.client.gui.components.Button
                                                                button
                                                && button.active
                                                && button.visible
                                                && button.isMouseOver(px, py));
        if (!enabled || !mc.screen.mouseClicked(px, py, 0))
            throw new IllegalStateException(
                    "Console widget rejected click: " + kind + " " + action);
        mc.screen.mouseReleased(px, py, 0);
    }

    /** Only synchronized menu fields: never prospect or read a server machine directly. */
    static int menuValue(AbstractContainerMenu menu, String kind, String field) {
        if (kind.equals("mining") && menu instanceof dev.starfold.mining.MiningMenu mining)
            return switch (field) {
                case "energy" -> mining.energy();
                case "cursor" -> mining.cursor();
                case "scan" -> mining.scanProgress();
                case "scanned" -> mining.scanned() ? 1 : 0;
                case "enabled" -> mining.enabled() ? 1 : 0;
                case "status" -> mining.status();
                default -> throw new IllegalArgumentException("Unknown mining field " + field);
            };
        if (kind.equals("oil") && menu instanceof dev.starfold.mining.OilMenu oil)
            return switch (field) {
                case "energy" -> oil.energy();
                case "surveyed" -> oil.surveyed() ? 1 : 0;
                case "remaining" -> oil.remaining();
                case "depth" -> oil.depth();
                case "well" -> oil.well() ? 1 : 0;
                case "mode" -> oil.mode();
                case "oil" -> oil.oil();
                default -> throw new IllegalArgumentException("Unknown oil field " + field);
            };
        throw new IllegalStateException("Wrong menu for " + kind);
    }

    private DevMiningGuideProbe() {}
}
