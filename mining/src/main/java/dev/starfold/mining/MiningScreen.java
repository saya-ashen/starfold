package dev.starfold.mining;

import dev.starfold.render.GroundClient;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.Locale;

/**
 * One-block drill console. Menu slot coordinates: fuel (26,38); outputs (8+18*c,100+18*r),
 * r=0..1; player inventory (8+18*c,151+18*r), r=0..2; hotbar (8+18*c,209).
 */
public final class MiningScreen extends AbstractContainerScreen<MiningMenu> {
    private static final int TEXT = 0xffdce3e7;
    private static final int MUTED = 0xffa6b3bd;
    private static final int ORANGE = 0xffed9a43;
    private Button scan;
    private Button toggle;
    private Button reset;
    private Button obstructionMode;
    private State state = State.waiting();

    public MiningScreen(MiningMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 233;
        titleLabelX = 8;
        titleLabelY = 7;
        inventoryLabelX = 8;
        inventoryLabelY = 140;
    }

    @Override
    protected void init() {
        super.init();
        scan = action("scan", "scan_hint", 8, 38, MiningMenu.SCAN);
        toggle = action("start", "start_hint", 48, 38, MiningMenu.START_PAUSE);
        reset = action("reset", "reset_hint", 88, 38, MiningMenu.RESET);
        obstructionMode = action("stop_mode", "stop_mode_hint", 128, 40, MiningMenu.TOGGLE_OBSTRUCTIONS);
    }

    private Button action(String label, String hint, int x, int width, int id) {
        return addRenderableWidget(
                Button.builder(text(label), button -> sendAction(id))
                        .bounds(leftPos + x, topPos + 20, width, 16)
                        .tooltip(Tooltip.create(text(hint)))
                        .build());
    }

    private void sendAction(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        state = readState();
        scan.active =
                !state.running()
                        && !menu.scanned()
                        && menu.cursor() == 0
                        && menu.scanProgress() == 0;
        toggle.active = menu.scanned() || state.scanning();
        reset.active = !state.running();
        toggle.setMessage(text(state.running() ? "pause" : "start"));
        toggle.setTooltip(Tooltip.create(text(state.running() ? "pause_hint" : "start_hint")));
        boolean skips = menu.skipsObstructions();
        obstructionMode.setMessage(text(skips ? "skip_mode" : "stop_mode"));
        obstructionMode.setTooltip(Tooltip.create(text(skips ? "skip_mode_hint" : "stop_mode_hint")));
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (isHovering(152, 5, 16, 12, mouseX, mouseY)) {
            graphics.renderTooltip(font, font.split(text("guide"), 240), mouseX, mouseY);
        } else if (isHovering(52, 38, 116, 18, mouseX, mouseY)) {
            graphics.renderTooltip(
                    font,
                    text("energy", number(state.energy()), number(state.maxEnergy())),
                    mouseX,
                    mouseY);
        } else if (isHovering(52, 57, 116, 10, mouseX, mouseY)) {
            graphics.renderTooltip(
                    font,
                    font.split(
                            text(
                                    "details",
                                    statusDetails(),
                                    number(state.cursor()),
                                    number(state.total()),
                                    number(state.scanProgress())),
                            240),
                    mouseX,
                    mouseY);
        } else if (isHovering(25, 37, 18, 18, mouseX, mouseY) && !menu.getSlot(0).hasItem()) {
            graphics.renderTooltip(font, font.split(text("fuel_hint"), 220), mouseX, mouseY);
        } else {
            for (int i = 0; i < Math.min(3, state.ores().size()); i++) {
                if (isHovering(8, 68 + i * 10, 160, 10, mouseX, mouseY)) {
                    graphics.renderTooltip(
                            font, font.split(oreLine(state.ores().get(i)), 240), mouseX, mouseY);
                    break;
                }
            }
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xff1b232a);
        graphics.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, 0xff4b5862);
        graphics.fill(x + 3, y + 3, x + imageWidth - 3, y + imageHeight - 3, 0xff303b44);
        graphics.fill(x + 4, y + 4, x + 172, y + 17, 0xff202a31);
        graphics.fill(x + 4, y + 4, x + 6, y + 17, ORANGE);
        graphics.fill(x + 7, y + 66, x + 169, y + 98, 0xff202a31);
        graphics.fill(x + 8, y + 135, x + 168, y + 136, 0xff74818b);
        // Use the actual menu positions for bevels, preserving vanilla slot hit-testing.
        for (var slot : menu.slots) {
            int sx = x + slot.x - 1;
            int sy = y + slot.y - 1;
            graphics.fill(sx, sy, sx + 18, sy + 18, 0xff182128);
            graphics.fill(sx + 1, sy + 1, sx + 18, sy + 18, 0xff82909a);
            graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xff46525b);
        }
        graphics.fill(x + 52, y + 51, x + 168, y + 55, 0xff141c22);
        if (state.maxEnergy() > 0 && state.energy() >= 0) {
            int filled =
                    (int)
                            (114L
                                    * Math.clamp(
                                            (long) state.energy(), 0L, (long) state.maxEnergy())
                                    / state.maxEnergy());
            graphics.fill(x + 53, y + 52, x + 53 + filled, y + 54, ORANGE);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        line(graphics, title, titleLabelX, titleLabelY, 140, TEXT);
        graphics.drawString(font, "?", 158, 7, ORANGE, false);
        graphics.drawString(
                font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false);
        line(graphics, text("fuel"), 8, 57, 42, MUTED);
        line(
                graphics,
                text("energy", number(state.energy()), number(state.maxEnergy())),
                52,
                40,
                116,
                TEXT);
        line(graphics, obstructionLabel(), 52, 57, 116, ORANGE);
        if (state.ores().isEmpty()) {
            line(graphics, text("ores_empty"), 8, 69, 160, MUTED);
            line(graphics, text("ore_hint"), 8, 81, 160, MUTED);
        } else {
            for (int i = 0; i < Math.min(3, state.ores().size()); i++) {
                line(graphics, oreLine(state.ores().get(i)), 8, 68 + i * 10, 160, TEXT);
            }
        }
    }

    private Component obstructionLabel() {
        var reason = menu.obstruction();
        return reason == MiningBlockEntity.Obstruction.NONE
                ? state.status()
                : text("obstruction." + reason.name().toLowerCase(Locale.ROOT));
    }

    private Component statusDetails() {
        if (menu.obstruction() == MiningBlockEntity.Obstruction.NONE) {
            if (menu.status() == MiningBlockEntity.BLOCKED || menu.status() == MiningBlockEntity.PROTECTED)
                return state.status().copy().append("\n").append(text("obstruction_retry"));
            return state.status();
        }
        var details = obstructionLabel().copy();
        var block = menu.obstructingBlock();
        if (block != null) details.append("\n").append(text("obstruction_block", block.getName()));
        int cursor = menu.obstructionCursor();
        if (cursor >= 0 && cursor < MiningVolume.TOTAL) {
            var frame = GroundClient.frame();
            var origin = frame.toStorage(menu.platform.getBlockPos());
            var position = frame.toClient(MiningBlockEntity.target(origin, cursor));
            int depth = cursor / MiningVolume.LAYER + 1;
            details.append("\n").append(text("obstruction_position",
                    position.getX(), position.getY(), position.getZ(), depth));
        }
        return details;
    }

    private void line(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        graphics.drawString(
                font, font.plainSubstrByWidth(text.getString(), width), x, y, color, false);
    }

    private static Component oreLine(OreSummary ore) {
        return text("ore", ore.name(), ore.count(), ore.minDepth(), ore.maxDepth());
    }

    private static Component number(int value) {
        return value < 0 ? text("unknown") : Component.literal(Integer.toString(value));
    }

    private static Component text(String key, Object... arguments) {
        return Component.translatable("screen.starfold_mining." + key, arguments);
    }

    private State readState() {
        // Read synchronized server menu fields and the compact ore summary only.
        // Slot layout in MiningMenu must match the coordinates documented above.
        // Only synchronized menu data and BE ore summaries are read; no client-world scan.
        int total = menu.total();
        int progress =
                total <= 0 ? 0 : (int) Math.clamp(100L * menu.scanProgress() / total, 0L, 100L);
        String key =
                switch (menu.status()) {
                    case MiningBlockEntity.IDLE -> "idle";
                    case MiningBlockEntity.SCANNING -> "scanning";
                    case MiningBlockEntity.READY -> "ready";
                    case MiningBlockEntity.MINING -> "mining";
                    case MiningBlockEntity.PAUSED -> "paused";
                    case MiningBlockEntity.NO_ENERGY -> "no_energy";
                    case MiningBlockEntity.UNLOADED -> "unloaded";
                    case MiningBlockEntity.OUTPUT_FULL -> "output_full";
                    case MiningBlockEntity.BLOCKED -> "blocked";
                    case MiningBlockEntity.COMPLETE -> "complete";
                    case MiningBlockEntity.UNSUPPORTED -> "unsupported";
                    case MiningBlockEntity.PROTECTED -> "protected";
                    case MiningBlockEntity.OWNER_AWAY -> "owner_away";
                    default -> "idle";
                };
        Component status =
                menu.status() == MiningBlockEntity.SCANNING
                        ? text("scanning", progress)
                        : Component.translatable("status.starfold_mining." + key);
        List<OreSummary> ores =
                menu.summary().stream()
                        .map(
                                ore -> {
                                    var id = ResourceLocation.tryParse(ore.id());
                                    Component name =
                                            id != null && BuiltInRegistries.BLOCK.containsKey(id)
                                                    ? BuiltInRegistries.BLOCK.get(id).getName()
                                                    : Component.literal(ore.id());
                                    return new OreSummary(
                                            name, ore.count(), ore.minDepth(), ore.maxDepth());
                                })
                        .toList();
        boolean scanning =
                !menu.scanned()
                        && (menu.enabled()
                                || menu.scanProgress() > 0
                                || menu.status() == MiningBlockEntity.SCANNING
                                || menu.status() == MiningBlockEntity.PAUSED);
        return new State(
                menu.energy(),
                menu.maxEnergy(),
                menu.cursor(),
                total,
                progress,
                scanning,
                menu.enabled(),
                status,
                ores);
    }

    private record OreSummary(Component name, int count, int minDepth, int maxDepth) {}

    private record State(
            int energy,
            int maxEnergy,
            int cursor,
            int total,
            int scanProgress,
            boolean scanning,
            boolean running,
            Component status,
            List<OreSummary> ores) {
        static State waiting() {
            return new State(-1, -1, -1, -1, -1, false, false, text("waiting"), List.of());
        }
    }
}
