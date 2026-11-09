package dev.starfold.atlas;

import java.util.ArrayList;
import java.util.List;

/** Call in priority order; rejected names remain available in the hover/selection readout. */
final class MapLabels {
    record Rect(int x, int y, int width, int height) {
        boolean overlaps(Rect b) {
            return x < b.x+b.width && x+width > b.x && y < b.y+b.height && y+height > b.y;
        }
    }
    private final List<Rect> placed = new ArrayList<>();
    boolean place(Rect rect) {
        for (var other : placed) if (rect.overlaps(other)) return false;
        placed.add(rect);
        return true;
    }
}
