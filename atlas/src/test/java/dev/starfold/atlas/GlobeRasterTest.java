package dev.starfold.atlas;

import dev.starfold.api.PlanetMapApi;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlobeRasterTest {
    private static PlanetMapApi.Surface surface(int step, Integer color) {
        return new PlanetMapApi.Surface() {
            public int width() { return 256 / step; }
            public int step() { return step; }
            public double progress() { return color == null ? 0 : 1; }
            public PlanetMapApi.Cell cell(int f, int u, int v) { return color == null ? null : new PlanetMapApi.Cell(color, 0); }
        };
    }
    @Test void unexploredOrbitalAndExploredColorsStayDistinctAndPendingCellsRemainDark() {
        var fog = new SurveyRecord(256);
        var detail = surface(16, 0xffa0c0e0); var coarse = surface(64, 0xff6080a0);
        assertEquals(GlobeRaster.UNKNOWN, GlobeRaster.color(fog, detail, coarse, 2, 3, 4));
        // The last 4-block subcell contributes only its own 1/16 coverage, not the whole texel.
        fog.explore(2, 15, 19);
        assertEquals(0xff19222c, GlobeRaster.color(fog, detail, coarse, 2, 3, 4));
        assertEquals(GlobeRaster.UNKNOWN, GlobeRaster.color(fog, detail, coarse, 2, 4, 4));
        fog.reveal();
        assertEquals(0xff44596f, GlobeRaster.color(fog, detail, coarse, 2, 3, 4));
        for (int v = 16; v < 20; v++) for (int u = 12; u < 16; u++) fog.explore(2, u, v);
        assertEquals(0xffa0c0e0, GlobeRaster.color(fog, detail, coarse, 2, 3, 4));
        assertEquals(GlobeRaster.dim(0xff6080a0, .65), GlobeRaster.color(fog, detail, coarse, 3, 3, 4));
        assertEquals(GlobeRaster.UNKNOWN, GlobeRaster.color(fog, surface(16, null), coarse, 2, 3, 4));
        assertEquals(GlobeRaster.UNKNOWN, GlobeRaster.color(fog, detail, surface(64, null), 3, 3, 4));
    }
}
