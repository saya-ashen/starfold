package dev.starfold.atlas;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.starfold.api.PlanetMapApi;
import dev.starfold.api.SystemMapApi;
import dev.starfold.api.SystemMapApi.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import java.util.*;
import static dev.starfold.atlas.AtlasMapScreen.text;

/** Live schematic orrery and interstellar chart, sharing one orbit camera. */
final class SystemView {
    record Node(int body, Ship ship, SystemMapApi.System system, Kind kind, boolean surface,
                Component label, Position world, Vector3d point, Colors colors, int priority) {}
    private record Pin(Node node, MapCamera.ScreenPoint point, double radius) {}
    private record Ring(List<Vector3d> points) {}
    private final MapCamera camera = new MapCamera();
    private List<Node> nodes = List.of();
    private List<Pin> pins = List.of();
    private final List<Ring> rings = new ArrayList<>();
    private SystemProjection projection;
    private GalaxyProjection galaxyProjection;
    private boolean galaxy, reset = true, refit = true;
    private int viewportWidth, viewportHeight;
    private int system = -1, focusBody = -1, selectedBody = -1, flying = -1;
    private UUID focusShip, selectedShip;
    private Vector3d destination = new Vector3d();
    private Position observer;
    private Vector3d player;
    private double near = .1, far = 10, extent = 1;

    MapCamera camera() { return camera; }
    double near() { return near; }
    double far() { return far; }
    int system() { return system; }
    void system(int system) { this.system = system; }
    void enter(boolean galaxy) {
        this.galaxy = galaxy;
        if (system < 0) system = SystemMapApi.currentSystem();
        reset = refit = true; flying = -1; focusBody = selectedBody = -1; focusShip = selectedShip = null;
    }
    void focus(Ship ship) { focusShip = selectedShip = ship.id(); focusBody = selectedBody = -1; flying = -1; refit = true; }
    void select(Node node) {
        flying = -1;
        refit = true;
        if (node.system() != null) system = node.body();
        focusBody = selectedBody = node.ship() == null ? node.body() : -1;
        focusShip = selectedShip = node.ship() == null ? null : node.ship().id();
    }
    void fly(Node node) { select(node); flying = node.body(); camera.distance(near); }
    Node focusedSurface() {
        return nodes.stream().filter(n -> n.body() == focusBody && n.surface()).findFirst().orElse(null);
    }
    private static Vector3d vector(Position p) { return new Vector3d(p.x(), p.y(), p.z()); }
    private Vector3d position(Position p) { return vector(galaxy ? galaxyProjection.position(p) : projection.position(p)); }
    static Component caption(SystemMapApi.System s) {
        return s.name().isBlank() ? text("system_name", s.identity()) : Component.literal(s.name());
    }
    private void telemetry() {
        observer = SystemMapApi.observer();
        if (system < 0) system = SystemMapApi.currentSystem();
        var stars = SystemMapApi.systems();
        var orbits = galaxy ? List.<Orbit>of() : SystemMapApi.orbits(system);
        rings.clear();
        if (stars.isEmpty() || !galaxy && orbits.isEmpty()) { nodes = List.of(); player = null; return; }
        var target = SystemMapApi.navigationTarget();
        var ground = PlanetMapApi.observer();
        if (galaxy) {
            var home = stars.stream().filter(SystemMapApi.System::home).findFirst().orElse(stars.getFirst());
            galaxyProjection = new GalaxyProjection(stars.stream().map(SystemMapApi.System::position).toList(), home.position());
        } else projection = new SystemProjection(orbits); // API samples positions at SystemMapApi.time() each frame.
        var result = new ArrayList<Node>();
        if (galaxy) for (var s : stars) {
            int priority = s.body() == target.body() || s.body() == selectedBody ? 0 : s.body() == SystemMapApi.currentSystem() ? 1 : 2;
            int color = s.body() == SystemMapApi.currentSystem() ? 0xff71ffff : s.home() ? 0xff91ffad : 0xffffd584;
            color = GlobeRaster.dim(color, .75 + .25 * Math.min(1, s.planets() / 8.0));
            result.add(new Node(s.body(), null, s, Kind.STAR, false, caption(s), s.position(), position(s.position()),
                    new Colors(color, color), priority));
        } else for (var o : orbits) {
            int priority = o.body() == target.body() || o.body() == selectedBody ? 0 : ground != null && ground.body() == o.body() ? 1
                    : o.kind() == Kind.MOON ? 3 : 2;
            result.add(new Node(o.body(), null, null, o.kind(), o.surface(), o.kind() == Kind.STAR ? text("primary") : Component.translatable(o.nameKey()),
                    o.position(), vector(projection.position(o.body())), SystemMapApi.colors(o.body()), priority));
            if (o.parent() < 0) continue;
            var parent = orbits.stream().filter(p -> p.body() == o.parent()).findFirst().orElseThrow();
            var points = new ArrayList<Vector3d>();
            for (int i = 0; i <= 128; i++) points.add(vector(projection.relative(SystemProjection.orbit(parent.position(),
                    o.axis(), o.radial(), o.radius(), Math.PI * 2 * i / 128), parent.body())));
            rings.add(new Ring(points));
        }
        if (!galaxy) for (var ship : SystemMapApi.ships()) if (ship.system() == system) {
            int priority = ship.id().equals(target.ship()) || ship.id().equals(selectedShip) ? 0 : 2;
            var p = ship.face() >= 0 ? projection.position(ship.body()) : projection.position(ship.position());
            result.add(new Node(-1, ship, null, null, false, Component.literal(ship.name()), ship.position(), vector(p), null, priority));
        }
        nodes = List.copyOf(result);
        player = observer == null || !galaxy && system != SystemMapApi.currentSystem() ? null
                : !galaxy && ground != null && !ground.inSpace() ? vector(projection.position(ground.body())) : position(observer);
        extent = Math.max(.2, nodes.stream().mapToDouble(n -> n.point().length()).max().orElse(1));
        for (var ring : rings) for (var p : ring.points()) extent = Math.max(extent, p.length());
    }
    /** Returns the landable body reached by an animated double-click flight, or -1. */
    int render(GuiGraphics g, Font font, int width, int top, int height, double dt, boolean dragging) {
        camera.viewport(0, top, width, height);
        telemetry();
        if (nodes.isEmpty()) { pins = List.of(); g.drawCenteredString(font, text("system_standby"), width / 2, top + height / 2, 0xff71dce0); return -1; }
        var focus = nodes.stream().filter(n -> focusShip != null ? n.ship() != null && focusShip.equals(n.ship().id())
                : n.ship() == null && n.body() == focusBody).findFirst().orElse(null);
        if (focus != null) destination = new Vector3d(focus.point());
        var points = new ArrayList<Vector3d>();
        for (var n : nodes) points.add(n.point());
        for (var ring : rings) points.addAll(ring.points());
        if (reset && focus == null) {
            var min = new Vector3d(points.getFirst()); var max = new Vector3d(min);
            for (var p : points) { min.min(p); max.max(p); }
            destination = min.add(max).mul(.5);
        }
        double fit = fit(points, destination, width, height);
        // Stable limits let extra-scroll transitions complete while the ephemeris keeps moving.
        if (refit || width != viewportWidth || height != viewportHeight) {
            far = Math.max(1, fit * 1.2); near = Math.max(.04, far * .008);
            camera.distance(Math.clamp(camera.desiredDistance(), near, far));
            viewportWidth = width; viewportHeight = height; refit = false;
        }
        if (reset) { camera.focus(destination); camera.pose(.35, .65, fit); reset = false; }
        else camera.focus(camera.focus().lerp(destination, 1 - Math.exp(-8 * dt)));
        if (flying >= 0) camera.distance(near);
        camera.update(dt, dragging);
        MapRender.draw(g, camera, 0, top, width, height, .001, far * 4 + extent * 2, () -> geometry(height));
        overlays(g, font, width, top, top + height);
        int arrived = flying >= 0 && camera.focus().distance(destination) < near * .1 && camera.distance() < near * 1.1 ? flying : -1;
        if (arrived >= 0) flying = -1;
        return arrived;
    }
    /** Fit the initial .35/.65 pose to 85% of the viewport's short edge, including perspective depth. */
    static double fit(List<Vector3d> points, Vector3d focus, int width, int height) {
        var outward = new Vector3d(Math.sin(.35) * Math.cos(.65), Math.sin(.65), Math.cos(.35) * Math.cos(.65));
        var right = new Vector3d(Math.cos(.35), 0, -Math.sin(.35));
        var up = new Vector3d(outward).cross(right);
        double scale = height / (Math.tan(MapCamera.FOV / 2) * .85 * Math.min(width, height));
        var relative = new Vector3d(points.getFirst());
        double fit = Double.NEGATIVE_INFINITY;
        for (var point : points) {
            relative.set(point).sub(focus);
            fit = Math.max(fit, relative.dot(outward)
                    + scale * Math.max(Math.abs(relative.dot(right)), Math.abs(relative.dot(up))));
        }
        return Math.max(.2, fit);
    }
    private void geometry(int height) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var view = camera.view(); var focus = camera.focus();
        var selected = selected();
        if (!rings.isEmpty() || selected != null && player != null) {
            var lines = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            for (var ring : rings) for (int i = 1; i < ring.points().size(); i++) {
                vertex(lines, view, focus, ring.points().get(i - 1), 0xff34546a);
                vertex(lines, view, focus, ring.points().get(i), 0xff34546a);
            }
            if (selected != null && player != null) {
                vertex(lines, view, focus, player, 0xffe8bd68);
                vertex(lines, view, focus, selected.point(), 0xffe8bd68);
            }
            BufferUploader.drawWithShader(lines.buildOrThrow());
        }
        var result = new ArrayList<Pin>();
        var spheres = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (var n : nodes) {
            var at = camera.project(n.point());
            if (at.depth() <= .001) continue;
            double pixel = 2 * Math.tan(MapCamera.FOV / 2) * at.depth() / height;
            double radius = n.ship() != null ? 4 : galaxy ? 2.5 + Math.sqrt(n.system().planets()) * .5 : n.kind() == Kind.STAR ? 6 : n.kind() == Kind.GAS ? 5 : n.kind() == Kind.MOON ? 2.5 : 4;
            double size = Math.max(pixel * radius, galaxy ? 0 : extent * (n.kind() == Kind.STAR ? .018 : .007));
            result.add(new Pin(n, at, n.ship() != null ? radius : size / pixel));
            if (n.ship() == null) sphere(spheres, view, focus, n, size, false);
        }
        // There is always at least one root star, though it can be behind the camera.
        var mesh = spheres.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        pins = List.copyOf(result);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.depthMask(false);
        var glow = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (var pin : pins) if (pin.node().kind() == Kind.STAR) {
            double pixel = 2 * Math.tan(MapCamera.FOV / 2) * pin.point().depth() / height;
            sphere(glow, view, focus, pin.node(), pin.radius() * pixel * 1.8, true);
        }
        mesh = glow.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }
    private void sphere(BufferBuilder b, Matrix4f view, Vector3d focus, Node node, double radius, boolean glow) {
        var star = galaxy ? null : nodes.stream().filter(n -> n.kind() == Kind.STAR).findFirst().orElseThrow();
        var light = node.kind() == Kind.STAR ? new Vector3d(0, 1, 0) : vector(star.world()).sub(vector(node.world())).normalize();
        for (int v = 0; v < 10; v++) for (int u = 0; u < 20; u++) {
            for (int corner = 0; corner < 4; corner++) {
                double lat = Math.PI * ((v + (corner == 1 || corner == 2 ? 1 : 0)) / 10.0 - .5);
                double lon = Math.PI * 2 * (u + (corner >= 2 ? 1 : 0)) / 20;
                var normal = new Vector3d(Math.cos(lat) * Math.cos(lon), Math.sin(lat), Math.cos(lat) * Math.sin(lon));
                double shade = node.kind() == Kind.STAR ? 1 : .18 + .82 * Math.max(0, normal.dot(light));
                int color = node.kind() == Kind.GAS && v % 3 == 0 ? node.colors().low() : node.colors().high();
                color = GlobeRaster.dim(color, shade);
                if (glow) color = color & 0xffffff | 0x14000000;
                vertex(b, view, focus, normal.mul(radius).add(node.point()), color);
            }
        }
    }
    private static void vertex(BufferBuilder b, Matrix4f view, Vector3d focus, Vector3d p, int color) {
        b.addVertex(view, (float) (p.x - focus.x), (float) (p.y - focus.y), (float) (p.z - focus.z)).setColor(color);
    }
    private void overlays(GuiGraphics g, Font font, int width, int top, int bottom) {
        var labels = new MapLabels();
        var selected = selected();
        if (selected != null && player != null) {
            var range = text("range", distance(observer.distance(selected.world())));
            g.drawString(font, range, 8, top + 4, 0xffe8bd68, true);
            labels.place(new MapLabels.Rect(8, top + 4, font.width(range), font.lineHeight));
        }
        for (var pin : pins) if (!galaxy && pin.node().priority() == 0) label(g, font, labels, pin.node().label(), pin.point(),
                (int) Math.ceil(pin.radius()) + 5, 0xffffca70, width, top, bottom);
        var o = PlanetMapApi.observer();
        if (player != null && !galaxy && o != null && o.inSpace()) {
            var at = camera.project(player);
            if (at.depth() > 0 && at.x() >= 0 && at.x() < width && at.y() >= top && at.y() < bottom) {
                int x = (int) at.x(), y = (int) at.y();
                g.fill(x - 1, y - 5, x + 2, y + 6, 0xff71ffff); g.fill(x - 5, y - 1, x + 6, y + 2, 0xff71ffff);
                label(g, font, labels, text("position"), at, 7, 0xff71ffff, width, top, bottom);
            }
        }
        for (var pin : pins.stream().sorted(Comparator.comparingInt(p -> p.node().priority())).toList()) {
            var n = pin.node(); int x = (int) pin.point().x(), y = (int) pin.point().y();
            if (x < 0 || x >= width || y < top || y >= bottom) continue;
            int color = n.priority() == 0 ? 0xffffca70 : n.priority() == 1 ? 0xff71ffff : n.system() != null && n.system().home() ? 0xff91ffad : n.ship() != null ? 0xff91ffad : 0xffb7c9da;
            int r = (int) Math.ceil(pin.radius()) + 2;
            if (galaxy) {
                if (n.system().home()) g.renderOutline(x - r - 2, y - r - 2, r * 2 + 5, r * 2 + 5, 0xff91ffad);
                if (n.body() == SystemMapApi.currentSystem()) g.renderOutline(x - r, y - r, r * 2 + 1, r * 2 + 1, 0xff71ffff);
                systemLabel(g, font, labels, pin, color, width, top, bottom);
                continue;
            }
            if (n.ship() != null || n.priority() <= 1) g.renderOutline(x - r, y - r, r * 2 + 1, r * 2 + 1, color);
            if (n.priority() > 0) label(g, font, labels, n.label(), pin.point(), r + 3, color, width, top, bottom);
        }
    }
    private static void systemLabel(GuiGraphics g, Font font, MapLabels labels, Pin pin, int color, int width, int top, int bottom) {
        var name = pin.node().label(); var count = text("system_count", pin.node().system().planets());
        int size = Math.max(font.width(name), (int) Math.ceil(font.width(count) * .75));
        int height = font.lineHeight + (int) Math.ceil(font.lineHeight * .75) + 2;
        int x = (int) pin.point().x(), y = (int) pin.point().y(), radius = (int) Math.ceil(pin.radius()) + 5;
        for (int offset = 0; offset < bottom - top; offset += height + 2) for (int direction : new int[]{1, -1}) for (int side : new int[]{1, -1}) {
            int lx = Math.clamp(side > 0 ? x + radius : x - radius - size, 2, Math.max(2, width - size - 2));
            int ly = y - height / 2 + offset * direction;
            if (ly < top || ly + height >= bottom || !labels.place(new MapLabels.Rect(lx - 1, ly - 1, size + 2, height + 2))) continue;
            g.drawString(font, name, lx, ly, color, true);
            g.pose().pushPose(); g.pose().translate(lx, ly + font.lineHeight + 2, 0); g.pose().scale(.75f, .75f, 1);
            g.drawString(font, count, 0, 0, 0xff728e9c, false); g.pose().popPose();
            return;
        }
    }
    private static void label(GuiGraphics g, Font font, MapLabels labels, Component text, MapCamera.ScreenPoint p,
                              int radius, int color, int width, int top, int bottom) {
        int size = font.width(text), x = (int) p.x(), y = (int) p.y();
        for (int side : new int[]{1, -1}) for (int offset : new int[]{-4, 9, -17}) {
            int lx = side > 0 ? x + radius : x - radius - size, ly = y + offset;
            if (lx < 2 || lx + size >= width - 2 || ly < top || ly + font.lineHeight >= bottom) continue;
            if (labels.place(new MapLabels.Rect(lx - 1, ly - 1, size + 2, font.lineHeight + 2))) {
                g.drawString(font, text, lx, ly, color, true); return;
            }
        }
    }
    private Node selected() {
        var target = SystemMapApi.navigationTarget();
        var selected = nodes.stream().filter(n -> selectedShip != null ? n.ship() != null && selectedShip.equals(n.ship().id())
                : selectedBody >= 0 && n.ship() == null && selectedBody == n.body()).findFirst().orElse(null);
        return selected != null ? selected : nodes.stream().filter(n -> n.ship() != null ? n.ship().id().equals(target.ship())
                : n.body() == target.body()).findFirst().orElse(null);
    }
    static String distance(double blocks) {
        return String.format(Locale.ROOT, blocks >= 1000 ? "%.1f km" : "%.0f m", blocks >= 1000 ? blocks / 1000 : blocks);
    }
    Node pick(double x, double y) {
        return pins.stream().filter(p -> Math.hypot(p.point().x() - x, p.point().y() - y) <= Math.max(8, p.radius()))
                .min(Comparator.comparingDouble(p -> p.point().depth())).map(Pin::node).orElse(null);
    }
}
