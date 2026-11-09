package dev.starfold.atlas;

import dev.starfold.api.SystemMapApi.Position;
import java.util.List;

/** One uniform scale preserves actual interstellar bearings and relative separations. */
final class GalaxyProjection {
    private final Position origin;
    private final double unit;
    GalaxyProjection(List<Position> stars, Position origin) {
        this.origin = origin;
        unit = stars.stream().mapToDouble(origin::distance).max().orElse(1);
    }
    Position position(Position p) {
        double scale = unit == 0 ? 1 : unit;
        return new Position((p.x() - origin.x()) / scale, (p.y() - origin.y()) / scale, (p.z() - origin.z()) / scale);
    }
}
