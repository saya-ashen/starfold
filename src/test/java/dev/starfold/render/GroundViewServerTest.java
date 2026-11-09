package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GasGiant;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

class GroundViewServerTest {
    private static GroundEphemeris.Model singleSolidWithGas() {
        var bodies = new ArrayList<>(PlanetSystem.model(1).bodies());
        bodies.add(
                GroundEphemeris.Body.gas(
                        new GroundEphemeris.Fixed(new Point(45000, 0, 0)),
                        4096,
                        new GasGiant(1, 31, "Amber", 0x98653d, 0xead1a2, 256, 512)));
        return new GroundEphemeris.Model(bodies);
    }

    @Test
    void deltaPublicationDoesNotLoadChunksOrKeepAnIteratorAcrossCallbacks() throws Exception {
        var source=dev.starfold.Sources.text("GroundViewServer");
        var publish=source.substring(source.indexOf("private static void publishChanges"),
                source.indexOf("private static void refreshIndices"));
        assertTrue(publish.contains("getChunkNow(pos.getX()>>4,pos.getZ()>>4)"));
        assertTrue(publish.contains("chunk.getBlockState(pos)"));
        assertFalse(publish.contains("level.getBlockState(pos)"));
        assertTrue(publish.indexOf("pending.iterator()")>publish.indexOf("while (!pending.isEmpty()"));
    }

    @Test
    void singleSolidWithGasNeverRefreshesAMissingSecondSurface() {
        var model = singleSolidWithGas();
        assertEquals(1, model.count());
        assertEquals(3, model.bodies().size());
        assertEquals(-1, model.catalogBody(2).slot());
        PlanetShape.system(new int[] {2048}, new int[] {191});
        try {
            var slots =
                    GroundViewServer.refreshSlots(
                            model.count(), IntStream.of(0), IntStream.empty());
            // Use the same slot validation as get(level, id), without a world or chunk loads.
            assertDoesNotThrow(() -> slots.forEach(id -> new GroundSurfaceIndex(id)));
            assertEquals(List.of(0), List.copyOf(slots));
        } finally {
            PlanetSystem.reset();
        }
    }

    @Test
    void twoSolidBaselineRefreshesBothEvenWithoutObservers() {
        assertEquals(
                List.of(0, 1),
                List.copyOf(
                        GroundViewServer.refreshSlots(
                                PlanetSystem.model(2).count(),
                                IntStream.empty(),
                                IntStream.empty())));
    }

    @Test
    void higherPlayerAndLeadDestinationSlotsAreSortedAndRefreshedOnce() {
        assertEquals(
                List.of(0, 1, 3, 4, 5),
                List.copyOf(
                        GroundViewServer.refreshSlots(
                                PlanetSystem.model(6).count(),
                                IntStream.of(5, 3, 3, 0),
                                IntStream.of(4, 3, 4, 1))));
    }

    @Test
    void invalidOccupiedAndDestinationSlotsAreSkippedNotClampedToAnotherSurface() {
        assertEquals(
                List.of(0, 1),
                List.copyOf(
                        GroundViewServer.refreshSlots(
                                PlanetSystem.model(5).count(),
                                IntStream.of(-2, -1, 5, Integer.MAX_VALUE),
                                IntStream.of(-2, -1, 5, Integer.MAX_VALUE))));
    }

    @Test
    void sunAndGasHaveNoDestinationSurfaceAndCatalogIndicesAreNotSurfaceCount() {
        var model = singleSolidWithGas();
        assertEquals(-1, model.catalogBody(0).slot());
        assertEquals(
                List.of(0),
                List.copyOf(
                        GroundViewServer.refreshSlots(
                                model.count(),
                                IntStream.empty(),
                                IntStream.of(
                                        model.catalogBody(0).slot(),
                                        model.catalogBody(2).slot(),
                                        2))));
    }
}
