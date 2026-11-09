package dev.starfold.atlas;

import dev.starfold.api.SystemMapApi.Position;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GalaxyProjectionTest {
    @Test void oneUniformScaleRetainsTrueRelativePositionsIncludingInclinationAndLargeOrigins() {
        var home = new Position(9e12, -4e12, 7e12);
        var inner = new Position(home.x() + 30000, home.y() + 40000, home.z());
        var outer = new Position(home.x() - 60000, home.y(), home.z() + 80000);
        var layout = new GalaxyProjection(List.of(home, inner, outer), home);
        assertEquals(new Position(0, 0, 0), layout.position(home));
        assertEquals(new Position(.3, .4, 0), layout.position(inner));
        assertEquals(new Position(-.6, 0, .8), layout.position(outer));
        assertEquals(inner.distance(outer) / 100000, layout.position(inner).distance(layout.position(outer)), 1e-12);
        assertEquals(new Position(.15, .2, 0), layout.position(new Position(home.x() + 15000, home.y() + 20000, home.z())));
    }
    @Test void singleHomeSystemHasFiniteCoordinatesForBothStarAndObserver() {
        var home = new Position(1e10, 2e10, -1e10);
        var layout = new GalaxyProjection(List.of(home), home);
        assertEquals(new Position(0, 0, 0), layout.position(home));
        assertEquals(new Position(3, -4, 5), layout.position(new Position(home.x() + 3, home.y() - 4, home.z() + 5)));
    }
}
