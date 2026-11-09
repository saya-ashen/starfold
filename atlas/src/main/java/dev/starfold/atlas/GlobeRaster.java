package dev.starfold.atlas;

import dev.starfold.api.PlanetMapApi;

/** Coarse globe texels aggregate explored survey cells without changing the saved survey grid. */
final class GlobeRaster {
    static final int UNKNOWN = 0xff101820;
    static int color(SurveyRecord fog, PlanetMapApi.Surface surface, PlanetMapApi.Surface coarse, int face, int u, int v) {
        int stride = surface.step() / SurveyRecord.STEP;
        int explored = 0, total = stride * stride;
        for (int z = v * stride; z < (v + 1) * stride; z++)
            for (int x = u * stride; x < (u + 1) * stride; x++)
                if (fog.explored(face, x, z)) explored++;
        var detail = explored == 0 ? null : surface.cell(face, u, v);
        int foreground = detail == null ? UNKNOWN : detail.argb();
        if (explored == total) return foreground;
        var cell = fog.revealed() ? coarse.cell(face, u * surface.step() / coarse.step(), v * surface.step() / coarse.step()) : null;
        int background = cell == null ? UNKNOWN : dim(cell.argb(), .65);
        // Downsampling must not unlock unvisited subcells merely because one neighbour was explored.
        int color = 0xff000000;
        for (int shift = 0; shift <= 16; shift += 8)
            color |= (((foreground >> shift & 255) * explored + (background >> shift & 255) * (total - explored)) / total) << shift;
        return color;
    }
    static int dim(int color, double scale) {
        return 0xff000000 | (int) ((color >> 16 & 255) * scale) << 16
                | (int) ((color >> 8 & 255) * scale) << 8 | (int) ((color & 255) * scale);
    }
    private GlobeRaster() {}
}
