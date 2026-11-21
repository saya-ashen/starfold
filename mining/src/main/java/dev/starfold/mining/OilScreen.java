package dev.starfold.mining;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Standalone oil console: uses server menu fields only, without Atlas or client-world prospecting. */
public final class OilScreen extends AbstractContainerScreen<OilMenu> {
    private Button survey, build, pump, pause;

    public OilScreen(OilMenu menu, Inventory inventory, Component title) {
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
        survey = action("survey", 8, OilMenu.SURVEY);
        build = action("build", 50, OilMenu.BUILD);
        pump = action("pump", 92, OilMenu.PUMP);
        pause = action("pause", 134, OilMenu.PAUSE);
    }

    private Button action(String key, int x, int id) {
        return addRenderableWidget(
                Button.builder(
                                text(key),
                                button -> {
                                    if (minecraft != null && minecraft.gameMode != null)
                                        minecraft.gameMode.handleInventoryButtonClick(
                                                menu.containerId, id);
                                })
                        .bounds(leftPos + x, topPos + 22, 40, 18)
                        .tooltip(Tooltip.create(text(key + "_hint")))
                        .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        survey.active = !menu.well() && menu.mode() != OilExtractorBlockEntity.SURVEY;
        build.active =
                menu.surveyed()
                        && menu.remaining() > 0
                        && !menu.well()
                        && menu.mode() != OilExtractorBlockEntity.BUILD;
        pump.active = menu.well() && menu.mode() != OilExtractorBlockEntity.PUMP;
        pause.active = menu.mode() != OilExtractorBlockEntity.OFF;
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (isHovering(8, 43, 160, 11, mouseX, mouseY))
            graphics.renderTooltip(
                    font,
                    font.split(Component.translatable(menu.status().key() + "_hint"), 250),
                    mouseX,
                    mouseY);
        else if (isHovering(154, 5, 16, 12, mouseX, mouseY))
            graphics.renderTooltip(font, font.split(text("guide"), 260), mouseX, mouseY);
        else if (isHovering(52, 57, 116, 13, mouseX, mouseY))
            graphics.renderTooltip(font, text("energy_hint", menu.burnTicks()), mouseX, mouseY);
        else if (isHovering(52, 79, 116, 20, mouseX, mouseY))
            graphics.renderTooltip(font, font.split(text("tank_hint"), 250), mouseX, mouseY);
        else if (isHovering(52, 103, 116, 13, mouseX, mouseY))
            graphics.renderTooltip(
                    font, text("progress", menu.survey(), menu.depth()), mouseX, mouseY);
        else if (isHovering(8, 122, 160, 13, mouseX, mouseY))
            graphics.renderTooltip(
                    font,
                    font.split(
                            text("remaining", remaining())
                                    .copy()
                                    .append("\n")
                                    .append(text("remaining_hint")),
                            250),
                    mouseX,
                    mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xff17202a);
        graphics.fill(x + 2, y + 2, x + 174, y + 231, 0xff34434c);
        graphics.fill(x + 5, y + 42, x + 171, y + 55, 0xff1d2931);
        for (var slot : menu.slots) {
            int sx = x + slot.x - 1, sy = y + slot.y - 1;
            graphics.fill(sx, sy, sx + 18, sy + 18, 0xff152028);
            graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xff607079);
        }
        bar(graphics, 52, 71, menu.energy(), OilCycle.ENERGY_CAPACITY, 0xffe6a447);
        bar(graphics, 52, 94, menu.oil(), OilCycle.TANK_CAPACITY, 0xffaab445);
    }

    private void bar(GuiGraphics graphics, int x, int y, int amount, int capacity, int color) {
        graphics.fill(leftPos + x, topPos + y, leftPos + x + 116, topPos + y + 4, 0xff101a20);
        int length = (int) (114L * Math.clamp(amount, 0, capacity) / capacity);
        graphics.fill(
                leftPos + x + 1, topPos + y + 1, leftPos + x + 1 + length, topPos + y + 3, color);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        line(graphics, title, 8, 7, 144, 0xffe4e9ed);
        line(graphics, Component.literal("?"), 158, 7, 12, 0xffe6a447);
        line(graphics, Component.translatable(menu.status().key()), 8, 45, 160, 0xffe6a447);
        line(graphics, text("fuel"), 8, 56, 42, 0xffc4ced4);
        line(graphics, text("remainder"), 8, 91, 42, 0xffc4ced4);
        line(graphics, text("energy", menu.energy()), 52, 60, 116, 0xffe4e9ed);
        line(graphics, text("tank", menu.oil()), 52, 83, 116, 0xffe4e9ed);
        line(graphics, text("progress", menu.survey(), menu.depth()), 52, 106, 116, 0xffe4e9ed);
        line(graphics, text("remaining", remaining()), 8, 125, 160, 0xffc4ced4);
        line(graphics, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 160, 0xffe4e9ed);
    }

    private Component remaining() {
        return !menu.surveyed()
                ? text("unknown")
                : menu.remaining() == Integer.MAX_VALUE
                        ? text("infinite")
                        : Component.literal(menu.remaining() + " mB");
    }

    private void line(
            GuiGraphics graphics, Component component, int x, int y, int width, int color) {
        graphics.drawString(
                font, font.plainSubstrByWidth(component.getString(), width), x, y, color, false);
    }

    private static Component text(String key, Object... arguments) {
        return Component.translatable("oil.starfold_mining." + key, arguments);
    }
}
