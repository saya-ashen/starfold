package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.TestArchiveResources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.ground.GroundTickets;
import dev.starfold.ground.GroundTopology;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetShape;
import dev.starfold.planet.PlanetStorage;

import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

class SpaceApproachTest {
    private static List<String> calls(String name, String method) throws Exception {
        var node = new ClassNode();
        try (var in = TestArchiveResources.open("/dev/starfold/" + name + ".class")) {
            new ClassReader(in).accept(node, 0);
        }
        var out = new ArrayList<String>();
        for (var m : node.methods)
            if (m.name.equals(method))
                for (var i : m.instructions)
                    if (i instanceof MethodInsnNode c) out.add(c.owner + "." + c.name);
        return out;
    }

    @Test
    void predictedColumnUsesTheCurrentRotatingPlanetAndEveryFace() {
        var model = SpaceTest.tilted();
        for (int planet = 0; planet < 2; planet++)
            for (var shape : SpaceTest.SHAPES)
                for (var face : Face.values())
                    for (double tick : new double[] {0, 1234, 9876}) {
                        var pose =
                                new GroundTopology.Pose(
                                        face,
                                        new Point(shape.size() * .27, 1500, shape.size() * .73));
                        var motion = SpaceTest.chart(pose.point());
                        var universe = Space.out(model, planet, shape, pose, motion, tick);
                        var predicted = Space.in(model, planet, shape, universe, tick);
                        var anchor = PlanetAccess.SHARED.bodyAnchor(planet, predicted.pose());
                        var origin = PlanetStorage.origin(planet);
                        assertEquals(
                                new ChunkPos(
                                        (int)
                                                        Math.floor(
                                                                origin.x()
                                                                        + face.ordinal() * 4096
                                                                        + pose.point().x())
                                                >> 4,
                                        (int) Math.floor(origin.z() + pose.point().z()) >> 4),
                                SpaceApproach.column(anchor));
                    }
    }

    @Test
    void forecastUsesGovernorsAndFutureSpinWithoutChangingReadiness() {
        var model = SpaceTest.tilted();
        var shape = PlanetShape.STANDARD;
        double tick = 1234;
        for (int planet = 0; planet < 2; planet++)
            for (var face : Face.values()) {
                var pose =
                        new GroundTopology.Pose(
                                face, new Point(shape.size() * .3, 6000, shape.size() * .6));
                var start =
                        Space.out(model, planet, shape, pose, SpaceTest.chart(pose.point()), tick);
                var up = Space.up(model, planet, start.position(), tick);
                var toward = new Point(-up.x, -up.y, -up.z);
                var motion =
                        new Space.Motion(
                                start.position(),
                                start.orientation(),
                                toward.scale(40).add(model.at(planet, tick).velocity()),
                                start.spin());
                var predicted = SpaceApproach.predict(model, planet, shape, motion, tick);
                var position = motion.position().sub(model.at(planet, tick).center());
                var velocity = toward.scale(40);
                int steps = 0;
                while (position.length() - shape.radius() > Space.IN) {
                    double height = position.length() - shape.radius();
                    var radial = position.scale(-1 / position.length());
                    velocity =
                            radial.scale(
                                    Math.min(
                                            velocity.dot(radial),
                                            (150 + Math.max(0, height - Space.IN) / 6) / 20));
                    position = position.add(velocity);
                    steps++;
                    assertTrue(steps < 600);
                }
                var expected =
                        Space.in(
                                model,
                                planet,
                                shape,
                                new Space.Motion(
                                        position.add(model.at(planet, tick + steps).center()),
                                        motion.orientation(),
                                        velocity.add(model.at(planet, tick + steps).velocity()),
                                        motion.spin()),
                                tick + steps);
                assertEquals(expected.pose().face(), predicted.pose().face());
                assertTrue(expected.pose().point().sub(predicted.pose().point()).length() < 1e-7);
                assertTrue(
                        predicted
                                        .pose()
                                        .point()
                                        .sub(
                                                Space.in(model, planet, shape, motion, tick)
                                                        .pose()
                                                        .point())
                                        .length()
                                > 1000);
            }
        assertTrue(SpaceApproach.PREPARE > SpaceApproach.APPROACH);
    }

    @Test
    void forecastIsBoundedForStationaryAndRetreatingShips() {
        var model = SpaceTest.tilted();
        var shape = PlanetShape.STANDARD;
        double tick = 456;
        var pose = new GroundTopology.Pose(Face.values()[0], new Point(500, 7000, 700));
        var start = Space.out(model, 0, shape, pose, SpaceTest.chart(pose.point()), tick);
        var up = Space.up(model, 0, start.position(), tick);
        for (double speed : new double[] {0, 10}) {
            var motion =
                    new Space.Motion(
                            start.position(),
                            start.orientation(),
                            new Point(up.x, up.y, up.z)
                                    .scale(speed)
                                    .add(model.at(0, tick).velocity()),
                            start.spin());
            assertEquals(
                    Space.in(model, 0, shape, motion, tick).pose(),
                    SpaceApproach.predict(model, 0, shape, motion, tick).pose());
        }
    }

    @Test
    void patchHysteresisRetainsOneChunkWobblesButFollowsRealMotion() {
        var old = new ChunkPos(20, -10);
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++)
                assertSame(old, SpaceApproach.stable(old, new ChunkPos(old.x + x, old.z + z)));
        var next = new ChunkPos(22, -10);
        assertSame(next, SpaceApproach.stable(old, next));
        assertSame(next, SpaceApproach.stable(null, next));
    }

    @Test
    void regionReleaseIsBoundedDrainsAndReticketingWins() {
        Set<Long> held = new LinkedHashSet<>();
        for (long i = 0; i < 625; i++) held.add(i);
        int ticks = 0;
        while (!held.isEmpty()) {
            int before = held.size();
            held = GroundTickets.retiring(held, Set.of());
            assertEquals(Math.min(8, before), before - held.size());
            assertTrue(++ticks < 80);
        }
        assertEquals(79, ticks);
        held = new LinkedHashSet<>();
        for (long i = 0; i < 100; i++) held.add(i);
        var desired = Set.of(0L, 1L, 200L);
        for (int i = 0; i < 20; i++) {
            held = GroundTickets.retiring(held, desired);
            assertTrue(held.containsAll(desired));
        }
        assertEquals(desired, held);
    }

    @Test
    void columnsFloorRatherThanTruncateAtNegativeAndChunkBoundaries() {
        for (double x : new double[] {-16.001, -16, -.001, 0, 15.999, 16})
            for (double z : new double[] {-16.001, -16, -.001, 0, 15.999, 16})
                assertEquals(
                        new ChunkPos((int) Math.floor(x / 16), (int) Math.floor(z / 16)),
                        SpaceApproach.column(new Point(x, 2000, z)));
    }

    @Test
    void readinessIsStrictAndTheEmergencyFloorNeverWaits() {
        for (boolean full : new boolean[] {false, true}) {
            assertFalse(SpaceApproach.ready(Space.IN, full));
            assertFalse(SpaceApproach.ready(Space.IN + 1, full));
            assertTrue(SpaceApproach.ready(Space.IN - 512 - .001, full));
        }
        assertFalse(SpaceApproach.ready(Space.IN - 512, false));
        assertTrue(SpaceApproach.ready(Space.IN - 512, true));
        assertFalse(SpaceApproach.ready(Space.IN - .001, false));
        assertTrue(SpaceApproach.ready(Space.IN - .001, true));
    }

    @Test
    void preparationIsAsyncFullAndCoversHullAndCanonicalImages() throws Exception {
        var s = Sources.text("SpaceApproach");
        for (String text :
                List.of(
                        "APPROACH=4096",
                        "RADIUS=3,INTERVAL=10",
                        "tick-old.tick()<INTERVAL",
                        "Space.in(",
                        "access.bodyAnchor(planet,arrival.pose())",
                        "lead.boundingBox()",
                        "i<8",
                        "minX-1",
                        "maxX+1",
                        "ChunkViews.columns(access,x,z).owners()",
                        "addRegionTicket(TYPE,new ChunkPos(key),0,key)",
                        "removeRegionTicket(TYPE,new ChunkPos(key),0,key)"))
            assertTrue(s.contains(text), text);
        assertTrue(s.contains("for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)"));
        assertTrue(
                calls("SpaceApproach", "full").stream().anyMatch(c -> c.endsWith(".getChunkNow")));
        assertFalse(s.contains("getChunk("));
        var arrive = calls("SableFrames", "arrive");
        int ready = arrive.indexOf("dev/starfold/space/SpaceApproach.ready"),
                move = arrive.indexOf("dev/starfold/ship/SableFrames.move");
        assertTrue(ready >= 0 && ready < move);
        assertTrue(arrive.indexOf("dev/starfold/space/SpaceApproach.arrived") > move);
    }

    @Test
    void ticketsAreReleasedOnEveryExitAndShutdown() throws Exception {
        var s = Sources.text("SpaceApproach");
        assertTrue(s.contains("old.body()!=lead||old.body().isRemoved()"));
        assertTrue(s.indexOf("old.body().isRemoved()") < s.indexOf("tick-old.tick()<INTERVAL"));
        assertTrue(s.contains(">=PREPARE){release(level);return;}"));
        assertTrue(s.contains("if(value!=null&&value.body()==body)release(level)"));
        assertTrue(s.contains("held.remove(level)"));
        assertTrue(s.contains("List.copyOf(held.keySet()))release(level)"));
        assertTrue(
                Sources.text("SpaceServer")
                        .contains(
                                "value.inboundPlanet=-1;value.arrivalState=ArrivalState.empty();value.setDirty();SpaceApproach.update(level,container,null);return;}"));
        assertTrue(Sources.text("SpaceServer").contains("LevelEvent.Unload"));
        assertTrue(
                Sources.text("SableFrames")
                        .contains("if(container==null){SpaceApproach.release(level);return;}"));
        assertTrue(
                calls("GroundTickets", "clear").contains("dev/starfold/space/SpaceApproach.clear"));
    }

    @Test
    void passiveArrivalMeshSurvivesAndUsesTheChartPlacement() throws Exception {
        var s = Sources.text("PlanetRemoteRenderer");
        assertTrue(s.contains("SpaceClient.retainedSession()"));
        assertTrue(s.contains("body.fallback==null&&!GroundRenderer.selectionReady()"));
        assertTrue(
                s.contains("e.getKey()==GroundClient.planet()&&!GroundRenderer.selectionReady()"));
        assertTrue(
                calls("PlanetRemoteRenderer", "drawBody")
                        .contains("dev/starfold/render/GroundClient.place"));
        var client = Sources.text("SpaceClient");
        assertTrue(
                client.contains(
                        "if(viewLevel!=null&&viewLevel!=mc.level){PlanetRemoteRenderer.clear();retainedSession=0;}"));
        assertFalse(
                client.contains("view.session()!=packet.session()))PlanetRemoteRenderer.clear()"));
    }

    @Test
    void departureTransfersBuffersAndDoesNotWaitForANewSummary() throws Exception {
        assertTrue(
                Sources.text("GroundFlightClient")
                        .contains("PlanetRemoteRenderer.departing();reset();"));
        var s = Sources.text("PlanetRemoteRenderer");
        assertTrue(s.contains("GroundExterior.take(shape)"));
        assertTrue(
                s.contains(
                        "int planet=GroundViewClient.cachedPlanet();if(profile==null||planet<0)return"));
        assertTrue(s.contains("System.arraycopy(meshes,0,body.meshes,0,meshes.length)"));
        assertTrue(s.contains("body.resident!=null?body.resident:body.fallback"));
        assertFalse(s.contains("body.view.session()!=session)continue"));
        assertTrue(
                Sources.text("GroundExterior")
                        .contains("var out=patches.clone();Arrays.fill(patches,null);return out;"));
    }

    @Test
    void approachDataIsStagedWithoutAChartOrFlightSession() throws Exception {
        assertTrue(
                Sources.text("PlanetRemoteServer").contains("SpaceApproach.destination(player)"));
        assertTrue(
                Sources.text("GroundViewServer")
                        .contains("if(destination>=0)ticked.add(destination)"));
        assertTrue(
                Sources.text("GroundViewClient")
                        .contains("PlanetRemoteRenderer.seedOwn(boundPlanet,id)"));
        assertTrue(Sources.text("GroundViewClient").contains("(previewed||!coverageReady())"));
        assertTrue(
                calls("PlanetRemoteRenderer", "seedOwn")
                        .contains("dev/starfold/render/GroundViewClient.preview"));
        var s = Sources.text("SpaceApproach");
        for (String forbidden : List.of("site(", "capture(", "setPos(", "moveTo(", "setup("))
            assertFalse(s.contains(forbidden));
    }
}
