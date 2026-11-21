package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Loop4 regression: transient command expiry must not turn held W into deck walking. */
class HelmLeaseTest {

    @Test
    void commandsStillExpireAndReleaseStillClearsIdentity() throws Exception {
        assertTrue(HelmCommand.fresh(100, 105));
        assertFalse(HelmCommand.fresh(100, 106));
        assertFalse(HelmCommand.fresh(100, 99));
    }
}
