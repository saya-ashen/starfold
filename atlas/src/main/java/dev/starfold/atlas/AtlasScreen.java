package dev.starfold.atlas;

import dev.starfold.api.PlanetMapApi;
import dev.starfold.api.SystemMapApi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;
import java.util.Arrays;
import java.util.Comparator;

/** Unified semantic zoom from local ground navigation to interstellar telemetry. */
public final class AtlasScreen extends AtlasMapScreen {
    public enum Layer {
        GROUND, GLOBE, SYSTEM, GALAXY;
        Layer scroll(double distance, double desired, double steps) { return scroll(distance, desired, steps, NEAR, FAR); }
        Layer scroll(double distance, double desired, double steps, double near, double far) {
            if (this == GROUND) return steps < 0 ? GLOBE : this;
            if (steps > 0 && desired <= near && distance <= near + (this == GLOBE ? .02 : near * .01)) return values()[ordinal() - 1];
            if (steps < 0 && this != GALAXY && desired >= far && distance >= far - (this == GLOBE ? .1 : far * .008)) return values()[ordinal() + 1];
            return this;
        }
    }
    static final double NEAR = 2.35, FAR = 12;
    private final MapCamera camera = new MapCamera();
    private final SystemView system = new SystemView();
    private final Layer initial;
    private GlobeView globe;
    private GroundView ground;
    private boolean northUp = true;
    private Layer layer = Layer.GLOBE, destination = Layer.GLOBE;
    private double transition = 1;
    private long lastFrame;
    private boolean pressed, dragged;
    private double pressX, pressY;
    private EditBox name;
    private Button record, target, delete, open;
    private PlanetMapApi.Location pending;
    private PlanetMapApi.Marker selected;
    private SystemMapApi.Ship selectedShip, pendingShip;
    private SystemView.Node selectedNode;
    private long lastClick;
    private double clickX, clickY;

    public AtlasScreen() { this(Layer.GLOBE); }
    public AtlasScreen(Layer initial) { super(text("title")); this.initial = initial; }
    private boolean spatial() { return layer == Layer.SYSTEM || layer == Layer.GALAXY; }
    private MapCamera activeCamera() { return spatial() ? system.camera() : camera; }
    @Override protected void init() {
        super.init();
        name = new EditBox(font, width / 2 - 100, height / 2 - 12, 200, 20, text("name"));
        name.setMaxLength(32); addRenderableWidget(name);
        record = addRenderableWidget(Button.builder(text("record"), b -> {
            if (!name.getValue().matches("[\\p{L}\\p{N}_-]{1,32}")) { name.setTextColor(0xff7777); return; }
            PlanetMapApi.mark(name.getValue(), globe.body().index(), pending.face(), pending.u(), pending.v()); dismiss();
        }).bounds(width / 2 - 100, height / 2 + 14, 200, 20).build());
        target = addRenderableWidget(Button.builder(text("target"), b -> {
            if (selectedShip != null) SystemMapApi.target(selectedShip.id());
            else if (selectedNode != null) SystemMapApi.target(selectedNode.body());
            else PlanetMapApi.target(selected.name());
            dismiss();
        }).bounds(width / 2 - 100, height / 2 - 12, 200, 20).build());
        delete = addRenderableWidget(Button.builder(text("delete"), b -> { PlanetMapApi.unmark(selected.name()); dismiss(); })
                .bounds(width / 2 - 100, height / 2 + 14, 200, 20).build());
        open = addRenderableWidget(Button.builder(text("open_planet"), b -> {
            var node = selectedNode;
            dismiss();
            if (node.system() != null) { system.system(node.body()); layer(Layer.SYSTEM); }
            else { body(node.body()); layer(Layer.GLOBE); camera.distance(3.5); }
        }).bounds(width / 2 - 100, height / 2 + 14, 200, 20).build());
        dismiss(); lastFrame = 0;
        PlanetMapApi.refreshMarkers();
        if (globe == null && !spatial()) {
            if (initial == Layer.GALAXY || initial == Layer.SYSTEM) { layer = destination = initial; system.enter(initial == Layer.GALAXY); }
            else recenter();
        }
    }
    private boolean modal() { return pending != null || selected != null || selectedShip != null || selectedNode != null; }
    private void dismiss() {
        pending = null; selected = null; selectedShip = null; selectedNode = null; pressed = dragged = false;
        if (name != null) { name.visible = record.visible = target.visible = delete.visible = open.visible = false; setFocused(null); }
    }
    private void body(int index) {
        var next = PlanetMapApi.bodies().stream().filter(b -> b.index() == index && b.surface()).findFirst().orElse(null);
        if (next == null || globe != null && globe.body().equals(next)) return;
        if (globe != null) { globe.close(); ground.close(); }
        globe = new GlobeView(next);
        ground = new GroundView(next); ground.northUp(northUp);
        camera.focus(new Vector3d()); camera.pose(0, 0, 3.5);
        dismiss();
    }
    @Override protected void recenter() {
        dismiss();
        if (spatial()) { system.system(SystemMapApi.currentSystem()); system.enter(layer == Layer.GALAXY); return; }
        var o = PlanetMapApi.observer();
        if (o != null && !o.inSpace()) {
            body(o.body());
            if (globe != null) { camera.face(globe.direction(o.face(), o.u(), o.v())); ground.recentre(); }
        } else {
            var nearest = SystemMapApi.orbits().stream().filter(SystemMapApi.Orbit::surface)
                    .min(Comparator.comparingDouble(SystemMapApi.Orbit::distance)).orElse(null);
            if (nearest != null) body(nearest.body());
        }
        layer(Layer.GLOBE); camera.distance(3.5);
    }
    @Override protected void layer(Layer next) {
        if (next == destination) return;
        if ((next == Layer.GLOBE || next == Layer.GROUND) && spatial() && globe == null && !systemBody()) return;
        dismiss(); pendingShip = null; activeCamera().stop(); destination = next; transition = 0;
        if ((next == Layer.SYSTEM || next == Layer.GALAXY) && !spatial()) system.system(SystemMapApi.currentSystem());
        if (next == Layer.GLOBE) { camera.focus(new Vector3d()); camera.distance(layer == Layer.GROUND ? NEAR : FAR); }
        if (next == Layer.GROUND && ground != null) ground.recentre();
    }
    private boolean systemBody() {
        var focus = system.focusedSurface();
        if (focus != null) { body(focus.body()); return true; }
        var nearest = SystemMapApi.orbits(system.system()).stream().filter(SystemMapApi.Orbit::surface)
                .min(Comparator.comparingDouble(SystemMapApi.Orbit::distance)).orElse(null);
        if (nearest == null) return false;
        body(nearest.body()); return true;
    }
    @Override public void tick() {
        super.tick();
        if (minecraft.level != null && minecraft.player != null && globe == null && !spatial() && destination == Layer.GLOBE) recenter();
    }
    @Override protected void focusShip(SystemMapApi.Ship ship) {
        dismiss();
        if (ship.face() >= 0) {
            body(ship.body());
            if (globe != null) camera.face(globe.direction(ship.face(), ship.u(), ship.v()));
            layer(Layer.GLOBE); camera.distance(3.5);
        } else {
            layer(Layer.SYSTEM);
            boolean changed = system.system() != ship.system();
            system.system(ship.system());
            if (layer == Layer.SYSTEM) {
                if (changed) system.enter(false);
                system.focus(ship);
            } else pendingShip = ship;
        }
        selectedShip = ship; target.visible = true; setFocused(target);
    }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min(.1, (now - lastFrame) / 1e9); lastFrame = now;
        camera.viewport(0, mapTop(), mapWidth(), mapBottom() - mapTop());
        if (!spatial()) camera.update(dt, pressed || modal());
        if (transition < 1) {
            transition = Math.min(1, transition + dt / .28);
            if (transition >= .5 && layer != destination) {
                layer = destination;
                if (spatial()) {
                    system.enter(layer == Layer.GALAXY);
                    if (pendingShip != null) { system.focus(pendingShip); pendingShip = null; }
                }
            }
        }
        g.fill(0, 0, width, height, 0xff080f18);
        g.enableScissor(0, mapTop(), mapWidth(), mapBottom());
        if (layer == Layer.GLOBE && globe != null) {
            globe.render(g, camera, 0, mapTop(), mapWidth(), mapBottom() - mapTop());
            globe.overlays(g, font, camera, mapWidth(), mapTop(), mapBottom());
        } else if (layer == Layer.GROUND && ground != null) ground.render(g, 0, mapTop(), mapWidth(), mapBottom() - mapTop(), delta);
        else if (spatial()) {
            int arrived = system.render(g, font, mapWidth(), mapTop(), mapBottom() - mapTop(), dt, pressed || modal());
            if (arrived >= 0) { body(arrived); layer(Layer.GLOBE); camera.distance(3.5); }
        } else g.drawCenteredString(font, text(layer == Layer.GLOBE ? "standby" : layer == Layer.GROUND ? "ground" : "system"),
                mapWidth() / 2, height / 2, 0xff71dce0);
        if (transition < 1) {
            int alpha = (int) (255 * (1 - Math.abs(2 * transition - 1)));
            g.fill(0, mapTop(), mapWidth(), mapBottom(), alpha << 24 | 0x080f18);
        }
        g.disableScissor();
        var readout = spatial() ? text(layer == Layer.GALAXY ? "galaxy_readout" : "system_readout", layer == Layer.GALAXY ? SystemMapApi.currentSystem() : system.system())
                : globe == null ? text("standby") : text("atlas_readout", text("layer." + layer.name().toLowerCase(java.util.Locale.ROOT)),
                Component.translatable(globe.body().nameKey()), globe.progress());
        g.drawString(font, font.plainSubstrByWidth(readout.getString(), width - 16), 8, 10, 0xffaad4dd, false);
        var hit = globe == null || !inMap(mouseX, mouseY) ? null : layer == Layer.GLOBE ? globe.pick(camera, mouseX, mouseY)
                : layer == Layer.GROUND ? location(ground.pick(mouseX, mouseY)) : null;
        if (hit != null) g.drawString(font, text("coordinates", text("face." + hit.face()), (int) hit.u(), (int) hit.v()), 8, mapBottom() + 2, 0xff71dce0, false);
        var node = spatial() && inMap(mouseX, mouseY) ? system.pick(mouseX, mouseY) : null;
        if (node != null) {
            var detail = node.system() != null ? text("system_count", node.system().planets())
                    : text("kind." + (node.ship() != null ? "ship" : node.kind().name().toLowerCase(java.util.Locale.ROOT)));
            var selection = text("selection", node.label(), detail, SystemView.distance(SystemMapApi.observer().distance(node.world())));
            g.drawString(font, font.plainSubstrByWidth(selection.getString(), width - 16), 8, mapBottom() + 2, 0xff71dce0, false);
        }
        int lineY = mapBottom() + 14;
        for (var line : font.split(text(spatial() ? "system_controls" : layer == Layer.GROUND ? "ground_controls" : "globe_controls"), width - 16)) {
            g.drawString(font, line, 8, lineY, 0xff728e9c, false); lineY += font.lineHeight;
        }
        chrome(g);
        if (modal()) {
            g.fill(0, 0, width, height, 0x70000000);
            g.fill(width / 2 - 110, height / 2 - 44, width / 2 + 110, height / 2 + 44, 0xf0102430);
            g.drawCenteredString(font, pending != null ? text("name") : selectedShip != null ? Component.literal(selectedShip.name())
                    : selectedNode != null ? selectedNode.system() == null ? selectedNode.label() : SystemView.caption(selectedNode.system()) : selected.launch() ? text("launch") : Component.literal(selected.name()), width / 2, height / 2 - 34, 0xffaad4dd);
        }
    }
    private AbstractWidget[] dialogWidgets() { return new AbstractWidget[]{name, record, target, delete, open}; }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (modal()) {
            if (button == 0 && selectedNode != null && selectedNode.surface() && System.nanoTime() - lastClick < 350_000_000
                    && Math.hypot(x - clickX, y - clickY) < 6) {
                var node = selectedNode; dismiss(); system.fly(node); return true;
            }
            for (var widget : dialogWidgets()) if (widget.visible && widget.mouseClicked(x, y, button)) {
                if (modal()) { setFocused(widget); setDragging(button == 0); }
                break;
            }
            return true;
        }
        if (!inMap(x, y) || transition < 1) return super.mouseClicked(x, y, button);
        if (button == 1 && !spatial() && globe != null) { select(x, y); return true; }
        if (button == 0) {
            activeCamera().stop(); pressX = x; pressY = y; pressed = true; dragged = false; return true;
        }
        return super.mouseClicked(x, y, button);
    }
    private static PlanetMapApi.Location location(GroundView.Pick p) { return p == null ? null : new PlanetMapApi.Location(p.face(), p.u(), p.v()); }
    private void select(double x, double y) {
        if (spatial()) {
            var node = system.pick(x, y);
            if (node == null) return;
            system.select(node); selectedNode = node; selectedShip = node.ship();
            target.visible = true; setFocused(target);
            open.visible = node.surface() || node.system() != null;
            open.setMessage(text(node.system() != null ? "view_system" : "open_planet"));
            lastClick = System.nanoTime(); clickX = x; clickY = y;
            return;
        }
        // shortcut: the ground layer only places new waypoints; existing ones are selected on the globe.
        var pin = layer == Layer.GLOBE ? globe.hovered(x, y) : null;
        if (pin != null) {
            selected = pin.marker(); selectedShip = pin.ship(); target.visible = true; setFocused(target);
            delete.visible = selected != null && !selected.launch();
        } else {
            pending = layer == Layer.GLOBE ? globe.pick(camera, x, y) : location(ground.pick(x, y));
            if (pending != null) { name.visible = record.visible = true; name.setValue(""); name.setTextColor(0xffffff); setFocused(name); }
        }
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && pressed && !modal()) {
            if (dragged || Math.hypot(x - pressX, y - pressY) > 3) {
                dragged = true;
                if (layer != Layer.GROUND) activeCamera().drag(dx, dy);
                else ground.drag(dx, dy);
            }
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        boolean click = button == 0 && pressed && !dragged; pressed = false;
        if (click && !modal() && inMap(x, y) && (spatial() || globe != null)) { select(x, y); return true; }
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (modal() || !inMap(x, y)) return super.mouseScrolled(x, y, dx, dy);
        if (transition < 1 || dy == 0) return true;
        if (layer == Layer.GROUND && ground.scroll(dy)) return true;
        var active = activeCamera();
        double near = spatial() ? system.near() : NEAR, far = spatial() ? system.far() : FAR;
        var next = layer.scroll(active.distance(), active.desiredDistance(), dy, near, far);
        if (next != layer) {
            if (next == Layer.GLOBE && spatial() && !systemBody()) return true;
            layer(next);
        } else if (layer != Layer.GROUND) active.zoom(dy, near, far);
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (modal()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { dismiss(); return true; }
            if (key == GLFW.GLFW_KEY_TAB) {
                var widgets = Arrays.stream(dialogWidgets()).filter(w -> w.visible).toList();
                int index = widgets.indexOf(getFocused());
                setFocused(widgets.get(Math.floorMod(index + ((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1), widgets.size())));
            } else if (pending != null && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) record.onPress();
            else if (getFocused() != null) getFocused().keyPressed(key, scan, mods);
            return true;
        }
        if (key == GLFW.GLFW_KEY_N && layer == Layer.GROUND) { northUp = !northUp; ground.northUp(northUp); return true; }
        return super.keyPressed(key, scan, mods);
    }
    @Override public boolean charTyped(char character, int mods) {
        return modal() ? name.visible && name.charTyped(character, mods) : super.charTyped(character, mods);
    }
    @Override public void removed() { super.removed(); if (globe != null) { globe.close(); ground.close(); globe = null; ground = null; } }
}
