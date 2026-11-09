package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import org.junit.jupiter.api.Test;

class GroundCoordinatesTest {
    @Test
    void originsAreSharedImmutableChartConstants() {
        for (int face = 0; face < 6; face++) {
            assertEquals(new Point(face * 4096, 128, 0), GroundCoordinates.origin(face));
            assertSame(GroundCoordinates.origin(face), GroundCoordinates.origin(face));
        }
        assertThrows(IllegalArgumentException.class, () -> GroundCoordinates.origin(-1));
        assertThrows(IllegalArgumentException.class, () -> GroundCoordinates.origin(6));
    }
}
