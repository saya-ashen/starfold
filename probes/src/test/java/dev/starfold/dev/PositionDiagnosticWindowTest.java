package dev.starfold.dev;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class PositionDiagnosticWindowTest {
    private static final UUID TARGET = UUID.fromString("b618951d-e41c-3388-8dcd-d8c85c5c978a");

    @Test
    void disabledMissingAndInvalidIdentityFailClosed() {
        for (var window : new PositionDiagnosticWindow[] {
                new PositionDiagnosticWindow(false, TARGET.toString()),
                new PositionDiagnosticWindow(true, null),
                new PositionDiagnosticWindow(true, "D1Smoke"),
                new PositionDiagnosticWindow(true, "1-1-1-1-1")}) {
            assertFalse(window.enabled());
            assertEquals(0, window.reserve(TARGET, 0));
            assertFalse(window.stack(0, true));
        }
    }

    @Test
    void onlyTargetStartsWindowAndIdsStayMonotonicAcrossReconnects() {
        var window = new PositionDiagnosticWindow(true, TARGET.toString());
        assertEquals(0, window.reserve(UUID.randomUUID(), 0));
        long start = PositionDiagnosticWindow.DURATION_NANOS * 2;
        assertEquals(1, window.reserve(TARGET, start));
        assertEquals(2, window.reserve(TARGET, start + 1));
        // No connection-specific reset exists: later sessions share the original deadline and IDs.
        assertEquals(3, window.reserve(TARGET, start + PositionDiagnosticWindow.DURATION_NANOS - 1));
        assertEquals(0, window.reserve(TARGET, start + PositionDiagnosticWindow.DURATION_NANOS));
        assertEquals(0, window.reserve(TARGET, start + PositionDiagnosticWindow.DURATION_NANOS * 2));
        assertFalse(window.stack(0, true));
    }

    @Test
    void nestedWritesRetainTheirPacketCauseAndReconnectGetsANewConnectionNotANewBudget() {
        var window = new PositionDiagnosticWindow(true, TARGET.toString());
        var connection = new Object();
        var ack = window.begin(TARGET, 0, connection);
        var write = window.begin(TARGET, 1, connection);
        assertEquals(1, ack.id());
        assertEquals(0, ack.parent());
        assertEquals(ack.id(), write.parent());
        assertEquals(ack.connection(), write.connection());
        assertEquals(3, window.end(TARGET, 2, write));
        var anotherWrite = window.begin(TARGET, 3, connection);
        assertEquals(ack.id(), anotherWrite.parent());
        window.end(TARGET, 4, anotherWrite);
        window.end(TARGET, 5, ack);
        var next = window.begin(TARGET, 6, new Object());
        assertEquals(7, next.id());
        assertEquals(0, next.parent());
        assertEquals(ack.connection() + 1, next.connection());
        assertEquals(0, window.end(TARGET, PositionDiagnosticWindow.DURATION_NANOS, next));
        assertNull(window.begin(TARGET, PositionDiagnosticWindow.DURATION_NANOS + 1, new Object()));
    }

    @Test
    void hardEventAndStackCapsRejectWithoutCollectingFurtherStacks() {
        var window = new PositionDiagnosticWindow(true, TARGET.toString());
        assertFalse(window.stack(0, true));
        assertFalse(window.stack(1, false));
        for (int i = 1; i <= PositionDiagnosticWindow.MAX_EVENTS; i++) {
            assertEquals(i, window.reserve(TARGET, i));
            assertEquals(i <= PositionDiagnosticWindow.MAX_STACKS, window.stack(i, true));
        }
        assertEquals(0, window.reserve(TARGET, PositionDiagnosticWindow.MAX_EVENTS + 1));
        assertFalse(window.stack(0, true));
    }
}
