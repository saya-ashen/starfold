package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class GasDamageCooldownTest {
    private static UUID hull(long id) {
        return new UUID(17, id);
    }

    @Test
    void sameUuidRetainsSuccessAcrossDormancyAndSnapshotReconstruction() {
        var cooldown = new GasDamageCooldown();
        assertTrue(cooldown.ready(hull(1), 1000, 100));
        cooldown.succeeded(hull(1), 1000, 100);
        // Dormancy must not erase timestamp state, even if the native hull object changes.
        var saved = cooldown.snapshot(1001);
        var reconstructed = new GasDamageCooldown(saved);
        assertFalse(reconstructed.ready(new UUID(17, 1), 1001, 100));
        assertFalse(reconstructed.ready(hull(1), 1099, 100));
        assertTrue(reconstructed.ready(hull(1), 1100, 100));
        assertTrue(reconstructed.snapshot(1100).stamps().isEmpty());
    }

    @Test
    void successfulMutationOnlyAndDynamicIntervalsWithoutAccumulatingExposure() {
        var cooldown = new GasDamageCooldown();
        assertFalse(cooldown.ready(hull(1), 10, 0));
        assertTrue(cooldown.ready(hull(1), 10, 100));
        assertTrue(
                cooldown.ready(hull(1), 11, 100),
                "a failed block attempt must not consume cadence");
        cooldown.succeeded(hull(1), 11, 100);
        assertFalse(cooldown.ready(hull(1), 30, 20));
        assertTrue(cooldown.ready(hull(1), 31, 20));
        assertFalse(cooldown.ready(hull(1), 31, 100));
        assertFalse(cooldown.ready(hull(1), 200, 0), "time alone never creates exposure damage");
        assertTrue(cooldown.ready(hull(1), 200, 100));
    }

    @Test
    void twoWritesPerTickRemainBoundedAcrossRollbackAndReconstruction() {
        var cooldown = new GasDamageCooldown();
        cooldown.succeeded(hull(1), 100, 20);
        cooldown.succeeded(hull(2), 100, 20);
        assertFalse(cooldown.ready(hull(3), 100, 20));
        var loaded = new GasDamageCooldown(cooldown.snapshot(100));
        assertFalse(loaded.ready(hull(3), 100, 20));
        assertFalse(loaded.ready(hull(3), 0, 20));
        assertFalse(loaded.ready(hull(3), 99, 20));
        assertTrue(loaded.ready(hull(3), 101, 20));
        assertFalse(loaded.ready(hull(1), 101, 20));
        assertFalse(loaded.ready(hull(1), 119, 20));
        assertTrue(loaded.ready(hull(1), 120, 20));
        assertThrows(IllegalArgumentException.class, () -> loaded.ready(hull(1), -1, 20));
    }

    @Test
    void recentStorageIsBoundedByWindowAndGlobalWriteBudgetNotFleetSize() {
        var cooldown = new GasDamageCooldown();
        for (int tick = 0; tick < 1000; tick++) {
            cooldown.succeeded(hull(2L * tick), tick, 20);
            cooldown.succeeded(hull(2L * tick + 1), tick, 20);
            assertFalse(cooldown.ready(hull(10000 + tick), tick, 20));
            assertTrue(cooldown.snapshot(tick).stamps().size() <= GasDamageCooldown.MAX_RECENT);
        }
        assertEquals(200, cooldown.snapshot(999).stamps().size());
        assertTrue(cooldown.snapshot(1099).stamps().isEmpty());
    }

    @Test
    void malformedOrOverBudgetSavedCooldownIsRejectedInsteadOfBecomingEmpty() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GasDamageCooldown(
                                new GasDamageCooldown.Snapshot(
                                        100, List.of(new GasDamageCooldown.Stamp(hull(1), 101)))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GasDamageCooldown(
                                new GasDamageCooldown.Snapshot(
                                        100, List.of(new GasDamageCooldown.Stamp(hull(1), 0)))));
        var stamps = new ArrayList<GasDamageCooldown.Stamp>();
        for (int i = 0; i < 3; i++) stamps.add(new GasDamageCooldown.Stamp(hull(i), 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GasDamageCooldown(new GasDamageCooldown.Snapshot(100, stamps)));
        var duplicate =
                List.of(
                        new GasDamageCooldown.Stamp(hull(1), 99),
                        new GasDamageCooldown.Stamp(hull(1), 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GasDamageCooldown(new GasDamageCooldown.Snapshot(100, duplicate)));
        var source = new ArrayList<>(List.of(new GasDamageCooldown.Stamp(hull(1), 100)));
        var saved = new GasDamageCooldown.Snapshot(100, source);
        source.clear();
        assertEquals(1, saved.stamps().size());
        assertThrows(UnsupportedOperationException.class, () -> saved.stamps().clear());
    }

    @Test
    void splitPieceWaitsOutSourceCadenceWithoutEnteringTheSnapshot() {
        var cooldown = new GasDamageCooldown();
        cooldown.succeeded(hull(1), 100, 20);
        cooldown.inherit(hull(2), hull(1));
        assertFalse(cooldown.ready(hull(2), 109, 20), "a new UUID must not reset the cadence");
        // Chains: a piece of a piece still waits for the original write.
        cooldown.inherit(hull(3), hull(2));
        assertFalse(cooldown.ready(hull(3), 119, 20));
        assertFalse(cooldown.ready(hull(2), 119, 20));
        assertTrue(cooldown.ready(hull(2), 120, 20));
        assertTrue(cooldown.ready(hull(3), 120, 20));
        // A piece's own success counts; inheritance never extends past the later of the two.
        cooldown.succeeded(hull(2), 120, 20);
        assertFalse(cooldown.ready(hull(2), 139, 20));
        assertTrue(cooldown.ready(hull(2), 140, 20));
        // Transient only: the saved stamps keep the two-writes-per-tick bound.
        var stamps = cooldown.snapshot(140).stamps();
        assertEquals(java.util.Set.of(hull(1), hull(2)),
                stamps.stream().map(GasDamageCooldown.Stamp::hull).collect(java.util.stream.Collectors.toSet()));
        assertDoesNotThrow(() -> new GasDamageCooldown(cooldown.snapshot(140)));
    }

    @Test
    void splitFromAnUndamagedSourceAddsNothingAndInheritanceExpires() {
        var cooldown = new GasDamageCooldown();
        cooldown.inherit(hull(2), hull(1));
        assertTrue(cooldown.ready(hull(2), 5, 20));
        cooldown.succeeded(hull(1), 10, 100);
        cooldown.inherit(hull(3), hull(1));
        assertFalse(cooldown.ready(hull(3), 109, 100));
        assertTrue(cooldown.ready(hull(3), 110, 100));
        assertThrows(IllegalArgumentException.class, () -> cooldown.inherit(null, hull(1)));
        assertThrows(IllegalArgumentException.class, () -> cooldown.inherit(hull(1), null));
    }
}
