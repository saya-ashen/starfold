package dev.starfold.ship;

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
                assertFalse(Files.readString(file).contains("dev.starfold.space"), file.toString());
        }
    }

    @Test
    void standaloneDefaultsHaveNoExternalControllerOrPreparedArrival() {
        var host = new ShipHost() {};
        assertEquals(200, host.minimumSpeed());
        assertEquals(new ShipHost.SpeedCaps(200, 200), host.speedCaps(null));
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
    void serverWiringPreservesEveryAnswerAndAnnouncement() throws Exception {
        var source = Sources.text("StarfoldServer");
        for (String text :
                new String[] {
                    "ShipHost.install(new ShipHost(){",
                    "public double minimumSpeed(){return SpaceSpeed.V_MIN;}",
                    "public SpeedCaps speedCaps(dev.ryanhcode.sable.sublevel.ServerSubLevel body){var caps=SpaceSpeedServer.caps(body);return new SpeedCaps(caps.cruise(),caps.boost());}",
                    "public boolean externalRoll(net.minecraft.world.level.Level level,dev.ryanhcode.sable.sublevel.ServerSubLevel body){return SpaceServer.rollControl(level,body);}",
                    "public void pilotReleased(net.minecraft.server.level.ServerPlayer player){SpaceServer.releaseHelm(player);}",
                    "public void releaseArrival(ServerLevel level){SpaceApproach.release(level);}",
                    "public void advanceSurroundings(ServerLevel level,dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container){SpaceServer.step(level,container);}",
                    "public dev.starfold.sky.Space.Frame departureFrame(ServerLevel level,dev.starfold.sky.Space.Motion motion,org.joml.Quaterniond orientation,dev.starfold.chart.GroundEphemeris.Model model,int planet,long tick){return SpaceServer.entering(level,motion,orientation,model,planet,tick);}",
                    "public void hullDeparted(ServerLevel level,int planet,String id){SpaceServer.departed(level,planet,id);}",
                    "public dev.starfold.sky.Space.Frame surroundingsFrame(ServerLevel level){return SpaceServer.frame(level);}",
                    "public Point carriedSpin(ServerLevel level,java.util.UUID hull){return SpaceServer.carriedSpin(level,hull);}",
                    "public boolean landingLoaded(ServerLevel level,Point anchor){return SpaceApproach.full(level,anchor);}",
                    "public boolean landingReady(dev.starfold.chart.GroundEphemeris.Body body,double height,boolean full){return SpaceApproach.ready(body,height,full);}",
                    "public net.minecraft.world.level.ChunkPos landingColumn(dev.starfold.geometry.BentStripGeometry.Point anchor){return SpaceApproach.column(anchor);}",
                    "public void hullEnteredChart(ServerLevel level,String id){SpaceServer.enteredChart(level,id);}",
                    "public void hullArrived(ServerLevel level,dev.ryanhcode.sable.sublevel.ServerSubLevel body){SpaceApproach.arrived(level,body);}",
                    "public SpacePackets.Frame carriedFrame(net.minecraft.server.level.ServerPlayer player){return SpaceServer.packet(player);}"
                }) assertTrue(source.contains(text), text);
        assertTrue(
                source.indexOf("ShipHost.install(") < source.indexOf("SableFrames.initialize()"));
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
                source.indexOf("ShipClientHost.install(")
                        < source.indexOf("DevProbe.initialize()"));
    }
}
