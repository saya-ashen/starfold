package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class SpaceHostTest {
    @Test
    void regionAndHullsHaveNoWaypointOrRenderingDependencies() throws Exception {
        for (String layer : List.of("space", "ship")) {
            var forbidden =
                    java.util.regex.Pattern.compile(
                            layer.equals("space")
                                    ? "dev\\.starfold\\.(waypoint|render)"
                                    : "dev\\.starfold\\.waypoint");
            try (var files = Files.walk(Path.of("src/main/java/dev/starfold/" + layer))) {
                for (var file : files.filter(p -> p.toString().endsWith(".java")).toList())
                    assertFalse(forbidden.matcher(Files.readString(file)).find(), file.toString());
            }
        }
        assertTrue(Files.exists(Path.of("src/main/java/dev/starfold/space/RenderFrame.java")));
        assertTrue(Files.exists(Path.of("src/main/java/dev/starfold/waypoint/ShipGuide.java")));
    }

    @Test
    void serverKeepsTargetSessionAndInstallsAfterExistingHosts() throws Exception {
        var server = Sources.text("StarfoldServer");
        assertTrue(server.contains("SpaceHost.install(new SpaceHost()"));
        assertTrue(server.indexOf("SpaceHost.install(") > server.indexOf("SkyHost.install("));
        assertTrue(
                server.indexOf("SpaceHost.install(") < server.indexOf("SpaceServer.initialize()"));
        assertTrue(
                server.contains(
                        "void targetSync(net.minecraft.server.level.ServerPlayer player,long session){WaypointServer.send(player,session);}"));
        assertTrue(
                Sources.text("SpaceServer")
                        .contains("SpaceHost.get().targetSync(player,v.packet().session())"));
        assertFalse(server.contains("SpaceClientHost"));
    }

    @Test
    void clientKeepsOriginalDelegatesAndBookmarkNullCondition() throws Exception {
        var client = Sources.text("StarfoldClient");
        assertTrue(client.contains("SpaceClientHost.install(new SpaceClientHost()"));
        assertTrue(
                client.indexOf("SpaceClientHost.install(")
                        > client.indexOf("FlightClientHost.install("));
        assertTrue(
                client.indexOf("SpaceClientHost.install(")
                        < client.indexOf("GroundClient.initialize("));
        for (String delegate :
                List.of(
                        "GroundEphemeris.Model ephemeris(){return GroundClient.ephemeris();}",
                        "double skyTime(float delta){return GroundClient.skyTime(delta);}",
                        "void viewLost(){PlanetRemoteRenderer.clear();}",
                        "int sunLimit(){return GroundClient.SUNS;}",
                        "int planet(){return GroundClient.planet();}",
                        "void captureTarget(RenderFrame context){WaypointClient.capture(context);}",
                        "int launchPlanet(){var launch=WaypointClient.active();return launch==null?-1:launch.planet();}",
                        "FlightSnapshot.Home home(){return WaypointClient.home();}"))
            assertTrue(client.contains(delegate), delegate);
        var navigation = Sources.text("SpaceNavigationClient");
        assertTrue(
                navigation.contains(
                        "SpaceClientHost.get().captureTarget(context);boolean chart=HullLandingClient.active()"));
        assertTrue(navigation.contains("int launchPlanet=SpaceClientHost.get().launchPlanet()"));
        assertTrue(navigation.contains("SpaceClientHost.get().home(),ArrivalState.Phase.CRUISE"));
    }

    @Test
    void standaloneHostsHaveNoViewsOrTargets() {
        var server = new SpaceHost() {};
        assertDoesNotThrow(() -> server.targetSync(null, 42));
        var client = new SpaceClientHost() {};
        assertNull(client.ephemeris());
        assertEquals(0, client.skyTime(.5f));
        assertEquals(0, client.sunLimit());
        assertEquals(-1, client.planet());
        assertEquals(-1, client.launchPlanet());
        assertNull(client.home());
        assertDoesNotThrow(client::viewLost);
        assertDoesNotThrow(() -> client.captureTarget(null));
    }
}
