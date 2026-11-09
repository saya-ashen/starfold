package dev.starfold.atlas;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.starfold.api.PlanetMapApi;
import dev.starfold.api.SystemMapApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;

/** Unit-radius cube sphere. Owns only its mesh/textures, not the camera, navigation or survey records. */
final class GlobeView implements AutoCloseable {
    private static final int GRID = 32;
    private static final Vector3d ORIGIN = new Vector3d();
    private final PlanetMapApi.Body body;
    private final Vector3d[][][] mesh = new Vector3d[6][GRID + 1][GRID + 1];
    private final DynamicTexture[] textures = new DynamicTexture[6];
    private final ResourceLocation[] ids = new ResourceLocation[6];
    private int revision = -1;
    private double progress = -1, coarseProgress = -1;
    private long lastRaster;
    private List<Pin> pins = List.of();
    record Pin(MapCamera.ScreenPoint point, Component label, int color, PlanetMapApi.Marker marker, SystemMapApi.Ship ship) {}

    GlobeView(PlanetMapApi.Body body) {
        this.body = body;
        for (int f = 0; f < 6; f++) for (int v = 0; v <= GRID; v++) for (int u = 0; u <= GRID; u++)
            mesh[f][v][u] = direction(f, (double) u / GRID * body.faceSize(), (double) v / GRID * body.faceSize());
    }
    PlanetMapApi.Body body() { return body; }
    Vector3d direction(int face, double u, double v) {
        var d = PlanetMapApi.direction(body.index(), face, u, v);
        return new Vector3d(d.x(), d.y(), d.z());
    }
    PlanetMapApi.Location pick(MapCamera camera, double x, double y) {
        var p = camera.ray(x, y).sphere(ORIGIN, 1);
        return p == null ? null : PlanetMapApi.locate(body.index(), new PlanetMapApi.Direction(p.x, p.y, p.z));
    }
    private MapCamera.ScreenPoint visible(MapCamera camera, Vector3d p) {
        // Perspective horizon: using only the camera's forward direction leaks pins over the limb.
        if (p.dot(new Vector3d(camera.eye()).sub(p)) <= 0) return null;
        var point = camera.project(p);
        return point.depth() > 0 ? point : null;
    }
    private void raster() {
        if (!Atlas.surveys.available() || System.nanoTime() - lastRaster < 100_000_000) return;
        int step = 4;
        while (step < 64 && body.faceSize() / step > 256) step *= 2;
        var surface = PlanetMapApi.surface(body.index(), step);
        var coarse = PlanetMapApi.surface(body.index(), 64);
        if (surface == null || coarse == null) return;
        var fog = Atlas.surveys.record(body);
        double ready = surface.progress(), coarseReady = coarse.progress();
        if (revision == fog.revision() && progress == ready && coarseProgress == coarseReady) return;
        revision = fog.revision(); progress = ready; coarseProgress = coarseReady; lastRaster = System.nanoTime();
        int n = Math.max(128, surface.width());
        var manager = Minecraft.getInstance().getTextureManager();
        for (int f = 0; f < 6; f++) {
            if (textures[f] == null) {
                textures[f] = new DynamicTexture(n, n, false);
                ids[f] = manager.register("starfold_atlas/globe_" + f, textures[f]);
            }
            var image = textures[f].getPixels();
            for (int v = 0; v < n; v++) for (int u = 0; u < n; u++) {
                int color = GlobeRaster.color(fog, surface, coarse, f, u * surface.width() / n, v * surface.width() / n);
                image.setPixelRGBA(u, v, color & 0xff00ff00 | (color >> 16 & 255) | (color & 255) << 16);
            }
            textures[f].upload();
        }
    }
    /** Caller clips to the viewport. Projection restoration bypasses re-applying Starfold's depth conversion. */
    void render(GuiGraphics g, MapCamera camera, int x, int y, int width, int height) {
        raster();
        MapRender.draw(g, camera, x, y, width, height, .01, 100, () -> {
            var sun = PlanetMapApi.sun(body.index());
            var light = sun == null ? new Vector3d(0, 1, 0) : new Vector3d(sun.x(), sun.y(), sun.z());
            var view = camera.view();
            for (int face = 0; face < 6; face++) {
                boolean textured = ids[face] != null;
                if (textured) {
                    RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
                    RenderSystem.setShaderTexture(0, ids[face]);
                } else RenderSystem.setShader(GameRenderer::getPositionColorShader);
                var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                        textured ? DefaultVertexFormat.POSITION_TEX_COLOR : DefaultVertexFormat.POSITION_COLOR);
                for (int v = 0; v < GRID; v++) for (int u = 0; u < GRID; u++) {
                    terrainVertex(buffer, view, mesh[face][v][u], u, v, light, textured);
                    terrainVertex(buffer, view, mesh[face][v + 1][u], u, v + 1, light, textured);
                    terrainVertex(buffer, view, mesh[face][v + 1][u + 1], u + 1, v + 1, light, textured);
                    terrainVertex(buffer, view, mesh[face][v][u + 1], u + 1, v, light, textured);
                }
                BufferUploader.drawWithShader(buffer.buildOrThrow());
            }
            var air = PlanetMapApi.atmosphere(body.index());
            if (air != null) {
                RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.depthMask(false);
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                var eye = camera.eye();
                for (int f = 0; f < 6; f++) for (int v = 0; v < GRID; v++) for (int u = 0; u < GRID; u++) {
                    rimVertex(buffer, view, mesh[f][v][u], eye, light, air);
                    rimVertex(buffer, view, mesh[f][v + 1][u], eye, light, air);
                    rimVertex(buffer, view, mesh[f][v + 1][u + 1], eye, light, air);
                    rimVertex(buffer, view, mesh[f][v][u + 1], eye, light, air);
                }
                BufferUploader.drawWithShader(buffer.buildOrThrow());
            }
        });
    }
    private static void terrainVertex(BufferBuilder b, Matrix4f view, Vector3d p, int u, int v, Vector3d sun, boolean textured) {
        float shade = (float) (.18 + .82 * Math.max(0, p.dot(sun)));
        var vertex = b.addVertex(view, (float) p.x, (float) p.y, (float) p.z);
        if (textured) vertex.setUv((float) u / GRID, (float) v / GRID).setColor(shade, shade, shade, 1);
        else vertex.setColor(GlobeRaster.dim(GlobeRaster.UNKNOWN, shade));
    }
    private static void rimVertex(BufferBuilder b, Matrix4f view, Vector3d p, Vector3d eye, Vector3d light, PlanetMapApi.Atmosphere air) {
        double facing = Math.abs(p.dot(new Vector3d(eye).sub(p).normalize()));
        float alpha = (float) (Math.pow(1 - facing, 4) * Math.min(.55, air.density() * .35)
                * (.3 + .7 * Math.max(0, p.dot(light))));
        int c = air.argb();
        b.addVertex(view, (float) (p.x * 1.025), (float) (p.y * 1.025), (float) (p.z * 1.025))
                .setColor((c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, alpha);
    }
    void overlays(GuiGraphics g, Font font, MapCamera camera, int width, int top, int bottom) {
        var result = new ArrayList<Pin>();
        var o = PlanetMapApi.observer();
        var labels = new MapLabels();
        if (o != null && o.body() == body.index()) {
            var p = visible(camera, direction(o.face(), o.u(), o.v()));
            if (p != null) {
                double angle = Math.toRadians(o.heading());
                var next = camera.project(direction(o.face(), o.u() - Math.sin(angle), o.v() + Math.cos(angle)));
                double dx = next.x() - p.x(), dy = next.y() - p.y(), length = Math.hypot(dx, dy);
                dx /= length; dy /= length;
                for (int i = 0; i < 9; i++) g.fill((int) (p.x() + dx * i) - 1, (int) (p.y() + dy * i) - 1,
                        (int) (p.x() + dx * i) + 2, (int) (p.y() + dy * i) + 2, 0xff71ffff);
                for (int s : new int[]{-1, 1}) for (int i = 0; i < 4; i++) {
                    int x = (int) (p.x() + dx * (8 - i) + s * dy * i), y = (int) (p.y() + dy * (8 - i) - s * dx * i);
                    g.fill(x, y, x + 2, y + 2, 0xff71ffff);
                }
                result.add(new Pin(p, AtlasMapScreen.text("position"), 0xff71ffff, null, null));
            }
        }
        for (var marker : PlanetMapApi.markers()) if (marker.body() == body.index()) {
            var p = visible(camera, direction(marker.face(), marker.u(), marker.v()));
            if (p != null) result.add(new Pin(p, marker.launch() ? AtlasMapScreen.text("launch") : Component.literal(marker.name()), 0xffffca70, marker, null));
        }
        for (var ship : SystemMapApi.ships()) if (ship.body() == body.index() && ship.face() >= 0) {
            var p = visible(camera, direction(ship.face(), ship.u(), ship.v()));
            if (p != null) result.add(new Pin(p, Component.literal(ship.name()), 0xff91ffad, null, ship));
        }
        pins = List.copyOf(result);
        for (var pin : pins) {
            int x = (int) pin.point().x(), y = (int) pin.point().y();
            if (pin.ship() != null) g.renderOutline(x - 3, y - 3, 7, 7, pin.color());
            else g.fill(x - 2, y - 2, x + 3, y + 3, pin.color());
            int labelWidth = font.width(pin.label());
            // Try both sides and above/below before dropping a crowded label.
            for (int side : new int[]{1, -1}) {
                boolean placed = false;
                for (int offset : new int[]{-4, 8, -16}) {
                    int lx = side > 0 ? x + 6 : x - labelWidth - 6, ly = y + offset;
                    if (lx < 2 || lx + labelWidth >= width - 2 || ly < top || ly + font.lineHeight >= bottom) continue;
                    if (labels.place(new MapLabels.Rect(lx - 1, ly - 1, labelWidth + 2, font.lineHeight + 2))) {
                        g.drawString(font, pin.label(), lx, ly, pin.color(), true); placed = true; break;
                    }
                }
                if (placed) break;
            }
        }
    }
    Pin hovered(double x, double y) {
        return pins.stream().filter(p -> p.marker() != null || p.ship() != null)
                .filter(p -> Math.hypot(p.point().x() - x, p.point().y() - y) <= 8)
                .min(java.util.Comparator.comparingDouble(p -> Math.hypot(p.point().x() - x, p.point().y() - y))).orElse(null);
    }
    int progress() { return Math.max(0, (int) (progress * 100)); }
    @Override public void close() {
        var manager = Minecraft.getInstance().getTextureManager();
        for (int f = 0; f < 6; f++) if (ids[f] != null) { manager.release(ids[f]); ids[f] = null; textures[f] = null; }
    }
}
