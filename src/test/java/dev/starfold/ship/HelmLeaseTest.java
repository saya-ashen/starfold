package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;

import org.junit.jupiter.api.Test;

/** Loop4 regression: transient command expiry must not turn held W into deck walking. */
class HelmLeaseTest {
    @Test
    void identityAndFacingDoNotUseTheCommandLease() throws Exception {
        var text = Sources.text("HelmInputClient");
        var body =
                text.substring(
                        text.indexOf("public static ClientSubLevel body()"),
                        text.indexOf("static int power("));
        assertTrue(body.contains("authority(pilot)"));
        assertTrue(body.contains("var s=authority(body.getUniqueId())"));
        assertFalse(body.contains("state(pilot)"));
        assertFalse(body.contains("var s=state(body.getUniqueId())"));
        var authority =
                text.substring(
                        text.indexOf("private static HelmPackets.State authority("),
                        text.indexOf("public static ClientSubLevel body()"));
        assertFalse(authority.contains("fresh("));
        assertFalse(authority.contains("getGameTime("));
    }

    @Test
    void commandsStillExpireAndReleaseStillClearsIdentity() throws Exception {
        var text = Sources.text("HelmInputClient");
        assertTrue(
                text.contains("HelmCommand.fresh(s.tick(),mc.level.getGameTime())?s.state():null"));
        assertTrue(text.contains("var s=state(body.getUniqueId());return HelmCommand.effective"));
        assertTrue(text.contains("else states.remove(p.body())"));
        assertTrue(text.contains("else if(p.body().equals(pilot))pilot=null"));
        assertTrue(text.contains("states.clear();pilot=null;level=null"));
        assertTrue(HelmCommand.fresh(100, 105));
        assertFalse(HelmCommand.fresh(100, 106));
        assertFalse(HelmCommand.fresh(100, 99));
    }
}
