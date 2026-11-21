package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GroundLodRetryTest {
    @Test
    void failuresDelayRetainedDemandWithoutBlockingOtherCanonicalKeys() {
        var retry = new GroundLodRetry();
        long now = 1_000_000_000L;
        retry.failed(98_303, now);
        assertFalse(retry.ready(98_303, now));
        assertFalse(retry.ready(98_303, now + 249_999_999));
        assertTrue(retry.ready(98_303, now + 250_000_000));
        assertTrue(retry.ready(0, now));
        retry.succeeded(98_303);
        assertTrue(retry.ready(98_303, now));
        retry.failed(98_303, now);
        assertTrue(retry.ready(98_303, now + 250_000_000));
    }

    @Test
    void persistentFailuresAreBoundedAndRemainRetryable() {
        var retry = new GroundLodRetry();
        for (int count = 0; count < 100; count++) retry.failed(4, 0);
        assertFalse(retry.ready(4, 29_999_999_999L));
        assertTrue(retry.ready(4, 30_000_000_000L));
    }
}
