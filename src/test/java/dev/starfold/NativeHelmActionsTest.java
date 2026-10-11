package dev.starfold;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeHelmActionsTest {
    @Test void leaseBlocksWorldAndInventoryActionsInEveryView(){for(var action:NativeHelmActions.Action.values()){assertTrue(NativeHelmActions.allowed(false,action));assertEquals(action==NativeHelmActions.Action.FLIGHT||action==NativeHelmActions.Action.CHAT||action==NativeHelmActions.Action.MENU,NativeHelmActions.allowed(true,action),action.toString());}}
}
