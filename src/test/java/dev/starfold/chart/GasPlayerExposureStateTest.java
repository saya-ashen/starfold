package dev.starfold.chart;

import static dev.starfold.chart.GasPlayerExposureState.Participation.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Optional;

class GasPlayerExposureStateTest {
    private static final GasPlayerExposure.Sample DANGER = new GasPlayerExposure.Sample(
            GasPlayerExposure.Condition.DANGER, Optional.of(new GasPressure.Reading(1, 512, 1)));
    private static final GasPlayerExposure.Sample SAFE = GasPlayerExposure.confirmedSafe();
    private static final GasPlayerExposure.Sample UNKNOWN = GasPlayerExposure.unknown();

    private static GasPlayerExposureState initial() {
        return GasPlayerExposureState.restored(GasPlayerExposureValues.initial());
    }

    private static GasPlayerExposureState exhausted() {
        return GasPlayerExposureState.restored(new GasPlayerExposureValues(0, 0, 0));
    }

    @Test
    void hundredFullDangerTicksThenOneAttemptEveryTwentyDangerTicks() {
        var state = initial();
        for (int tick = 0; tick < 161; tick++) {
            var next = state.tick(tick, DANGER, ACTIVE);
            assertEquals(tick >= 100 && (tick - 100) % 20 == 0, next.damageAttempt(), "tick=" + tick);
            assertEquals(Math.max(0, 99 - tick), next.state().values().graceTicks());
            assertEquals(0, next.state().values().safeTicks());
            state = next.state();
        }
        assertEquals(4, GasPlayerExposureState.DAMAGE);
    }

    @Test
    void duplicateAndBackwardCallsCannotRetryIncludingCancelledOrSpawnProtectedHurt() {
        var attempted = exhausted().tick(1000, DANGER, ACTIVE);
        assertTrue(attempted.damageAttempt());
        assertEquals(20, attempted.state().values().attemptTicks());
        // Ordinary hurt may return false: no acknowledgement or success parameter can undo cadence.
        for (long tick : new long[] {1000, 999, 0}) {
            var duplicate = attempted.state().tick(tick, DANGER, ACTIVE);
            assertFalse(duplicate.damageAttempt());
            assertSame(attempted.state(), duplicate.state());
            assertEquals(0, attempted.state().tick(tick, SAFE, ACTIVE).state().values().safeTicks());
        }
        var state = attempted.state();
        for (long tick = 1001; tick < 1020; tick++) {
            var next = state.tick(tick, DANGER, ACTIVE);
            assertFalse(next.damageAttempt());
            state = next.state();
        }
        assertTrue(state.tick(1020, DANGER, ACTIVE).damageAttempt());
    }

    @Test
    void exitingStopsDamageAndNinetyNineSafeTicksCannotRechargeGraceOrAttemptTimer() {
        var state = exhausted().tick(0, DANGER, ACTIVE).state();
        for (int tick = 1; tick <= 99; tick++) {
            var next = state.tick(tick, SAFE, ACTIVE);
            assertFalse(next.damageAttempt());
            assertEquals(0, next.state().values().graceTicks());
            assertEquals(20, next.state().values().attemptTicks());
            state = next.state();
        }
        var reentry = state.tick(100, DANGER, ACTIVE);
        assertFalse(reentry.damageAttempt());
        assertEquals(0, reentry.state().values().safeTicks());
        assertEquals(19, reentry.state().values().attemptTicks());
        var recovered = state.tick(100, SAFE, ACTIVE);
        assertEquals(new GasPlayerExposureValues(100, 100, 0), recovered.state().values());
        assertEquals(99, recovered.state().tick(101, DANGER, ACTIVE).state().values().graceTicks());
    }

    @Test
    void repeatedCrossingsAccumulateDangerInsteadOfRearmingGrace() {
        var state = initial();
        for (int i = 0; i < 100; i++) {
            var next = state.tick(i * 2L, DANGER, ACTIVE);
            assertFalse(next.damageAttempt());
            state = next.state().tick(i * 2L + 1, SAFE, ACTIVE).state();
        }
        assertEquals(0, state.values().graceTicks());
        assertTrue(state.tick(200, DANGER, ACTIVE).damageAttempt());
    }

    @Test
    void unknownDeathAndExemptModesBreakRecoveryButFreezeDangerTimersAndStillReportRisk() {
        for (var participation : GasPlayerExposureState.Participation.values()) {
            var state = GasPlayerExposureState.restored(new GasPlayerExposureValues(0, 99, 7));
            var sample = participation == ACTIVE ? UNKNOWN : DANGER;
            var next = state.tick(0, sample, participation);
            assertFalse(next.damageAttempt());
            assertEquals(new GasPlayerExposureValues(0, 0, 7), next.state().values());
            assertEquals(sample.risk(), next.sample().risk());
            var returned = next.state().tick(1, DANGER, ACTIVE);
            assertFalse(returned.damageAttempt());
            assertEquals(new GasPlayerExposureValues(0, 0, 6), returned.state().values());
        }
        for (var participation : new GasPlayerExposureState.Participation[] {EXEMPT, DEAD}) {
            var state = initial();
            for (int tick = 0; tick < 1000; tick++)
                state = state.tick(tick, SAFE, participation).state();
            assertEquals(GasPlayerExposureValues.initial(), state.values());
            assertEquals(99, state.tick(1000, DANGER, ACTIVE).state().values().graceTicks());
        }
    }

    @Test
    void deathToRespawnCopiesStateWithoutGivingNewGraceOrBypassingSameTickGuard() {
        var damaged = exhausted().tick(10, DANGER, ACTIVE).state();
        var dead = damaged.tick(11, DANGER, DEAD).state();
        assertEquals(damaged.values(), dead.values());
        assertFalse(dead.tick(11, DANGER, ACTIVE).damageAttempt());
        var respawned = dead.tick(12, DANGER, ACTIVE);
        assertFalse(respawned.damageAttempt());
        assertEquals(19, respawned.state().values().attemptTicks());
        assertEquals(0, respawned.state().values().graceTicks());
    }

    @Test
    void offlineClockJumpsAndSessionRebasesNeverCatchUpAndReloadPreservesCountdown() {
        var state = initial().tick(1000, DANGER, ACTIVE).state();
        assertEquals(99, state.values().graceTicks());
        var restored = GasPlayerExposureState.restored(
                GasPlayerExposureValues.fromFields(state.values().toFields()));
        assertEquals(98, restored.tick(1, DANGER, ACTIVE).state().values().graceTicks());
        assertEquals(98, state.tick(Long.MAX_VALUE, DANGER, ACTIVE).state().values().graceTicks());
        var waiting = exhausted().tick(1000, DANGER, ACTIVE).state();
        assertFalse(waiting.tick(Long.MAX_VALUE, DANGER, ACTIVE).damageAttempt());
        assertEquals(19, waiting.tick(Long.MAX_VALUE, DANGER, ACTIVE).state().values().attemptTicks());
        var safe = GasPlayerExposureState.restored(new GasPlayerExposureValues(0, 30, 0));
        assertEquals(31, safe.tick(Long.MAX_VALUE, SAFE, ACTIVE).state().values().safeTicks());
    }

    @Test
    void knownNonGasWorldRecoversWhileUnknownNeverRecharges() {
        var state = exhausted();
        for (int tick = 0; tick < 200; tick++) state = state.tick(tick, UNKNOWN, ACTIVE).state();
        assertEquals(new GasPlayerExposureValues(0, 0, 0), state.values());
        for (int tick = 200; tick < 300; tick++) state = state.tick(tick, SAFE, ACTIVE).state();
        assertEquals(new GasPlayerExposureValues(100, 100, 0), state.values());
        assertEquals(new GasPlayerExposureValues(100, 100, 0), state.tick(300, SAFE, ACTIVE).state().values());
    }

    @Test
    void overlappingGasStillReturnsOnlyOneAttemptAndModeChangesCannotRearmIt() {
        var layers = java.util.List.of(
                new GasPressure.Layer(1, GroundEphemeris.ZERO, 4096, GasGiantTest.policy()),
                new GasPressure.Layer(2, GroundEphemeris.ZERO, 4096, GasGiantTest.policy()));
        var sample = GasPlayerExposure.sample(layers, GroundEphemeris.ZERO);
        var next = exhausted().tick(0, sample, ACTIVE);
        assertTrue(next.damageAttempt());
        var creative = next.state().tick(1, sample, EXEMPT);
        assertFalse(creative.damageAttempt());
        assertEquals(next.state().values(), creative.state().values());
        assertFalse(creative.state().tick(2, sample, ACTIVE).damageAttempt());
    }

    @Test
    void programmerErrorsRejectNegativeTickAndInvalidTransientState() {
        assertThrows(IllegalArgumentException.class, () -> initial().tick(-1, SAFE, ACTIVE));
        assertThrows(IllegalArgumentException.class,
                () -> new GasPlayerExposureState(GasPlayerExposureValues.initial(), -2));
    }
}
