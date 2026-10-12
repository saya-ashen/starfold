package dev.starfold.flight;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class FlightHostTest {
    @Test
    void personalFlightHasNoUpperLayerReferences() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java/dev/starfold/flight"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertFalse(
                        java.util.regex.Pattern.compile(
                                        "dev\\.starfold\\.(ship|space|waypoint|render)")
                                .matcher(Files.readString(file))
                                .find(),
                        file.toString());
            }
        }
    }

    @Test
    void serverRequestsKeepTheirOriginalDelegatesAndStayServerOnly() throws Exception {
        var wiring = Sources.text("StarfoldServer");
        for (String delegate :
                List.of(
                        "void viewLost(net.minecraft.server.level.ServerPlayer player){GroundViewServer.remove(player);}",
                        "void targetSync(net.minecraft.server.level.ServerPlayer player){WaypointServer.send(player);}",
                        "void targetCleared(net.minecraft.server.level.ServerPlayer player){WaypointSave.get(player.serverLevel()).clear(player.getUUID());}",
                        "Component failure(RuntimeException error){return WaypointServer.failure(error);}"))
            assertTrue(wiring.contains(delegate), delegate);
        assertTrue(wiring.contains("FlightHost.install(new FlightHost()"));
        assertFalse(wiring.contains("FlightClientHost"));
        var flight = Sources.text("GroundFlightServer");
        for (String request :
                List.of(
                        "viewLost(player)",
                        "targetSync(player)",
                        "targetCleared(player)",
                        "failure(error)"))
            assertTrue(flight.contains("FlightHost.get()." + request), request);
    }

    @Test
    void clientRequestsKeepTheirOriginalDelegates() throws Exception {
        var wiring = Sources.text("StarfoldClient");
        assertTrue(wiring.contains("FlightClientHost.install(new FlightClientHost()"));
        for (String delegate :
                List.of(
                        "void departing(){PlanetRemoteRenderer.departing();}",
                        "boolean shared(Level level){return SpaceClient.shared(level);}",
                        "void sessionBound(ClientLevel level,long session){GroundViewClient.bindSession(level,session);}",
                        "void atmosphere(GroundAir.Config config){GroundClient.air(config);}",
                        "void metadata(GroundEphemeris.Model model,long tick){GroundClient.metadata(model,tick);}",
                        "boolean viewReady(){return GroundClient.ready();}",
                        "boolean diagnosticHud(){return GroundClient.DEBUG_HUD;}",
                        "Point chartPosition(int planet,PlanetShape.Pose pose){return GroundClient.plane(planet,pose);}",
                        "int chartTurns(Face face){return GroundClient.planeTurns(face);}",
                        "Point chartPoint(int planet,Face face,Point point){return GroundClient.planePoint(planet,face,point);}"))
            assertTrue(wiring.contains(delegate), delegate);
    }

    @Test
    void standaloneServerNoticesNeedNoUpperLayer() {
        var host = new FlightHost() {};
        assertDoesNotThrow(() -> host.viewLost(null));
        assertDoesNotThrow(() -> host.targetSync(null));
        assertDoesNotThrow(() -> host.targetCleared(null));
    }
}
