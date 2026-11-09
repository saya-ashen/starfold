package dev.starfold.ship;

import dev.starfold.space.SpacePackets;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class ShipHostTest {
    @Test
    void shipSourcesNeverNameTheHigherRegionLayer() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java/dev/starfold/ship"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList())
                assertFalse(Files.readString(file).replace("import dev.starfold.space.SpacePackets;", "")
                        .contains("dev.starfold.space"), file.toString());
        }
    }

    @Test
    void standaloneDefaultsHaveNoExternalControllerOrPreparedArrival() {
        var host = new ShipHost() {};
        assertFalse(host.externalRoll(null, null));
        assertFalse(host.landingLoaded(null, null));
        assertFalse(host.landingReady(dev.starfold.chart.GroundSolar.defaults().body(0), 0, true));
        assertEquals(Space.Frame.IDENTITY, host.surroundingsFrame(null));
        assertEquals(dev.starfold.chart.GroundEphemeris.ZERO, host.carriedSpin(null, null));
        assertEquals(Space.Frame.IDENTITY, host.departureFrame(null, null, null, null, 0, 0));
        assertEquals(new SpacePackets.Frame(null, 0), host.carriedFrame(null));
        assertEquals(
                new net.minecraft.world.level.ChunkPos(-1, 2),
                host.landingColumn(new Point(-.1, 0, 32)));
        assertDoesNotThrow(
                () -> {
                    host.pilotReleased(null);
                    host.releaseArrival(null);
                    host.advanceSurroundings(null, null);
                    host.hullDeparted(null, 0, "hull");
                    host.hullEnteredChart(null, "hull");
                    host.hullArrived(null, null);
                });
        var client = new ShipClientHost() {};
        assertFalse(client.movingSurroundings());
        assertFalse(client.mouseSteering());
        assertDoesNotThrow(() -> client.carriedFrame(null));
    }

    @Test
    void clientWiringPreservesTheFrameFenceAndViewQueries() throws Exception {
        var source = Sources.text("StarfoldClient");
        for (String text :
                new String[] {
                    "ShipClientHost.install(new ShipClientHost(){",
                    "public boolean movingSurroundings(){return SpaceClient.present();}",
                    "public boolean mouseSteering(){return SpaceAttitudeClient.mouseFlight();}",
                    "public void carriedFrame(SpacePackets.Frame frame){SpaceClient.carried(frame);}"
                }) assertTrue(source.contains(text), text);
        assertTrue(
                source.indexOf("installShipHost();")
                        < source.indexOf("initializeClientSystems(modBus)"));
    }
}
