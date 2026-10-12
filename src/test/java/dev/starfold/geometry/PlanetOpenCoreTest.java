package dev.starfold.geometry;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.geometry.PlanetSpace.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** True cores are absent, not transparent solids. Tests use the actual shared mesh/CCD. */
class PlanetOpenCoreTest {
    private final PlanetSpace s = PlanetSpace.STANDARD;

    private List<Cell> cores(int h) {
        var cells = new ArrayList<Cell>();
        for (var f : Face.values())
            for (int u : new int[] {0, s.size - 1})
                for (int v : new int[] {0, s.size - 1}) cells.add(new Cell(f, u, h, v));
        return cells;
    }

    @Test
    void onlyTwentyFourColumnsAreAbsentWhileEverySavedKeyStillExists() {
        int absent = 0;
        var old = PlanetSpace.equiangular(64, -4, 4);
        for (int i = 0; i < s.volume(); i++) {
            var c = s.cell(i);
            assertTrue(s.contains(c));
            assertEquals(i, s.index(c));
            assertEquals(i, old.index(c));
            if (!s.hasVolume(c)) {
                absent++;
                assertTrue((c.u() == 0 || c.u() == 63) && (c.v() == 0 || c.v() == 63));
            }
            assertTrue(old.hasVolume(c));
        }
        assertEquals(24 * 8, absent);
        for (var c : cores(0))
            for (int h : new int[] {-100, 100})
                assertTrue(s.voidColumn(new Cell(c.face(), c.u(), h, c.v())));
    }

    @Test
    void omittedCellsNeverEvaluateEvenAnInvalidOrFoldedMapping() {
        for (int h = s.minHeight; h < s.maxHeight; h++)
            for (var c : cores(h))
                for (var side : Side.values()) {
                    assertTrue(s.face(c, side).isEmpty());
                    assertFalse(
                            PlanetMotion.intersects(
                                    s.project(c.face(), c.u() + .5, c.h() + .5, c.v() + .5),
                                    s.face(c, side)));
                    assertTrue(
                            s.face(
                                            c,
                                            side,
                                            key -> {
                                                throw new AssertionError(
                                                        "Absent geometry evaluated");
                                            })
                                    .isEmpty());
                }
        assertThrows(
                AssertionError.class,
                () ->
                        s.face(
                                new Cell(Face.TOP, 63, 0, 62),
                                Side.UP,
                                key -> {
                                    throw new AssertionError();
                                }));
    }
}
