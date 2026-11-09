package dev.starfold.atlas;

import dev.starfold.api.PlanetFaces;
import dev.starfold.api.PlanetMapApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import java.util.LinkedHashMap;

/** Chunk-sized pixel tiles, built only on demand; live columns are polled with a bounded budget. */
final class GroundRaster implements AutoCloseable {
    static final int SIDE = 16, LIMIT = 2048;
    record Key(int face, int x, int y, int step, int lightTurns) {}
    private record Sample(int argb, double height, int depth, boolean explored) {}
    private record Tile(DynamicTexture texture, ResourceLocation id, int revision, double progress, long tick) {}
    private final PlanetMapApi.Body body;
    private final LinkedHashMap<Key, Tile> tiles = new LinkedHashMap<>(64, .75f, true);
    private SurveyRecord fog;
    private PlanetMapApi.Surface fine, coarse;
    private double progress;
    private long tick;
    private int builds, refreshes, cursor, visited;
    GroundRaster(PlanetMapApi.Body body) { this.body = body; }
    void begin(SurveyRecord fog, PlanetMapApi.Surface fine, PlanetMapApi.Surface coarse, long tick) {
        this.fog = fog; this.fine = fine; this.coarse = coarse; this.tick = tick;
        progress = fine == null ? -1 : fine.progress();
        builds = 8; refreshes = 2; visited = 0;
    }
    void end() { if (cursor >= visited) cursor = 0; }
    boolean present(Key key) { return tiles.containsKey(key); }
    ResourceLocation tile(Key key) {
        int index = visited++;
        var tile = tiles.get(key);
        if (tile == null) {
            if (builds-- <= 0) return null;
            var texture = new DynamicTexture(SIDE, SIDE, false);
            var id = Minecraft.getInstance().getTextureManager().register("starfold_atlas/ground", texture);
            tile = paint(key, texture, id);
            tiles.put(key, tile);
            while (tiles.size() > LIMIT) release(tiles.pollFirstEntry().getValue());
        } else if ((tile.revision() != fog.revision() || tile.progress() != progress || tick - tile.tick() >= 20)
                && refreshes > 0 && index >= cursor) {
            refreshes--; cursor = index + 1;
            tile = paint(key, tile.texture(), tile.id());
            tiles.put(key, tile);
        }
        return tile.id();
    }
    private Tile paint(Key key, DynamicTexture texture, ResourceLocation id) {
        var samples = new Sample[SIDE + 2][SIDE + 2];
        for (int y = -1; y <= SIDE; y++) for (int x = -1; x <= SIDE; x++)
            samples[y + 1][x + 1] = sample(key.face(), (key.x() * SIDE + x) * key.step(),
                    (key.y() * SIDE + y) * key.step());
        var image = texture.getPixels();
        var light = GroundChart.rotate(-1, -1, -key.lightTurns());
        for (int y = 0; y < SIDE; y++) for (int x = 0; x < SIDE; x++) {
            var cell = samples[y + 1][x + 1];
            var slope = samples[y + 1 + (int) light.y()][x + 1 + (int) light.x()];
            int color = 0;
            if (cell != null && (key.x() * SIDE + x) * key.step() < body.faceSize()
                    && (key.y() * SIDE + y) * key.step() < body.faceSize()) {
                double shade = cell.depth() >= 0 ? Math.clamp(1 - cell.depth() * .018, .42, 1)
                        : Math.clamp(.86 + (cell.height() - (slope == null ? cell.height() : slope.height()))
                                / key.step() * .22, .5, 1.18);
                color = shade(cell.argb(), shade * (cell.explored() ? 1 : .42));
            }
            image.setPixelRGBA(x, y, color & 0xff00ff00 | (color >> 16 & 255) | (color & 255) << 16);
        }
        texture.upload();
        return new Tile(texture, id, fog.revision(), progress, tick);
    }
    private Sample sample(int face, int u, int v) {
        // There is no single flat diagonal continuation through a three-face vertex.
        boolean outsideU = u < 0 || u >= body.faceSize(), outsideV = v < 0 || v >= body.faceSize();
        if (outsideU && outsideV) return null;
        if (outsideU || outsideV) {
            var p = PlanetFaces.adjacent(body.faceSize(), face, u, v);
            face = p.face(); u = p.u(); v = p.v();
        }
        boolean explored = fog.explored(face, u / SurveyRecord.STEP, v / SurveyRecord.STEP);
        if (!explored && !fog.revealed()) return null;
        if (explored) {
            var live = PlanetMapApi.loadedSurface(body.index(), face, u, v);
            if (live != null) return new Sample(live.argb(), live.height(), live.waterDepth(), true);
        }
        var source = explored ? fine : coarse;
        if (source == null) return null;
        var cell = source.cell(face, u / source.step(), v / source.step());
        return cell == null ? null : new Sample(cell.argb(), cell.height(),
                source.waterDepth(face, u / source.step(), v / source.step()), explored);
    }
    static int shade(int color, double shade) {
        return 0xff000000 | (int) Math.min(255, (color >> 16 & 255) * shade) << 16
                | (int) Math.min(255, (color >> 8 & 255) * shade) << 8 | (int) Math.min(255, (color & 255) * shade);
    }
    private static void release(Tile tile) { Minecraft.getInstance().getTextureManager().release(tile.id()); }
    @Override public void close() { tiles.values().forEach(GroundRaster::release); tiles.clear(); }
}
