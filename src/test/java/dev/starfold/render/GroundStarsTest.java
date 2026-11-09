package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

class GroundStarsTest {
    @Test
    void actualDirectionsUseObserverPositionAndAreTranslationInvariant() {
        var star = new Point(10, 0, 0);
        assertEquals(new Point(1, 0, 0), GroundStars.direction(star, new Point(0, 0, 0)));
        var moved = GroundStars.direction(star, new Point(0, 10, 0));
        assertEquals(1, moved.length(), 1e-12);
        assertEquals(1 / Math.sqrt(2), moved.x(), 1e-12);
        assertEquals(-1 / Math.sqrt(2), moved.y(), 1e-12);
        var offset = new Point(4_000_000, -20, 8_000_000);
        assertEquals(moved, GroundStars.direction(star.add(offset), new Point(0, 10, 0).add(offset)));
        assertEquals(new Point(-1, 0, 0), GroundStars.direction(star, new Point(20, 0, 0)));
    }

    @Test
    void distanceDimmingKeepsDestinationsBrighterThanGalacticDecoration() {
        assertTrue(GroundStars.brightness(1_000_000) > GroundStars.brightness(5_200_000));
        assertTrue(GroundStars.brightness(5_200_000) > GroundStars.brightness(10_000_000));
        assertTrue(GroundStars.brightness(100_000_000) * 255 > 67);
        assertEquals(1, GroundStars.brightness(0));
    }
}
