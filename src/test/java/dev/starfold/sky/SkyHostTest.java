package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

class SkyHostTest {
    @Test
    void standaloneAnswersAreNeutralAndServerAndClientInstallationsAreIndependent() {
        var server = SkyHost.get();
        var client = SkyHost.client();
        try {
            var neutral = new SkyHost() {};
            assertFalse(neutral.underWay());
            assertFalse(neutral.present());
            assertEquals(1, neutral.sunlight(null, null, null));
            assertEquals(new Point(1, 2, 3), neutral.storage(1, 2, 3));
            SkyHost.install(neutral);
            SkyHost.installClient(new SkyHost() {});
            assertSame(neutral, SkyHost.get());
            assertNotSame(neutral, SkyHost.client());
            var installedClient = SkyHost.client();
            SkyHost.install(new SkyHost() {});
            assertSame(installedClient, SkyHost.client());
            assertThrows(NullPointerException.class, () -> SkyHost.install(null));
            assertThrows(NullPointerException.class, () -> SkyHost.installClient(null));
        } finally {
            SkyHost.install(server);
            SkyHost.installClient(client);
        }
    }

    @Test
    void skyQuestionsRetainTheirGatesAndTheWiringRetainsTheirAnswers() throws Exception {
        var daylight = Sources.text("GroundDaylight");
        assertTrue(
                daylight.contains(
                        "if(PlanetLevels.is(level)&&Space.holds(pos.getX(),pos.getZ()))"));
        assertTrue(daylight.contains("returnSkyHost.get().sunlight(level,pos,clock)"));
        assertTrue(
                daylight.contains(
                        "if(!PlanetLevels.is(level)||!PlanetSystem.shared(level)||SkyHost.get().underWay())return"));
        var server = Sources.text("StarfoldServer");
        assertTrue(server.contains("SkyHost.install(newSkyHost()"));
        assertTrue(server.contains("returnGroundFlightServer.underWay()"));
        assertTrue(
                server.contains(
                        "SpaceServer.frame(level).universe(newdev.starfold.geometry.BentStripGeometry.Point(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5),clock.model,clock.tick())"));
        assertTrue(server.contains("returnSpaceView.sunlight(clock.model,eye,clock.tick())"));
        var weather = Sources.text("WeatherClient");
        assertTrue(
                weather.contains(
                        "if(level!=mc.level||mc.player==null||!PlanetLevels.is(level))returnfalse"));
        assertTrue(weather.contains("if(SkyHost.client().present())returntrue"));
        assertTrue(
                weather.contains(
                        "SkyHost.client().storage(mc.player.getX(),mc.player.getEyeY(),mc.player.getZ())"));
        var client = Sources.text("StarfoldClient");
        assertTrue(client.contains("SkyHost.installClient(newSkyHost()"));
        assertTrue(client.contains("returnSpaceClient.present()"));
        assertTrue(client.contains("returnGroundClient.storage(x,y,z)"));
        assertTrue(
                client.indexOf("SkyHost.installClient(")
                        < client.indexOf("GroundClient.initialize("));
        for (String name : new String[] {"GroundDaylight", "Weather", "WeatherClient", "SkyHost"}) {
            var text = Sources.text(name);
            for (String layer :
                    new String[] {"flight", "space", "ship", "ground", "render", "waypoint"})
                assertFalse(
                        text.contains("dev.starfold." + layer + "."),
                        name + " must not name " + layer);
        }
    }
}
