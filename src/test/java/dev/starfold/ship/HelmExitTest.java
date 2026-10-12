package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.level.GameType;

import org.junit.jupiter.api.Test;

class HelmExitTest {
    @Test
    void survivalAndAdventureNeedConfirmationInSpace() {
        for (var mode : new GameType[] {GameType.SURVIVAL, GameType.ADVENTURE}) {
            var rule = new HelmExit();
            assertFalse(rule.leave(mode, true, 100));
            assertTrue(rule.leave(mode, true, 159));
        }
    }

    @Test
    void creativeAndSpectatorLeaveImmediately() {
        for (var mode : new GameType[] {GameType.CREATIVE, GameType.SPECTATOR})
            assertTrue(new HelmExit().leave(mode, true, 100));
    }

    @Test
    void allModesLeaveChartsImmediately() {
        for (var mode : GameType.values()) assertTrue(new HelmExit().leave(mode, false, 100));
    }

    @Test
    void exactThreeSecondsExpires() {
        var rule = new HelmExit();
        assertFalse(rule.leave(GameType.SURVIVAL, true, 100));
        assertFalse(rule.leave(GameType.SURVIVAL, true, 160));
        assertTrue(rule.leave(GameType.SURVIVAL, true, 161));
    }

    @Test
    void confirmationIsConsumed() {
        var rule = new HelmExit();
        assertFalse(rule.leave(GameType.SURVIVAL, true, 100));
        assertTrue(rule.leave(GameType.SURVIVAL, true, 101));
        assertFalse(rule.leave(GameType.SURVIVAL, true, 102));
    }

    @Test
    void clockRollbackDoesNotConfirm() {
        var rule = new HelmExit();
        assertFalse(rule.leave(GameType.SURVIVAL, true, 100));
        assertFalse(rule.leave(GameType.SURVIVAL, true, 99));
    }

    @Test
    void chartPressClearsPending() {
        var rule = new HelmExit();
        assertFalse(rule.leave(GameType.SURVIVAL, true, 100));
        assertTrue(rule.leave(GameType.SURVIVAL, false, 101));
        assertFalse(rule.leave(GameType.SURVIVAL, true, 102));
    }
}
