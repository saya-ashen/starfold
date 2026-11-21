package dev.starfold.devtools;

import static dev.starfold.devtools.FarRawCapture.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class FarRawCaptureTest {
    static final Fluid WATER =
            new Fluid("minecraft:water", "minecraft:water", true, false, 8, false, 8f / 9);
    static final Fluid FLOWING =
            new Fluid("minecraft:flowing_water", "minecraft:water", false, true, 3, true, 3f / 9);
    static final Block AIR = new Block(Status.LOADED, "minecraft:air", true, null, "fixture");
    static final Block STONE = new Block(Status.LOADED, "minecraft:stone", false, null, "fixture");

    static class Fake implements Source {
        int min = -64, max = 320, top = 70, reads, lightReads, maps;
        boolean onlyFirst, unloaded, allFluid;
        final Map<Position, Block> states = new HashMap<>();
        final Map<MappedColumn, Status> availability = new HashMap<>();
        final List<Position> actualReads = new ArrayList<>();

        public int minY() {
            return min;
        }

        public int maxYExclusive() {
            return max;
        }

        public MappedColumn map(int face, int u, int v) {
            maps++;
            return onlyFirst && (u != 0 || v != 0) ? null : new MappedColumn(u - 16, v - 32);
        }

        public Surface surface(int x, int z) {
            Status s =
                    availability.getOrDefault(
                            new MappedColumn(x, z), unloaded ? Status.UNLOADED : Status.LOADED);
            return new Surface(s, s == Status.LOADED ? top : null, "fixture " + s);
        }

        public Block block(Position p) {
            assertEquals(Status.LOADED, surface(p.x(), p.z()).status(), "read unloaded column");
            assertTrue(p.y() >= min && p.y() < max, "read outside world");
            reads++;
            actualReads.add(p);
            return states.getOrDefault(p, allFluid ? water(WATER) : (p.y() <= top ? STONE : AIR));
        }

        public Light light(Position p) {
            lightReads++;
            return new Light(Status.LOADED, 12, 3, "fixture client layer values");
        }
    }

    static Block water(Fluid fluid) {
        return new Block(Status.LOADED, "fixture fluid", false, fluid, "fixture");
    }

    static Sample at(Report r, int y) {
        return r.columns().getFirst().samples().stream()
                .filter(s -> s.position().y() == y)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void normalizesTileAndSamplesActualSurfaceCoordinatesIncludingAirAndLight() {
        Fake f = new Fake();
        Report r = capture(3, 31, 47, f);
        assertEquals(16, r.bounds().x());
        assertEquals(32, r.bounds().z());
        assertEquals(256, f.maps);
        assertEquals(256, r.counts().columns());
        assertEquals(2816, r.counts().primarySamples());
        assertEquals(2816, f.reads);
        assertEquals(2816, f.lightReads);
        Column c = r.columns().getFirst();
        assertEquals(70, c.surface().top());
        assertEquals(62, c.fromY());
        assertEquals(72, c.toY());
        assertEquals(new Position(0, 62, 0), c.samples().getFirst().position());
        assertEquals(false, at(r, 70).block().air());
        assertEquals(true, at(r, 71).block().air());
        assertEquals(12, at(r, 71).light().sky());
        assertEquals(3, at(r, 71).light().block());
        assertTrue(r.lightMeaning().contains("not sky visibility"));
        assertEquals(0, r.counts().clippedSamples());
        assertThrows(UnsupportedOperationException.class, () -> r.columns().clear());
        assertThrows(UnsupportedOperationException.class, () -> c.samples().clear());
    }

    @Test
    void rejectsInvalidInputsBeforeMapping() {
        Fake f = new Fake();
        assertThrows(IllegalArgumentException.class, () -> capture(-1, 0, 0, f));
        assertThrows(IllegalArgumentException.class, () -> capture(6, 0, 0, f));
        assertThrows(IllegalArgumentException.class, () -> capture(0, -1, 0, f));
        assertThrows(IllegalArgumentException.class, () -> capture(0, 0, 2048, f));
        assertEquals(0, f.maps);
        Report edge = capture(5, 2047, 2047, f);
        assertEquals(2032, edge.bounds().x());
        assertEquals(2047, edge.columns().getLast().u());
    }

    @Test
    void clipsAtBothWorldBoundsAndPreservesEmptyColumnHeight() {
        Fake f = new Fake();
        f.onlyFirst = true;
        f.min = 0;
        f.max = 8;
        f.top = -1;
        Report bottom = capture(0, 0, 0, f);
        assertEquals(-1, bottom.columns().getFirst().surface().top());
        assertEquals(0, bottom.columns().getFirst().fromY());
        assertEquals(1, bottom.columns().getFirst().toY());
        assertEquals(9, bottom.counts().clippedSamples());
        assertEquals(2, f.reads);
        f.top = 7;
        f.reads = 0;
        Report ceiling = capture(0, 0, 0, f);
        assertEquals(0, ceiling.columns().getFirst().fromY());
        assertEquals(7, ceiling.columns().getFirst().toY());
        assertEquals(3, ceiling.counts().clippedSamples());
        assertEquals(8, f.reads);
    }

    @Test
    void distinguishesUnmappedUnloadedUnknownAndNeverReadsThem() {
        Fake f = new Fake();
        f.onlyFirst = true;
        f.unloaded = true;
        Report r = capture(0, 0, 0, f);
        assertEquals(255, r.counts().unmappedColumns());
        assertEquals(1, r.counts().unloadedColumns());
        assertEquals(0, r.counts().primarySamples());
        assertEquals(0, f.reads);
        assertEquals(Status.UNLOADED, r.columns().getFirst().surface().status());
        assertNull(r.columns().getFirst().fromY());
        f.unloaded = false;
        f.availability.put(new MappedColumn(-16, -32), Status.UNKNOWN);
        r = capture(0, 0, 0, f);
        assertEquals(1, r.counts().unknownColumns());
        assertEquals(0, f.reads);
    }

    @Test
    void fluidsUseLoadedAboveAndRetainSixActualWorldNeighbors() {
        Fake f = new Fake();
        f.onlyFirst = true;
        Position p = new Position(-16, 70, -32);
        f.states.put(p, water(WATER));
        f.states.put(new Position(-16, 71, -32), water(FLOWING));
        f.availability.put(new MappedColumn(-17, -32), Status.UNLOADED);
        f.availability.put(new MappedColumn(-16, -33), Status.UNKNOWN);
        Report r = capture(0, 0, 0, f);
        Sample s = at(r, 70);
        assertEquals(1f, s.effectiveHeight().value());
        assertEquals(6, s.neighbors().size());
        Neighbor west =
                s.neighbors().stream()
                        .filter(n -> n.direction() == Direction.WEST)
                        .findFirst()
                        .orElseThrow();
        assertEquals(new Position(-17, 70, -32), west.position());
        assertEquals(Status.UNLOADED, west.block().status());
        assertNull(west.block().air());
        Neighbor north =
                s.neighbors().stream()
                        .filter(n -> n.direction() == Direction.NORTH)
                        .findFirst()
                        .orElseThrow();
        assertEquals(Status.UNKNOWN, north.block().status());
        assertNull(north.block().air());
        Sample flowing = at(r, 71);
        assertEquals(3, flowing.block().fluid().amount());
        assertTrue(flowing.block().fluid().flowing());
        assertTrue(flowing.block().fluid().falling());
        assertEquals(3f / 9, flowing.effectiveHeight().value());
        assertTrue(
                flowing.neighbors().stream().anyMatch(n -> Boolean.TRUE.equals(n.block().air())));
        assertEquals(2, r.counts().unloadedNeighbors());
        assertEquals(2, r.counts().unknownNeighbors());
        assertThrows(UnsupportedOperationException.class, () -> s.neighbors().clear());
    }

    @Test
    void effectiveHeightUsesSourceFluidEquivalenceRule() {
        Fake f =
                new Fake() {
                    public boolean sameFluid(Fluid fluid, Fluid above) {
                        return false;
                    }
                };
        f.onlyFirst = true;
        f.states.put(new Position(-16, 70, -32), water(WATER));
        f.states.put(new Position(-16, 71, -32), water(FLOWING));
        Sample s = at(capture(0, 0, 0, f), 70);
        assertEquals(8f / 9, s.effectiveHeight().value());
    }

    @Test
    void unavailableAboveNeverProducesEffectiveHeightOrPretendsToBeEmpty() {
        Fake f = new Fake();
        f.onlyFirst = true;
        f.states.put(new Position(-16, 70, -32), water(WATER));
        f.states.put(
                new Position(-16, 71, -32),
                Block.unavailable(Status.UNLOADED, "gone at block read"));
        Report r = capture(0, 0, 0, f);
        Sample s = at(r, 70);
        assertEquals(Status.UNLOADED, s.effectiveHeight().status());
        assertNull(s.effectiveHeight().value());
        assertEquals(1, r.counts().effectiveHeightUnknown());
        Neighbor above =
                s.neighbors().stream()
                        .filter(n -> n.direction() == Direction.UP)
                        .findFirst()
                        .orElseThrow();
        assertEquals(new Position(-16, 71, -32), above.position());
        assertEquals(Status.UNLOADED, above.block().status());
        assertNull(above.block().air());
        assertEquals(8f / 9, s.block().fluid().ownHeight());
        assertTrue(s.block().fluid().source());
        assertFalse(s.block().fluid().flowing());
    }

    @Test
    void sharesHardReadBudgetAndCapsNeighborSetsWithoutDroppingRawFluids() {
        Fake f = new Fake();
        f.allFluid = true;
        Report r = capture(0, 0, 0, f);
        assertEquals(2816, f.reads);
        assertEquals(2816, r.counts().blockReads());
        assertEquals(0, r.counts().supplementalReads());
        assertEquals(2816, r.counts().fluidSamples());
        assertEquals(128, r.counts().neighborSets());
        assertEquals(2816 - 128, r.counts().neighborSetsTruncated());
        assertTrue(r.counts().budgetOmissions() > 0);
        assertTrue(r.counts().unknownNeighbors() > 0);
        assertEquals(256, r.counts().effectiveHeightUnknown());
        assertEquals(Status.UNKNOWN, at(r, 72).effectiveHeight().status());
        assertNull(at(r, 72).effectiveHeight().value());
        Sample last = r.columns().getLast().samples().getLast();
        assertNotNull(last.block().fluid());
        assertTrue(last.neighborsTruncated());
        assertTrue(last.neighbors().isEmpty());
        assertEquals(f.reads, f.actualReads.stream().distinct().count());
    }

    @Test
    void supplementalReadsStayWithinGlobalBudgetAndOutOfWorldIsUnknownHeight() {
        Fake f = new Fake();
        f.onlyFirst = true;
        f.min = 0;
        f.max = 2;
        f.top = 1;
        f.allFluid = true;
        Report r = capture(0, 0, 0, f);
        assertEquals(2, r.counts().primarySamples());
        assertEquals(8, r.counts().supplementalReads());
        assertEquals(10, f.reads);
        assertEquals(2, r.counts().outOfWorldNeighbors());
        assertEquals(Status.OUT_OF_WORLD, at(r, 1).effectiveHeight().status());
        assertNull(at(r, 1).effectiveHeight().value());
        assertEquals(1, r.counts().effectiveHeightUnknown());
    }

    @Test
    void reportsBlockDisappearingAndUnknownLightWithoutInventingAir() {
        Fake f =
                new Fake() {
                    public Light light(Position p) {
                        return new Light(Status.UNKNOWN, null, null, "pending");
                    }
                };
        f.onlyFirst = true;
        f.states.put(
                new Position(-16, 70, -32),
                Block.unavailable(Status.UNLOADED, "unloaded after surface read"));
        Report r = capture(0, 0, 0, f);
        assertEquals(1, r.counts().unloadedSamples());
        assertEquals(11, r.counts().lightUnknownSamples());
        assertNull(at(r, 70).block().air());
        assertEquals(Status.UNLOADED, at(r, 70).light().status());
        assertNull(at(r, 69).light().sky());
    }
}
