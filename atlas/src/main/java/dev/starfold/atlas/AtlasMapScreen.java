package dev.starfold.atlas;

import dev.starfold.api.SystemMapApi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Shared layer controls and paged private hull list. All data crosses the public API. */
abstract class AtlasMapScreen extends Screen {
    private final List<Button> shipButtons = new ArrayList<>();
    private Button previous, next;
    private List<SystemMapApi.Ship> ships = List.of();
    private int page;
    AtlasMapScreen(Component title) { super(title); }
    static Component text(String key, Object... args) { return Component.translatable("atlas.starfold_atlas." + key, args); }
    private int mapTop = 52;
    protected int mapWidth() { return width - Math.min(150, width / 4); }
    protected int mapTop() { return mapTop; }
    protected boolean inMap(double x, double y) { return x >= 0 && x < mapWidth() && y >= mapTop() && y < mapBottom(); }
    protected int mapBottom() { return height - 44; }
    @Override protected void init() {
        shipButtons.clear();
        String[] keys = {"ground", "globe", "system", "galaxy", "recenter"};
        boolean compact = Arrays.stream(keys).mapToInt(k -> font.width(text(k)) + 12).sum() + 32 > mapWidth();
        int x = 8, y = 27;
        for (int i = 0; i < keys.length; i++) {
            var caption = text(keys[i] + (compact ? ".short" : ""));
            int tab = font.width(caption) + 12, index = i;
            if (x + tab > mapWidth() - 8) { x = 8; y += 24; }
            addRenderableWidget(Button.builder(caption, b -> {
                if (index == 4) recenter(); else layer(AtlasScreen.Layer.values()[index]);
            }).bounds(x, y, tab, 20).build());
            x += tab + 4;
        }
        mapTop = y + 25;
        int left = mapWidth() + 6, available = width - left - 6;
        var refreshText = text(available < 100 ? "refresh.short" : "refresh");
        int refresh = font.width(refreshText) + 12, arrow = (available - refresh - 4) / 2;
        int rows = Math.max(1, (height - mapTop - 88) / 22);
        for (int i = 0; i < rows; i++) {
            int row = i;
            shipButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> focusShip(ships.get(page * rows + row)))
                    .bounds(left, mapTop + 22 + i * 22, available, 20).build()));
        }
        previous = addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; updateShips(); }).bounds(left, height - 68, arrow, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; updateShips(); }).bounds(left + arrow, height - 68, arrow, 20).build());
        addRenderableWidget(Button.builder(refreshText, b -> SystemMapApi.refreshShips()).bounds(width - refresh - 6, height - 68, refresh, 20).build());
        updateShips();
        SystemMapApi.refreshShips();
    }
    protected abstract void layer(AtlasScreen.Layer layer);
    protected abstract void recenter();
    @Override public void tick() {
        if (minecraft.level == null || minecraft.player == null) { onClose(); return; }
        updateShips();
    }
    private void updateShips() {
        ships = SystemMapApi.ships();
        int rows = shipButtons.size();
        if (rows == 0) return;
        page = Math.clamp(page, 0, Math.max(0, (ships.size() - 1) / rows));
        for (int i = 0; i < rows; i++) {
            int index = page * rows + i; var button = shipButtons.get(i);
            button.visible = index < ships.size();
            if (button.visible) button.setMessage(Component.literal(ships.get(index).name()));
        }
        previous.active = page > 0;
        next.active = (page + 1) * rows < ships.size();
    }
    protected void chrome(GuiGraphics g) {
        g.fill(mapWidth(), mapTop(), width, height - 72, 0xff101e2b);
        var title = text("ships", ships.size());
        if (font.width(title) > width - mapWidth() - 12) title = text("ships.short", ships.size());
        g.drawString(font, title, mapWidth() + 6, mapTop() + 6, 0xff71dce0, false);
    }
    protected abstract void focusShip(SystemMapApi.Ship ship);
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() { shipButtons.clear(); }
}
