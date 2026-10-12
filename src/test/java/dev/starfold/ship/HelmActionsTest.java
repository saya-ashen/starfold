package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HelmActionsTest {
    @Test
    void leaseBlocksWorldAndInventoryActionsInEveryView() {
        for (var action : HelmActions.Action.values()) {
            assertTrue(HelmActions.allowed(false, action));
            assertEquals(
                    action == HelmActions.Action.FLIGHT
                            || action == HelmActions.Action.CHAT
                            || action == HelmActions.Action.MENU,
                    HelmActions.allowed(true, action),
                    action.toString());
        }
    }
}
