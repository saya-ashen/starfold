package dev.starfold;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.flight.FlightHost;
import dev.starfold.ground.GroundHost;
import dev.starfold.ship.ShipHost;
import dev.starfold.sky.SkyHost;
import dev.starfold.space.SpaceHost;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class StarfoldHostBindingsTest {
    @Test
    void adaptersPreserveSubsystemBoundariesAndArguments() throws Exception {
        var flight = FlightHost.get();
        var ground = GroundHost.get();
        var ship = ShipHost.get();
        var sky = SkyHost.get();
        var space = SpaceHost.get();
        try {
            StarfoldHostBindings.install();
            delegates(FlightHost.get(), "viewLost=render/GroundViewServer.remove",
                    "targetSync=waypoint/WaypointServer.send", "targetCleared=waypoint/WaypointSave.clear",
                    "failure=waypoint/WaypointServer.failure", "leave=PlanetPlayers.leave", "nearHull=ship/SableFrames.near");
            delegates(GroundHost.get(), "afterDeath=PlanetPlayers.afterDeath", "viewLost=render/GroundViewServer.remove",
                    "resited=flight/GroundFlightServer.site", "regionSync=space/SpaceServer.sync",
                    "controlled=flight/GroundFlightServer.controlled", "aloft=flight/GroundFlightServer.aloft",
                    "carrying=ship/SableFrames.carrying", "clearArrival=space/SpaceApproach.clear");
            assertEquals(java.util.List.of("PlanetPlayers.afterDeath", "GasPlayerExposureServer.completedDeath"),
                    calls(bytecode(GroundHost.get().getClass()), "afterDeath"));
            delegates(ShipHost.get(), "mapSync=waypoint/MapNavigationServer.sync", "retainLead=space/SpaceServer.retainLead",
                    "externalRoll=space/SpaceServer.rollControl", "gasCollected=waypoint/ShipGuide.skim",
                    "pilotReleased=space/SpaceServer.releaseHelm", "releaseArrival=space/SpaceApproach.release",
                    "advanceSurroundings=space/SpaceServer.step", "departureFrame=space/SpaceServer.entering",
                    "hullDeparted=space/SpaceServer.departed", "surroundingsFrame=space/SpaceServer.frame",
                    "carriedSpin=space/SpaceServer.carriedSpin", "landingLoaded=space/SpaceApproach.full",
                    "landingReady=space/SpaceApproach.ready", "landingColumn=space/SpaceApproach.column",
                    "hullEnteredChart=space/SpaceServer.enteredChart", "hullArrived=space/SpaceApproach.arrived",
                    "carriedFrame=space/SpaceServer.packet");
            delegates(SkyHost.get(), "underWay=flight/GroundFlightServer.underWay",
                    "sunlight=sky/Space$Frame.universe", "sunlight=space/SpaceView.sunlight");
            delegates(SpaceHost.get(), "targetCleared=waypoint/MapNavigationServer.markerSelected",
                    "targetSync=waypoint/WaypointServer.send");
            var error = new IllegalArgumentException("host failure");
            assertEquals(dev.starfold.waypoint.WaypointServer.failure(error), FlightHost.get().failure(error));
            assertEquals(dev.starfold.flight.GroundFlightServer.underWay(), SkyHost.get().underWay());
            assertEquals(new net.minecraft.world.level.ChunkPos(-1, 2),
                    ShipHost.get().landingColumn(new dev.starfold.geometry.BentStripGeometry.Point(-.1, 0, 32)));
            var body = dev.starfold.chart.GroundSolar.defaults().body(0);
            for (boolean full : new boolean[] {false, true})
                assertEquals(dev.starfold.space.SpaceApproach.ready(body, 0, full), ShipHost.get().landingReady(body, 0, full));
        } finally {
            FlightHost.install(flight);
            GroundHost.install(ground);
            ShipHost.install(ship);
            SkyHost.install(sky);
            SpaceHost.install(space);
        }
    }

    @Test
    void bindingsPrecedeSubsystemInitializationAndTickOrderIsPreserved() throws Exception {
        var node = bytecode(StarfoldServer.class);
        var init = calls(node, "initialize");
        assertEquals(java.util.List.of("StarfoldHostBindings.install", "StarfoldServer.initializeServerSystems",
                "PlanetFire.initialize", "StarfoldServer.registerPlanetLifecycle", "StarfoldServer.initializePlayerSystems",
                "StarfoldServer.registerTicketsAndCleanup", "StarfoldServer.registerSleeping",
                "StarfoldServer.registerClockTicks", "StarfoldServer.registerCommands"), init);
        var ticks = node.methods.stream().filter(m -> m.name.startsWith("lambda$registerClockTicks"))
                .map(m -> calls(node, m.name)).filter(c -> c.contains("GroundFlightServer.tick")).findFirst().orElseThrow();
        int previous = -1;
        for (String call : java.util.List.of("GroundFlightServer.tick", "ImageStream.tick",
                "ChunkTickReadiness.postProcessHeld", "GroundViewServer.tick", "AnomalyZone.tick")) {
            int index = ticks.indexOf(call);
            assertTrue(index > previous, call);
            previous = index;
        }
    }

    private static void delegates(Object host, String... delegates) throws Exception {
        var node = bytecode(host.getClass());
        for (String delegate : delegates) {
            var parts = delegate.split("=");
            var method = node.methods.stream().filter(m -> m.name.equals(parts[0])).findFirst().orElseThrow();
            boolean found = false;
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call) {
                    assertFalse(call.owner.startsWith("net/minecraft/client/"), delegate);
                    found |= (call.owner + "." + call.name).equals("dev/starfold/" + parts[1]);
                }
            assertTrue(found, delegate);
        }
    }

    private static ClassNode bytecode(Class<?> type) throws Exception {
        var node = new ClassNode();
        try (var input = TestArchiveResources.open("/" + type.getName().replace('.', '/') + ".class")) {
            new ClassReader(input).accept(node, 0);
        }
        return node;
    }

    private static java.util.List<String> calls(ClassNode node, String method) {
        var result = new java.util.ArrayList<String>();
        var instructions = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow().instructions;
        for (var instruction : instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.startsWith("dev/starfold/"))
                result.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
        return result;
    }
}
