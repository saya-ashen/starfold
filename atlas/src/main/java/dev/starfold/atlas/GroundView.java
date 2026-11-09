package dev.starfold.atlas;

import dev.starfold.api.PlanetMapApi;
import dev.starfold.api.SystemMapApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.joml.Quaternionf;

/** Client-thread ground-map component. Owns textures, not widgets, navigation or a Screen. */
public final class GroundView implements AutoCloseable {
    public record Pick(int face, double u, double v) {}
    private final PlanetMapApi.Body body;
    private final GroundRaster raster;
    private GroundChart chart;
    private double centreU, centreV, scale = 2, angle;
    private int north, x, y, width, height, sampleStep = 1;
    private boolean northUp = true, following = true;
    private Pick target;

    public GroundView(PlanetMapApi.Body body) {
        this.body = body; raster = new GroundRaster(body);
        var o = observer();
        chart = new GroundChart(body.faceSize(), o == null ? 0 : o.face());
        centreU = o == null ? body.faceSize() / 2. : o.u();
        centreV = o == null ? body.faceSize() / 2. : o.v();
    }
    private PlanetMapApi.Observer observer() {
        var o = PlanetMapApi.observer();
        return o != null && o.body() == body.index() && !o.inSpace() ? o : null;
    }
    private double minimumScale() { return Math.max(.125, Math.max(width, height) / (body.faceSize() * 1.25)); }
    private void update(PlanetMapApi.Observer o) {
        if (o != null && chart.root != o.face()) {
            var tile = chart.face(o.face());
            if (tile == null) { north = 0; following = true; }
            else {
                var p = tile.local(body.faceSize(), centreU, centreV);
                centreU = p.x(); centreV = p.y();
                // Parallel transport avoids a snap; reopening the component resets north to local -v.
                north = chart.northAfter(o.face(), north);
            }
            chart = new GroundChart(body.faceSize(), o.face());
        }
        if (o != null && following) { centreU = o.u(); centreV = o.v(); }
        angle = northUp || o == null ? north * Math.PI / 2 : Math.PI - Math.toRadians(o.heading());
    }
    public boolean drag(double dx, double dy) {
        var d = rotate(dx / scale, dy / scale, -angle);
        centreU -= d.x(); centreV -= d.y(); following = false;
        return true;
    }
    /** Negative at the minimum returns false so the owner can transition to the globe. */
    public boolean scroll(double amount) {
        double minimum = minimumScale();
        if (amount < 0 && scale <= minimum + 1e-9) return false;
        scale = Math.clamp(scale * Math.pow(1.2, amount), minimum, Math.max(8, minimum));
        return true;
    }
    public void recentre() { following = true; update(observer()); }
    public void northUp(boolean on) { northUp = on; update(observer()); }
    /** Optional local selection, e.g. a ship. Null restores Starfold's active waypoint. */
    public void target(Pick target) { this.target = target; }
    public Pick pick(double mouseX, double mouseY) {
        if (mouseX < x || mouseY < y || mouseX >= x + width || mouseY >= y + height || !Atlas.surveys.available()) return null;
        var p = world(mouseX, mouseY);
        var hit = chart.pick(p.x(), p.y());
        if (hit == null || !raster.present(new GroundRaster.Key(hit.face(), (int) hit.u() / (16 * sampleStep),
                (int) hit.v() / (16 * sampleStep), sampleStep, Math.floorMod(north + chart.face(hit.face()).turns(), 4)))) return null;
        var fog = Atlas.surveys.record(body);
        boolean explored = fog.explored(hit.face(), (int) hit.u() / 4, (int) hit.v() / 4);
        if (!explored && !fog.revealed()) return null;
        var surface = PlanetMapApi.surface(body.index(), explored ? 4 : 64);
        boolean present = explored && PlanetMapApi.loadedSurface(body.index(), hit.face(), (int) hit.u(), (int) hit.v()) != null
                || surface != null && surface.cell(hit.face(), (int) hit.u() / surface.step(), (int) hit.v() / surface.step()) != null;
        return present ? new Pick(hit.face(), hit.u(), hit.v()) : null;
    }
    public void render(GuiGraphics g, int x, int y, int width, int height, float partialTick) {
        this.x = x; this.y = y; this.width = width; this.height = height;
        var o = observer(); update(o);
        scale = Math.max(scale, minimumScale());
        g.enableScissor(x, y, x + width, y + height);
        g.fill(x, y, x + width, y + height, 0xff080f18);
        anomalies(g);
        var labels = new MapLabels();
        labels.place(new MapLabels.Rect(x, y + height - 27, width, 27));
        if (Atlas.surveys.available()) {
            raster.begin(Atlas.surveys.record(body), PlanetMapApi.surface(body.index(), 4),
                    PlanetMapApi.surface(body.index(), 64), Minecraft.getInstance().level.getGameTime());
            terrain(g);
            raster.end();
        }
        grid(g, labels, false);
        overlays(g, labels, o);
        grid(g, labels, true);
        readout(g);
        g.disableScissor();
    }
    private void terrain(GuiGraphics g) {
        int step = scale >= 2 ? 1 : 4;
        while (GroundRaster.SIDE * step * scale < Math.max(32, Math.max(width, height) / 20.)) step *= 2;
        sampleStep = step;
        int span = GroundRaster.SIDE * step, count = (body.faceSize() + span - 1) / span;
        for (var t : chart.tiles) {
            double[] bounds = bounds(t);
            int left = Math.max(0, (int) Math.floor(bounds[0] / span)), top = Math.max(0, (int) Math.floor(bounds[1] / span));
            int right = Math.min(count - 1, (int) Math.floor(bounds[2] / span)), bottom = Math.min(count - 1, (int) Math.floor(bounds[3] / span));
            for (int v = top; v <= bottom; v++) for (int u = left; u <= right; u++) {
                var centre = t.project(body.faceSize(), (u + .5) * span, (v + .5) * span);
                var p = screen(centre);
                double radius = span * scale * .71;
                if (p.x() + radius < x || p.y() + radius < y || p.x() - radius > x + width || p.y() - radius > y + height) continue;
                var id = raster.tile(new GroundRaster.Key(t.face(), u, v, step, Math.floorMod(north + t.turns(), 4)));
                if (id == null) continue;
                g.pose().pushPose();
                g.pose().translate(p.x(), p.y(), 0);
                g.pose().mulPose(new Quaternionf().rotationZ((float) (angle + t.turns() * Math.PI / 2)));
                g.pose().scale((float) (step * scale), (float) (step * scale), 1);
                int w = Math.min(16, (body.faceSize() - u * span) / step);
                int h = Math.min(16, (body.faceSize() - v * span) / step);
                g.blit(id, -8, -8, 0, 0, w, h, 16, 16);
                g.pose().popPose();
            }
        }
    }
    private double[] bounds(GroundChart.Tile tile) {
        double minU = Double.POSITIVE_INFINITY, minV = minU, maxU = -minU, maxV = -minU;
        for (int a : new int[]{x, x + width}) for (int b : new int[]{y, y + height}) {
            var w = world(a, b); var p = tile.local(body.faceSize(), w.x(), w.y());
            minU = Math.min(minU, p.x()); minV = Math.min(minV, p.y());
            maxU = Math.max(maxU, p.x()); maxV = Math.max(maxV, p.y());
        }
        return new double[]{minU, minV, maxU, maxV};
    }
    private void anomalies(GuiGraphics g) {
        int n = body.faceSize();
        for (int a : new int[]{-1, 1}) for (int b : new int[]{-1, 1}) {
            double u = a * n, v = b * n;
            var p = screen(new GroundChart.Point(u + n / 2., v + n / 2.));
            double radius = n * scale * .71;
            if (p.x() + radius < x || p.y() + radius < y || p.x() - radius > x + width || p.y() - radius > y + height) continue;
            g.pose().pushPose();
            g.pose().translate(p.x(), p.y(), 0);
            g.pose().mulPose(new Quaternionf().rotationZ((float) angle));
            g.pose().scale((float) scale, (float) scale, 1);
            g.fill(-n / 2, -n / 2, n / 2, n / 2, 0xff161b25);
            g.pose().popPose();
            int stride = Math.max(1, (int) Math.ceil(12 / scale));
            var bounds = bounds(new GroundChart.Tile(chart.root, a, b, 0));
            int first = Math.max(stride, (int) Math.ceil((bounds[0] + bounds[1]) / stride) * stride);
            double last = Math.min(2 * n, bounds[2] + bounds[3]);
            for (int d = first; d < last; d += stride) {
                var start = screen(new GroundChart.Point(u + Math.max(0, d - n), v + Math.min(n, d)));
                var end = screen(new GroundChart.Point(u + Math.min(n, d), v + Math.max(0, d - n)));
                line(g, start, end, 0xff34303c);
            }
            if (inside(p.x(), p.y(), 8)) label(g, new MapLabels(), text("ground.anomaly"), p, 0xffa998a5);
        }
    }
    private void grid(GuiGraphics g, MapLabels labels, boolean names) {
        int spacing = 16;
        while (spacing * scale < 70) spacing *= 2;
        for (var t : chart.tiles) {
            var b = bounds(t);
            double loU = Math.max(0, b[0]), loV = Math.max(0, b[1]);
            double hiU = Math.min(body.faceSize(), b[2]), hiV = Math.min(body.faceSize(), b[3]);
            if (loU > hiU || loV > hiV) continue;
            for (int u = (int) Math.ceil(loU / spacing) * spacing; u <= hiU; u += spacing) {
                var p = screen(t.project(body.faceSize(), u, loV));
                var q = screen(t.project(body.faceSize(), u, hiV));
                if (!names) line(g, p, q, 0x383b6570);
                else label(g, labels, text("ground.grid_u", u), screen(t.project(body.faceSize(), u, (loV + hiV) / 2)), 0xff819da6);
            }
            for (int v = (int) Math.ceil(loV / spacing) * spacing; v <= hiV; v += spacing) {
                if (!names) line(g, screen(t.project(body.faceSize(), loU, v)), screen(t.project(body.faceSize(), hiU, v)), 0x383b6570);
                else label(g, labels, text("ground.grid_v", v), screen(t.project(body.faceSize(), (loU + hiU) / 2, v)), 0xff819da6);
            }
        }
    }
    private void overlays(GuiGraphics g, MapLabels labels, PlanetMapApi.Observer o) {
        var markers = PlanetMapApi.markers();
        var ships = SystemMapApi.ships();
        // Reserve icons before placing names, so lower-priority names never cover instruments.
        for (var m : markers) if (m.body() == body.index()) reserve(labels, project(m.face(), m.u(), m.v()));
        for (var s : ships) if (s.body() == body.index() && s.face() >= 0) reserve(labels, project(s.face(), s.u(), s.v()));
        GroundChart.Point player = o == null ? null : project(o.face(), o.u(), o.v());
        if (player != null) {
            reserve(labels, player);
            double heading = Math.toRadians(o.heading()) + angle;
            arrow(g, player, -Math.sin(heading), Math.cos(heading), 0xff71ffff);
        }
        var nav = PlanetMapApi.navigationTarget();
        Pick destination = target != null ? target : nav != null && nav.body() == body.index() ? new Pick(nav.face(), nav.u(), nav.v()) : null;
        if (destination != null) {
            var from = o == null ? new GroundChart.Point(centreU, centreV) : new GroundChart.Point(o.u(), o.v());
            var end = screen(chart.destination(destination.face(), destination.u(), destination.v(), from));
            var start = player == null ? screen(from) : player;
            line(g, start, end, 0xffc9b477);
            var edge = edge(start, end, 10);
            if (edge != null) {
                double d = Math.hypot(end.x() - start.x(), end.y() - start.y());
                arrow(g, edge, (end.x() - start.x()) / d, (end.y() - start.y()) / d, 0xffffca70);
                reserve(labels, edge);
            }
        }
        for (var m : markers) if (m.body() == body.index()) {
            var p = project(m.face(), m.u(), m.v());
            if (p == null || !inside(p.x(), p.y(), 4)) continue;
            int a = (int) p.x(), b = (int) p.y();
            if (m.launch()) {
                line(g, new GroundChart.Point(a - 4, b + 3), new GroundChart.Point(a, b - 4), 0xffffca70);
                line(g, new GroundChart.Point(a, b - 4), new GroundChart.Point(a + 4, b + 3), 0xffffca70);
                g.hLine(a - 4, a + 4, b + 3, 0xffffca70);
            } else g.renderOutline(a - 2, b - 2, 5, 5, 0xffffca70);
            label(g, labels, m.launch() ? text("launch") : Component.literal(m.name()), p, 0xffffca70);
        }
        for (var s : ships) if (s.body() == body.index() && s.face() >= 0) {
            var p = project(s.face(), s.u(), s.v());
            if (p == null || !inside(p.x(), p.y(), 4)) continue;
            g.renderOutline((int) p.x() - 3, (int) p.y() - 3, 7, 7, 0xff91ffad);
            g.hLine((int) p.x() - 4, (int) p.x() + 4, (int) p.y(), 0xff91ffad);
            label(g, labels, Component.literal(s.name()), p, 0xff91ffad);
        }
    }
    private static void reserve(MapLabels labels, GroundChart.Point p) {
        if (p != null) labels.place(new MapLabels.Rect((int) p.x() - 5, (int) p.y() - 5, 11, 11));
    }
    private void label(GuiGraphics g, MapLabels labels, Component text, GroundChart.Point p, int color) {
        var font = Minecraft.getInstance().font;
        int w = font.width(text);
        for (int dy : new int[]{-4, 9, -17}) {
            int a = (int) p.x() + 7, b = (int) p.y() + dy;
            if (a < x || a + w >= x + width || b < y || b + font.lineHeight >= y + height) continue;
            if (labels.place(new MapLabels.Rect(a - 1, b - 1, w + 2, font.lineHeight + 2))) {
                g.drawString(font, text, a, b, color, true); return;
            }
        }
    }
    private void readout(GuiGraphics g) {
        var font = Minecraft.getInstance().font;
        var at = chart.pick(centreU, centreV);
        if (at != null) g.drawString(font, text("coordinates", text("face." + at.face()), (int) at.u(), (int) at.v()), x + 6, y + height - 24, 0xffaad4dd, true);
        int blocks = 1;
        while (blocks * scale < 60) blocks *= 2;
        int length = (int) Math.round(blocks * scale), bottom = y + height - 5;
        g.hLine(x + 6, x + 6 + length, bottom, 0xffaad4dd);
        g.vLine(x + 6, bottom - 4, bottom, 0xffaad4dd); g.vLine(x + 6 + length, bottom - 4, bottom, 0xffaad4dd);
        g.drawString(font, text("ground.scale", blocks), x + 12 + length, bottom - 6, 0xffaad4dd, true);
        var mode = text(northUp ? "ground.north" : "ground.heading");
        g.drawString(font, mode, x + width - font.width(mode) - 6, y + height - 12, 0xff71dce0, true);
    }
    private GroundChart.Point project(int face, double u, double v) {
        var t = chart.face(face);
        return t == null ? null : screen(t.project(body.faceSize(), u, v));
    }
    private GroundChart.Point screen(GroundChart.Point p) {
        var d = rotate(p.x() - centreU, p.y() - centreV, angle);
        return new GroundChart.Point(x + width / 2. + d.x() * scale, y + height / 2. + d.y() * scale);
    }
    private GroundChart.Point world(double x, double y) {
        var d = rotate((x - this.x - width / 2.) / scale, (y - this.y - height / 2.) / scale, -angle);
        return new GroundChart.Point(centreU + d.x(), centreV + d.y());
    }
    private static GroundChart.Point rotate(double x, double y, double angle) {
        return new GroundChart.Point(x * Math.cos(angle) - y * Math.sin(angle), x * Math.sin(angle) + y * Math.cos(angle));
    }
    private boolean inside(double a, double b, int margin) { return a >= x + margin && a < x + width - margin && b >= y + margin && b < y + height - margin; }
    /** Ray/rectangle intersection, including when panning has taken the player offscreen. */
    private GroundChart.Point edge(GroundChart.Point start, GroundChart.Point end, int margin) {
        if (inside(end.x(), end.y(), margin)) return null;
        double[] range = GroundChart.clip(start, end, x, y, width, height, margin);
        return range == null ? null : new GroundChart.Point(start.x() + (end.x() - start.x()) * range[1], start.y() + (end.y() - start.y()) * range[1]);
    }
    private void line(GuiGraphics g, GroundChart.Point a, GroundChart.Point b, int color) {
        var range = GroundChart.clip(a, b, x, y, width, height, 0);
        if (range == null) return;
        double dx = b.x() - a.x(), dy = b.y() - a.y();
        double ax = a.x() + dx * range[0], ay = a.y() + dy * range[0];
        dx *= range[1] - range[0]; dy *= range[1] - range[0];
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        for (int i = 0; i <= steps; i++) {
            double f = steps == 0 ? 0 : (double) i / steps;
            int u = (int) Math.round(ax + dx * f), v = (int) Math.round(ay + dy * f);
            g.fill(u, v, u + 1, v + 1, color);
        }
    }
    private void arrow(GuiGraphics g, GroundChart.Point p, double dx, double dy, int color) {
        var tip = new GroundChart.Point(p.x() + dx * 7, p.y() + dy * 7);
        line(g, new GroundChart.Point(p.x() - dx * 4, p.y() - dy * 4), tip, color);
        for (int sign : new int[]{-1, 1}) line(g, tip,
                new GroundChart.Point(p.x() + dx * 2 - sign * dy * 4, p.y() + dy * 2 + sign * dx * 4), color);
    }
    private static Component text(String key, Object... args) { return Component.translatable("atlas.starfold_atlas." + key, args); }
    @Override public void close() { raster.close(); }
}
