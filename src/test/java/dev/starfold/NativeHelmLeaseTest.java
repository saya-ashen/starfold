package dev.starfold;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Loop4 regression: transient command expiry must not turn held W into deck walking. */
class NativeHelmLeaseTest {
    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/dev/starfold/NativeHelmInputClient.java"));
    }
    @Test void identityAndFacingDoNotUseTheCommandLease() throws Exception {
        var text=source();
        var body=text.substring(text.indexOf("public static ClientSubLevel body()"),text.indexOf("static int power("));
        assertTrue(body.contains("authority(pilot)"));
        assertTrue(body.contains("var s=authority(body.getUniqueId())"));
        assertFalse(body.contains("state(pilot)"));
        assertFalse(body.contains("var s=state(body.getUniqueId())"));
        var authority=text.substring(text.indexOf("private static NativeHelmPackets.State authority("),text.indexOf("public static ClientSubLevel body()"));
        assertFalse(authority.contains("fresh("));
        assertFalse(authority.contains("getGameTime("));
    }
    @Test void commandsStillExpireAndReleaseStillClearsIdentity() throws Exception {
        var text=source();
        assertTrue(text.contains("NativeHelmCommand.fresh(s.tick(),mc.level.getGameTime())?s.state():null"));
        assertTrue(text.contains("var s=state(body.getUniqueId());return NativeHelmCommand.effective"));
        assertTrue(text.contains("else states.remove(p.body())"));
        assertTrue(text.contains("else if(p.body().equals(pilot))pilot=null"));
        assertTrue(text.contains("states.clear();pilot=null;level=null"));
        assertTrue(NativeHelmCommand.fresh(100,105));
        assertFalse(NativeHelmCommand.fresh(100,106));
        assertFalse(NativeHelmCommand.fresh(100,99));
    }
}
