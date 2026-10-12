package dev.starfold.probe;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class ClientPlumbingTest {
    @Test
    void productionClientsNoLongerNameRenderOrDevelopment() throws Exception {
        for (String name :
                new String[] {"HullLandingClient", "SableCarryClient", "WaypointClient"}) {
            var source = Sources.text(name);
            assertFalse(source.contains("dev.starfold.render"), name);
            assertFalse(source.contains("dev.starfold.dev"), name);
        }
        for (String pkg : new String[] {"probe", "network", "render"}) {
            try (var files = Files.walk(Path.of("src/main/java/dev/starfold/" + pkg))) {
                for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var source = Files.readString(file);
                    assertFalse(source.contains("dev.starfold.dev."), file.toString());
                    if (!pkg.equals("render")) {
                        for (String higher :
                                new String[] {
                                    "sky",
                                    "worldgen",
                                    "universe",
                                    "ground",
                                    "flight",
                                    "ship",
                                    "space",
                                    "waypoint",
                                    "render"
                                })
                            assertFalse(
                                    source.contains("dev.starfold." + higher + "."),
                                    file.toString());
                    }
                }
            }
        }
    }

    @Test
    void chartQueriesAreDelegatedWithoutCombiningOrReorderingThem() throws Exception {
        var wiring = Sources.text("StarfoldClient");
        for (String text :
                new String[] {
                    "Point storage(double x,double y,double z){return GroundClient.storage(x,y,z);}",
                    "PlanetAccess chartAccess(){return GroundClient.access();}",
                    "ObserverFrame chartFrame(){return GroundClient.frame();}",
                    "int planet(){return GroundClient.planet();}",
                    "ObserverFrame frame(){return GroundClient.frame();}",
                    "PlanetAccess access(){return GroundClient.access();}",
                    "PlanetShape shape(){return GroundClient.shape();}",
                    "GroundSpace.Pose pose(){return GroundClient.pose();}",
                    "boolean diagnosticHud(){return GroundClient.DEBUG_HUD;}"
                }) assertTrue(wiring.contains(text), text);
        assertTrue(
                wiring.indexOf("SpaceClientHost.install(")
                        < wiring.indexOf("WaypointClientHost.install("));
        assertTrue(
                wiring.indexOf("WaypointClientHost.install(")
                        < wiring.indexOf("DevProbe.initialize()"));
    }

    @Test
    void arrivalCensusRetainsResetTimingFlagsAndUnconditionalInstallation() throws Exception {
        var state = Sources.text("ArrivalClientState");
        assertTrue(
                state.contains(
                        "begin(){if(ArrivalProbe.ENABLED){remote=false;own=false;parentNanos=0;}"));
        assertTrue(state.contains("end(long start){census.accept(start);}"));
        var probe = Sources.text("ArrivalClientProbe");
        assertTrue(probe.contains("ArrivalClientState.census(ArrivalClientProbe::end)"));
        assertTrue(probe.contains("end(long start){if(!ArrivalProbe.ENABLED)return;"));
        assertTrue(probe.contains("ArrivalClientState.remote,ArrivalClientState.own"));
        assertTrue(probe.contains("ArrivalClientState.parentNanos"));
        var driver = Sources.text("DevProbe");
        assertTrue(
                driver.contains(
                        "initialize(){ArrivalClientProbe.initialize();String path=System.getProperty(\"starfold.probe\");if(path==null)return;"));
        assertTrue(
                Sources.text("GroundRenderer")
                        .contains(
                                "if(ArrivalProbe.ENABLED)ArrivalClientState.parentNanos+=System.nanoTime()-parentStart"));
        var frame = Sources.text("ArrivalFrameMixin");
        assertTrue(frame.contains("ArrivalClientState.begin();try{original.call("));
        assertTrue(frame.contains("finally{ArrivalClientState.end(start);}"));
    }

    @Test
    void boardingPacketAndProbeStillRegisterBeforeBoardingListeners() throws Exception {
        var wiring = Sources.text("StarfoldServer");
        assertTrue(
                wiring.contains(
                        "SpaceServer.initialize();StarfoldNetwork.playS2C(SableBoardingPacket.TYPE,SableBoardingPacket.CODEC);DevBoardingProbe.initialize();SableBoarding.initialize();Thrusters.initialize(modBus);"));
        var boarding = Sources.text("SableBoarding");
        assertFalse(boarding.contains("DevBoardingProbe"));
        assertFalse(boarding.contains("playS2C("));
        assertTrue(
                boarding.contains(
                        "initialize(){var bus=net.neoforged.neoforge.common.NeoForge.EVENT_BUS;bus.addListener("));
    }
}
