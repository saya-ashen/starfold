package dev.starfold.space;

import static dev.starfold.flight.HudLayout.Widget.*;

import dev.starfold.flight.ArrivalState;
import dev.starfold.flight.FlightMode;
import dev.starfold.flight.HudLayout;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.ship.HullLanding;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

import java.util.Locale;

/** Small drawing functions consume only the frame snapshot and pure layout. No flight-state queries here. */
public final class FlightWidgets {
    static final int AMBER = 0xffffc857,
            CYAN = 0xff6fe3ff,
            DIM = 0xff8794a8,
            INK = 0xffdfe7f2,
            DARK = 0xb0070a12,
            RED = 0xffff7a66;

    public static void render(GuiGraphics g, FlightSnapshot s) {
        if (s == null || s.tier() == 0) return;
        var l = HudLayout.layout(g.guiWidth(), g.guiHeight());
        mode(g, s, l);
        reticle(g, s, l);
        if (s.mode() == FlightMode.CHART) {
            chart(g, s, l);
            return;
        }
        try {
            if (s.blend() < 1) {
                g.setColor(1, 1, 1, (float) (1 - s.blend()));
                speed(g, s, l);
                if (shows(s, CLOSING)) closing(g, s, l);
                if (shows(s, VELOCITY)) velocity(g, s, l);
            }
            if (s.blend() > 0) {
                g.setColor(1, 1, 1, (float) s.blend());
                arrival(g, s, l);
            }
        } finally {
            g.setColor(1, 1, 1, 1);
        }
        if (s.phase() == ArrivalState.Phase.NOTICE) entry(g, s, l);
        if (shows(s, PLANETS)) planets(g, s, l);
        if (shows(s, BEARING)) bearing(g, s, l);
        if (shows(s, THRUST)) thrust(g, s, l);
    }

    public static void view(GuiGraphics g, String label, boolean flash) {
        var l = HudLayout.layout(g.guiWidth(), g.guiHeight());
        centreText(g, label, l.width() / 2, l.view().y(), flash ? INK : DIM, l.view().width());
    }

    private static boolean shows(FlightSnapshot s, HudLayout.Widget w) {
        return HudLayout.shows(s.tier(), w);
    }

    private static String tr(String key, Object... args) {
        return I18n.get("hud.starfold.instrument_" + key, args);
    }

    private static String number(double n) {
        return HudLayout.number(n);
    }

    private static void text(GuiGraphics g, String t, int x, int y, int color, int width) {
        var f = Minecraft.getInstance().font;
        t = f.plainSubstrByWidth(t, Math.max(0, width));
        if (color != 0xff070a12) g.fill(x - 1, y - 1, x + f.width(t) + 1, y + 9, DARK);
        g.drawString(f, t, x, y, color, true);
    }

    private static void centreText(GuiGraphics g, String t, int x, int y, int color, int width) {
        var f = Minecraft.getInstance().font;
        t = f.plainSubstrByWidth(t, width);
        text(g, t, x - f.width(t) / 2, y, color, width);
    }

    private static void box(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.hLine(x, x + w - 1, y, c);
        g.hLine(x, x + w - 1, y + h - 1, c);
        g.vLine(x, y, y + h - 1, c);
        g.vLine(x + w - 1, y, y + h - 1, c);
    }

    private static void circle(GuiGraphics g, int x, int y, int radius, int c) {
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI / 24;
            int px = x + (int) Math.round(radius * Math.cos(a)),
                    py = y + (int) Math.round(radius * Math.sin(a));
            g.fill(px, py, px + 1, py + 1, c);
        }
    }

    private static void mode(GuiGraphics g, FlightSnapshot s, HudLayout.Layout l) {
        var r = l.mode();
        boolean arrival = s.mode() == FlightMode.ARRIVAL;
        String label =
                s.phase() == ArrivalState.Phase.NOTICE
                        ? tr("notice", PlanetSystem.name(s.planet()), s.notice())
                        : tr(
                                arrival
                                        ? "arrival"
                                        : s.mode() == FlightMode.CHART ? "chart" : "free",
                                PlanetSystem.name(s.planet()));
        if (arrival) {
            if (s.blend() < 1) {
                g.setColor(1, 1, 1, (float) (1 - s.blend()));
                box(g, r.x(), r.y(), r.width(), 18, CYAN);
                centreText(g, tr("free"), l.width() / 2, r.y() + 5, CYAN, r.width() - 8);
            }
            if (s.blend() > 0) {
                g.setColor(1, 1, 1, (float) s.blend());
                g.fill(r.x(), r.y(), r.right(), r.y() + 18, AMBER);
                centreText(g, label, l.width() / 2, r.y() + 5, 0xff070a12, r.width() - 8);
            }
            g.setColor(1, 1, 1, 1);
        } else {
            box(g, r.x(), r.y(), r.width(), 18, CYAN);
            centreText(g, label, l.width() / 2, r.y() + 5, CYAN, r.width() - 8);
        }
        String target =
                s.target() == null
                        ? tr(
                                "caption",
                                "—",
                                s.launchPlanet() < 0 ? "—" : PlanetSystem.name(s.launchPlanet()))
                        : SpaceNavigationClient.caption(
                                s.target().planet(),
                                s.target().rank(),
                                s.target().count(),
                                r.width());
        centreText(g, target, l.width() / 2, r.y() + 23, DIM, r.width());
        if (shows(s, TARGET) && s.target() != null && s.target().blocker() >= 0) {
            var row = HudLayout.occlusion(l);
            centreText(
                    g,
                    tr("blocked", PlanetSystem.name(s.target().blocker())),
                    l.width() / 2,
                    row.y(),
                    AMBER,
                    row.width());
        }
    }

    private static void reticle(GuiGraphics g, FlightSnapshot s, HudLayout.Layout l) {
        int x = l.width() / 2, y = l.height() / 2;
        circle(g, x, y, 7, AMBER);
        g.hLine(x - 12, x - 10, y, AMBER);
        g.hLine(x + 10, x + 12, y, AMBER);
        g.vLine(x, y - 12, y - 10, AMBER);
        g.vLine(x, y + 10, y + 12, AMBER);
        int px = x + (int) Math.round(s.pointerX()), py = y - (int) Math.round(s.pointerY());
        g.fill(px - 1, py - 1, px + 2, py + 2, CYAN);
    }

    private static void speed(GuiGraphics g, FlightSnapshot s, HudLayout.Layout l) {
        var r = l.speed();
        int x = r.x() + 18,
                bottom = r.bottom() - 26,
                top = r.y() + 12,
                h = Math.max(10, bottom - top);
        if (shows(s, SPEED_TAPE) && s.mode() != FlightMode.CHART) {
            g.vLine(x, top, bottom, DIM);
            for (int i = 0; i <= 4; i++) g.hLine(x - 4, x + 4, top + h * i / 4, DIM);
            int fill = (int) Math.round(h * HudLayout.fill(s.speed(), s.boostCap()));
            g.fill(x - 2, bottom - fill, x + 3, bottom, CYAN);
            int cruise = bottom - (int) Math.round(h * HudLayout.fill(s.cruiseCap(), s.boostCap()));
            g.hLine(x - 7, x + 7, cruise, AMBER);
            text(g, number(s.cruiseCap()), x + 10, cruise - 4, AMBER, r.width() - 28);
            text(g, number(s.boostCap()), x + 10, top - 4, DIM, r.width() - 28);
            if (s.boosted()) {
                g.fill(r.x(), r.y() - 6, r.x() + 44, r.y() + 5, AMBER);
                text(g, tr("boost"), r.x() + 3, r.y() - 5, 0xff070a12, 40);
            }
            text(g, tr("cap", number(s.activeCap())), r.x(), r.y() - 18, DIM, r.width());
        }
        big(g, number(s.speed()), r.x(), bottom + 4, INK);
        text(
                g,
                tr("relative", PlanetSystem.name(s.relativeTo())),
                r.x(),
                bottom + 18,
                DIM,
                r.width());
    }

    private static void big(GuiGraphics g, String t, int x, int y, int c) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(1.4f, 1.4f, 1);
        text(g, t, 0, 0, c, 62);
        g.pose().popPose();
    }

    private static void closing(GuiGraphics g, FlightSnapshot s, HudLayout.Layout l) {
        var r = l.closing();
        int x = r.right() - 18,
                top = r.y() + 12,
                bottom = r.bottom() - 26,
                mid = (top + bottom) / 2,
                half = Math.max(1, (bottom - top) / 2);
        g.vLine(x, top, bottom, DIM);
        g.hLine(x - 6, x + 6, mid, DIM);
        int fill = (int) Math.round(half * HudLayout.fill(Math.abs(s.closing()), s.boostCap()));
        if (s.target() != null) {
            if (s.closing() >= 0) g.fill(x - 2, mid - fill, x + 3, mid, AMBER);
            else g.fill(x - 2, mid, x + 3, mid + fill, AMBER);
        }
        String value = s.target() == null ? "—" : String.format(Locale.ROOT, "%+.0f", s.closing());
        centreText(g, value, r.x() + r.width() / 2, bottom + 5, INK, r.width());
        centreText(g, tr("closing"), r.x() + r.width() / 2, bottom + 18, DIM, r.width());
    }

    private static void planets(GuiGraphics g, FlightSnapshot s, HudLayout.Layout l) {
        for (var t : s.planets()) {
            var m = t.marker();
            int x = (int) Math.round(m.x() * l.width()), y = (int) Math.round(m.y() * l.height());
            boolean selected = s.target() != null && t.planet() == s.target().planet();
            // Fixed instruments own their rectangles. Small non-target contacts use the inner side
            // boundary.
            if (m.edge()) {
                x = Math.clamp(x, l.speed().right() + 12, l.closing().x() - 12);
                y = Math.clamp(y, 54, l.height() - 76);
            }
            if (!selected
                    && !m.edge()
                    && x > l.centre().x() - 8
                    && x < l.centre().right() + 8
                    && y > l.centre().y() - 8
                    && y < l.centre().bottom() + 8) continue;
            int color = selected ? AMBER : DIM;
            if (m.edge()) SpaceNavigationClient.arrow(g, x, y, m.angle(), color);
            else if (selected) {
                for (int dx : new int[] {-1, 1})
                    for (int dy : new int[] {-1, 1}) {
                        int bx = x + dx * 12, by = y + dy * 12;
                        for (int i = 0; i <= 5; i++) {
                            if (t.blocker() >= 0 && i % 4 >= 2) continue;
                            g.fill(bx - dx * i, by, bx - dx * i + 1, by + 1, color);
                            g.fill(bx, by - dy * i, bx + 1, by - dy * i + 1, color);
                        }
                    }
            } else
                for (int i = -4; i <= 4; i++) {
                    int d = 4 - Math.abs(i);
                    g.fill(x + i, y - d, x + i + 1, y - d + 1, color);
                    g.fill(x + i, y + d, x + i + 1, y + d + 1, color);
                }
            text(
                    g,
                    PlanetSystem.name(t.planet()),
                    Math.min(l.width() - 30, x + (selected ? 16 : 7)),
                    y - 4,
                    color,
                    24);
        }
        if (s.target() != null) {
            var t = s.target();
            var r = l.closing();
            text(
                    g,
                    PlanetSystem.name(t.planet()) + " · " + tr("distance", number(t.distance())),
                    r.x(),
                    44,
                    AMBER,
                    r.width());
            text(g, t.eta(), r.x(), 55, AMBER, r.width());
        }
    }

    private static void velocity(GuiGraphics g, FlightSnapshot s, HudLayout.Layout l) {
        var m = s.velocityMarker();
        if (m == null) return;
        int x = (int) Math.round(m.x() * l.width()), y = (int) Math.round(m.y() * l.height());
        if (m.edge()) {
            x = Math.clamp(x, l.speed().right() + 12, l.closing().x() - 12);
            y = Math.clamp(y, 54, l.height() - 76);
        }
        circle(g, x, y, 5, CYAN);
        g.hLine(x - 9, x - 6, y, CYAN);
        g.hLine(x + 6, x + 9, y, CYAN);
        g.vLine(x, y - 9, y - 6, CYAN);
        if (s.retrograde())
            for (int i = -2; i <= 2; i++) {
                g.fill(x + i, y + i, x + i + 1, y + i + 1, CYAN);
                g.fill(x + i, y - i, x + i + 1, y - i + 1, CYAN);
            }
    }

    private static void bearing(GuiGraphics g, FlightSnapshot s, HudLayout.Layout l) {
        var r = l.bearing();
        int x = r.x() + 26, y = r.y() + 31;
        circle(g, x, y, 16, DIM);
        var d = s.referenceDirection();
        double n = Math.max(1e-12, d.length());
        int dx = (int) Math.round(13 * d.x() / n), dy = (int) Math.round(-13 * d.z() / n);
        for (int i = 0; i <= 12; i++) {
            int px = x + dx * i / 12, py = y + dy * i / 12;
            g.fill(px, py, px + 1, py + 1, AMBER);
        }
        g.fill(x + dx - 1, y + dy - 1, x + dx + 2, y + dy + 2, AMBER);
        text(
                g,
                tr("bearing", PlanetSystem.name(s.referencePlanet())),
                r.x(),
                r.y(),
                DIM,
                r.width());
        text(g, d.y() > 0 ? "+" : "−", x + 20, y - 4, AMBER, 10);
    }

    private static void thrust(GuiGraphics g, FlightSnapshot s, HudLayout.Layout l) {
        var r = l.thrust();
        var t = s.thrust();
        int x = r.x() + 58, y = r.y() + 12;
        lamp(g, x - 8, y - 12, "W", t.forward());
        lamp(g, x - 8, y + 1, "S", t.back());
        lamp(g, x - 30, y - 6, "L", t.left());
        lamp(g, x + 14, y - 6, "R", t.right());
        lamp(g, x - 56, y - 12, "+", t.up());
        var lift = t.lift();
        if (lift != null) {
            int color = lift.insufficient() ? RED : CYAN;
            g.fill(x - 57, y - 1, x - 42, y + 1, lift.insufficient() ? RED : DIM);
            g.fill(x - 57, y - 1, x - 57 + (int) Math.round(15 * lift.up()), y + 1, color);
            if (lift.insufficient()) text(g, tr("insufficient_lift"), r.x(), r.y() - 10, RED, 130);
        }
        lamp(g, x - 56, y + 1, "−", t.down());
        if (s.arrival() != null && s.blend() > 0) {
            g.setColor(1, 1, 1, (float) s.blend());
            var d = s.arrival().disabled();
            if (d.forward()) disabled(g, x - 8, y - 12);
            if (d.back()) disabled(g, x - 8, y + 1);
            if (d.left()) disabled(g, x - 30, y - 6);
            if (d.right()) disabled(g, x + 14, y - 6);
            if (d.down()) disabled(g, x - 56, y + 1);
            text(g, tr("radial"), x + 34, y - 8, RED, Math.max(0, l.width() - x - 38));
            g.setColor(1, 1, 1, 1);
        }
    }

    private static void lamp(GuiGraphics g, int x, int y, String label, boolean on) {
        if (on) g.fill(x, y, x + 16, y + 11, CYAN);
        else box(g, x, y, 16, 11, DIM);
        text(g, label, x + 5, y + 1, on ? 0xff070a12 : DIM, 10);
    }

    private static void disabled(GuiGraphics g, int x, int y) {
        for (int i = 0; i < 16; i += 4) {
            g.hLine(x + i, x + i + 2, y, RED);
            g.hLine(x + i, x + i + 2, y + 10, RED);
        }
        g.vLine(x, y, y + 10, RED);
        g.vLine(x + 15, y, y + 10, RED);
        line(g, x + 1, y + 9, x + 14, y + 1, RED);
    }

    private static void line(GuiGraphics g, int x, int y, int a, int b, int color) {
        int steps = Math.max(1, Math.max(Math.abs(a - x), Math.abs(b - y)));
        for (int i = 0; i <= steps; i++) {
            int px = x + (a - x) * i / steps, py = y + (b - y) * i / steps;
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    /** Signed tape: the needle and red cap share exactly the same scale and zero. */
    private static void signed(
            GuiGraphics g,
            HudLayout.Rect r,
            double value,
            double limit,
            double cap,
            String label,
            boolean tape) {
        int x = r.x() + 18,
                top = r.y() + 12,
                bottom = r.bottom() - 26,
                mid = (top + bottom) / 2,
                half = Math.max(1, (bottom - top) / 2);
        if (tape) {
            g.vLine(x, top, bottom, DIM);
            g.hLine(x - 5, x + 5, mid, DIM);
            int y = mid - (int) Math.round(half * Math.clamp(value / cap, -1, 1));
            g.fill(x - 2, Math.min(mid, y), x + 3, Math.max(mid, y) + 1, CYAN);
            SpaceNavigationClient.arrow(g, x - 5, y, 0, CYAN);
            text(g, number(cap), x + 10, top - 4, DIM, r.width() - 30);
            if (Double.isFinite(limit)) {
                text(g, tr("limit"), r.x(), r.y() - 2, RED, r.width());
                int ly = mid - (int) Math.round(half * Math.clamp(limit / cap, -1, 1));
                g.hLine(x - 7, x + 7, ly, RED);
                text(
                        g,
                        number(Math.abs(limit)),
                        x + 10,
                        Math.abs(ly - top) < 10 ? ly + 6 : ly - 4,
                        RED,
                        r.width() - 30);
            }
        }
        big(g, number(value), r.x(), bottom + 4, INK);
        text(g, label, r.x(), bottom + 18, DIM, r.width());
    }

    private static void height(
            GuiGraphics g, HudLayout.Rect r, double value, double cap, String label, boolean tape) {
        int x = r.right() - 18,
                top = r.y() + 12,
                bottom = r.bottom() - 26,
                h = Math.max(1, bottom - top);
        if (tape && Double.isFinite(value)) {
            g.vLine(x, top, bottom, DIM);
            for (int i = 0; i <= 4; i++) g.hLine(x - 4, x + 4, top + h * i / 4, DIM);
            int y = bottom - (int) Math.round(h * HudLayout.fill(value, cap));
            g.fill(x - 2, y, x + 3, bottom, CYAN);
            SpaceNavigationClient.arrow(g, x + 5, y, Math.PI, AMBER);
            g.hLine(x - 7, x + 7, bottom, AMBER);
            text(g, "0", r.x(), bottom - 14, AMBER, 8);
            text(g, number(cap), r.x(), top - 4, DIM, r.width() - 30);
        }
        big(g, number(value), r.x(), bottom + 4, AMBER);
        text(g, label, r.x(), bottom + 18, DIM, r.width());
    }

    private static void arrival(GuiGraphics g, FlightSnapshot s, HudLayout.Layout l) {
        var a = s.arrival();
        if (a == null) return;
        boolean tape = s.tier() >= 2;
        signed(
                g,
                l.speed(),
                a.closing(),
                a.limit(),
                Math.max(s.boostCap(), a.limit()),
                tr("closing"),
                tape);
        height(
                g,
                l.closing(),
                a.height(),
                Math.max(1000, Math.ceil(a.height() / 1000) * 1000),
                tr("entry_height"),
                tape);
        if (tape) {
            var m = a.entry();
            int
                    x =
                            Math.clamp(
                                    (int) Math.round(m.x() * l.width()),
                                    l.speed().right() + 14,
                                    l.closing().x() - 14),
                    y = Math.clamp((int) Math.round(m.y() * l.height()), 64, l.height() - 78);
            if (m.edge()) SpaceNavigationClient.arrow(g, x, y, m.angle(), AMBER);
            else {
                circle(g, x, y, 9, AMBER);
                g.hLine(x - 4, x + 4, y, AMBER);
            }
            text(g, tr("entry"), x + 12, y + 12, AMBER, 60);
            centreText(
                    g, tr("alignment", number(a.alignment() * 100)), l.width() / 2, 56, AMBER, 180);
        }
    }

    private static void entry(GuiGraphics g, FlightSnapshot s, HudLayout.Layout l) {
        if (s.arrival() == null) return;
        var m = s.arrival().entry();
        int
                x =
                        Math.clamp(
                                (int) Math.round(m.x() * l.width()),
                                l.speed().right() + 14,
                                l.closing().x() - 14),
                y = Math.clamp((int) Math.round(m.y() * l.height()), 64, l.height() - 78);
        if (m.edge()) SpaceNavigationClient.arrow(g, x, y, m.angle(), AMBER);
        else {
            circle(g, x, y, 9, AMBER);
            g.hLine(x - 4, x + 4, y, AMBER);
        }
        text(g, tr("entry"), x + 12, y + 12, AMBER, 60);
    }

    private static void chart(GuiGraphics g, FlightSnapshot s, HudLayout.Layout l) {
        boolean tape = s.tier() >= 2;
        var a = s.landing();
        double limit = a == null ? Double.NaN : a.limit();
        signed(
                g,
                l.speed(),
                s.tier() == 1 ? s.speed() : s.vertical(),
                s.tier() >= 3 ? -limit : Double.NaN,
                Math.max(160, Math.ceil(Math.abs(s.vertical()) / 80) * 80),
                tr(s.tier() == 1 ? "speed" : "vertical"),
                tape);
        height(g, l.closing(), s.clearance(), 300, tr("clearance"), tape);
        if (tape) home(g, s, l);
        if (s.tier() < 3 || a == null) return;
        if (HullLanding.showSurface(s.tier(), s.mode(), s.clearance())) {
            var site = a.site();
            int color =
                    (site.kind() == HullLanding.Kind.LAND || site.kind() == HullLanding.Kind.PAD)
                                    && site.deployable()
                            ? CYAN
                            : RED;
            centreText(
                    g,
                    tr("below_" + site.kind().name().toLowerCase(Locale.ROOT)),
                    l.width() / 2,
                    54,
                    color,
                    Math.min(180, l.width() - 32));
            centreText(
                    g,
                    tr(site.deployable() ? "stair_yes" : "stair_no"),
                    l.width() / 2,
                    65,
                    color,
                    Math.min(180, l.width() - 32));
        }
        int x = l.width() / 2, y = l.height() / 2;
        if (HullLanding.showSurface(s.tier(), s.mode(), s.clearance())) {
            var aid = a.site().aid();
            if (aid.status() != HullLanding.AidStatus.OFF)
                centreText(
                        g,
                        tr(
                                aid.status() == HullLanding.AidStatus.FOUND
                                        ? "landing_site"
                                        : aid.status() == HullLanding.AidStatus.NONE
                                                ? "landing_none"
                                                : "landing_search",
                                number(aid.distance())),
                        x,
                        76,
                        AMBER,
                        Math.min(220, l.width() - 32));
            if (aid.status() == HullLanding.AidStatus.FOUND && aid.distance() > .01) {
                double dx = aid.right() / aid.distance(), dy = -aid.forward() / aid.distance();
                int ax = x + (int) Math.round(dx * 34), ay = y + (int) Math.round(dy * 34);
                line(g, x + (int) (dx * 12), y + (int) (dy * 12), ax, ay, AMBER);
                line(g, ax, ay, ax - (int) (dx * 7 + dy * 4), ay - (int) (dy * 7 - dx * 4), AMBER);
                line(g, ax, ay, ax - (int) (dx * 7 - dy * 4), ay - (int) (dy * 7 + dx * 4), AMBER);
            }
        }
        double h = Math.hypot(a.drift().x(), a.drift().z());
        if (h < .5) g.fill(x - 2, y - 2, x + 3, y + 3, CYAN);
        else {
            double scale = Math.min(42, h * 2) / Math.max(1e-12, h);
            int dx = (int) Math.round(a.drift().x() * scale),
                    dy = (int) Math.round(-a.drift().z() * scale);
            line(g, x, y, x + dx, y + dy, CYAN);
            g.fill(x + dx - 2, y + dy - 2, x + dx + 3, y + dy + 3, CYAN);
        }
        var r = l.bearing();
        int cx = r.x() + 32, cy = r.y() + 30;
        double roll = a.roll(),
                w = 20,
                d =
                        Math.clamp(20 * a.length() / Math.max(1, a.width()), 8, 22)
                                * Math.max(.35, Math.cos(a.pitch()));
        int[] xs = new int[4], ys = new int[4];
        for (int i = 0; i < 4; i++) {
            double px = (i == 0 || i == 3 ? -w : w), py = i < 2 ? -d : d;
            xs[i] = cx + (int) Math.round(px * Math.cos(roll) - py * Math.sin(roll));
            ys[i] = cy + (int) Math.round(px * Math.sin(roll) + py * Math.cos(roll));
        }
        g.hLine(cx - 25, cx + 25, cy, DIM);
        for (int i = 0; i < 4; i++) line(g, xs[i], ys[i], xs[(i + 1) % 4], ys[(i + 1) % 4], AMBER);
        text(g, tr("tilt", number(Math.toDegrees(a.pitch()))), r.x(), r.y(), DIM, r.width());
        text(
                g,
                tr("drift", String.format(Locale.ROOT, "%.1f", s.horizontal())),
                l.width() / 2 - 50,
                l.thrust().y() + 5,
                CYAN,
                100);
    }

    private static void home(GuiGraphics g, FlightSnapshot s, HudLayout.Layout l) {
        var home = s.home();
        if (home == null) return;
        int x = l.width() / 2, y = l.height() - 66;
        boolean here = home.distance() <= 16;
        double angle = Math.toRadians(home.angle()) - Math.PI / 2;
        String label =
                home.name() + " · " + (here ? tr("here") : tr("distance", number(home.distance())));
        int arrow = x - Math.min(150, Minecraft.getInstance().font.width(label)) / 2 - 12;
        if (here) circle(g, arrow, y + 5, 3, AMBER);
        else SpaceNavigationClient.arrow(g, arrow, y + 5, angle, AMBER);
        centreText(g, label, x, y, AMBER, 150);
        var m = home.marker();
        if (m != null && !m.edge()) {
            int mx = (int) Math.round(m.x() * l.width()), my = (int) Math.round(m.y() * l.height());
            if (mx > l.speed().right() + 8
                    && mx < l.closing().x() - 8
                    && my > 60
                    && my < l.height() - 80) {
                circle(g, mx, my, 4, AMBER);
                g.hLine(mx - 2, mx + 2, my, AMBER);
            }
        }
    }

    private FlightWidgets() {}
}
