package dev.starfold.waypoint;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ShipGuideFlightTest {
    @Test
    void realGroundBeforeTakingHelmAllowsImmediatePoweredLiftWithoutTwentyMoreTicks() {
        var flight = new ShipGuide.Flight();
        flight.aboard(new Object());
        flight.ground(0, false, .15, .1);
        assertEquals(0, flight.settled);
        assertFalse(flight.lifted(0, true, true, .15));
        flight.ground(0, true, 3, 1);
        assertEquals(0, flight.settled);
        assertTrue(flight.lifted(0, true, true, 3));
        assertFalse(flight.lifted(0, false, true, 3), "navigation launch still required");
        assertFalse(flight.lifted(0, true, false, 3), "powered upward input still required");
    }

    @Test
    void passengerGroundEvidenceCannotSurviveAnUnpilotedDepartureAndAirborneHandover() {
        var flight = new ShipGuide.Flight();
        flight.aboard(new Object());
        flight.ground(0, false, .15, .1);
        flight.ground(0, false, 3, 1);
        flight.ground(0, true, 3, 1);
        assertFalse(flight.lifted(0, true, true, 3));
        flight.ground(0, true, .15, .1);
        flight.ground(0, true, 3, 1);
        assertTrue(flight.lifted(0, true, true, 3));
    }

    @Test
    void oneQuietObservationAtTheHelmIsEnoughForLiftButNotForArrival() {
        var flight = new ShipGuide.Flight();
        flight.aboard(new Object());
        flight.ground(0, true, .15, .1);
        assertEquals(1, flight.settled);
        flight.ground(0, true, 2.01, 1);
        assertEquals(0, flight.settled);
        assertTrue(flight.lifted(0, true, true, 2.01));
    }

    @Test
    void boardingInAirCannotUseAnExistingRemoteLaunchAsGroundEvidence() {
        for (double[] sample :
                new double[][] {
                    {3, 0},
                    {2, 0},
                    {-2.01, 0},
                    {.15, .5},
                    {.15, 1},
                    {Double.NaN, 0},
                    {Double.POSITIVE_INFINITY, 0},
                    {.15, Double.NaN}
                }) {
            var flight = new ShipGuide.Flight();
            flight.aboard(new Object());
            flight.ground(0, false, sample[0], sample[1]);
            flight.ground(0, true, 3, 1);
            assertFalse(
                    flight.lifted(0, true, true, 3),
                    "invalid ground sample: clearance=" + sample[0] + ", speed=" + sample[1]);
        }
    }

    @Test
    void groundEvidenceBelongsToThisHullAndPlanetAndIsClearedOutsideCharts() {
        var flight = new ShipGuide.Flight();
        var body = new Object();
        flight.aboard(body);
        flight.ground(0, false, .15, .1);
        flight.aboard(body);
        assertTrue(flight.lifted(0, true, true, 3));
        assertFalse(flight.lifted(1, true, true, 3));
        flight.ground(1, true, 3, 0);
        assertFalse(flight.lifted(1, true, true, 3));
        assertFalse(flight.lifted(0, true, true, 3));
        flight.ground(1, true, .15, .1);
        flight.aboard(new Object());
        assertFalse(flight.lifted(1, true, true, 3));
        flight.ground(1, true, .15, .1);
        flight.clearGround();
        assertEquals(0, flight.settled);
        assertFalse(flight.lifted(1, true, true, 3));
    }

    @Test
    void arrivalStillRequiresTwentyConsecutiveQuietPilotTicks() {
        var flight = new ShipGuide.Flight();
        flight.aboard(new Object());
        for (int tick = 0; tick < 30; tick++) flight.ground(1, false, .15, .1);
        assertEquals(0, flight.settled);
        for (int tick = 1; tick < 20; tick++) {
            flight.ground(1, true, .15, .1);
            assertEquals(tick, flight.settled);
        }
        flight.ground(1, true, .15, .5);
        assertEquals(0, flight.settled);
        for (int tick = 0; tick < 20; tick++) flight.ground(1, true, .15, .1);
        assertEquals(20, flight.settled);
        flight.ground(1, true, .15, .1);
        assertEquals(20, flight.settled);
        flight.ground(1, false, .15, .1);
        assertEquals(0, flight.settled);
    }
}
