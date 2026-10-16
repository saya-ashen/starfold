package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.*;
import dev.starfold.space.SpaceNavigation;
import dev.starfold.universe.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

public class MoonSystemTest {
    @org.junit.jupiter.api.BeforeAll
    public static void boot() {
        dev.starfold.worldgen.PlanetEcologyTest.boot();
    }

    public static JsonObject fixture() throws Exception {
        return JsonParser.parseString(
                        Files.readString(Path.of("src/test/resources/celestial/C4-moons.json")))
                .getAsJsonObject();
    }

    public static SystemDefinition.Made made() throws Exception {
        return AuthoredSystemTest.read(fixture()).materialize(42);
    }

    public static GroundEphemeris.Model model() throws Exception {
        return made().sky().model();
    }

    static JsonObject moon(JsonObject d) {
        return AuthoredSystemTest.planet(d, 0, 0).getAsJsonArray("moons").get(0).getAsJsonObject();
    }

    @Test
    void independentFramePinsIncludeParentVelocityAndIndependentSpin() throws Exception {
        var model = model();
        for (var row : Files.readAllLines(Path.of("src/test/resources/celestial/C4-moons.tsv"))) {
            if (row.startsWith("#")) continue;
            var v = Arrays.stream(row.split("\\t")).mapToDouble(Double::parseDouble).toArray();
            var f = model.at((int) v[0], v[1]);
            var q = f.rotation();
            var actual =
                    new double[] {
                        f.center().x(),
                        f.center().y(),
                        f.center().z(),
                        f.velocity().x(),
                        f.velocity().y(),
                        f.velocity().z(),
                        q.x,
                        q.y,
                        q.z,
                        q.w,
                        f.angular().x(),
                        f.angular().y(),
                        f.angular().z()
                    };
            assertArrayEquals(Arrays.copyOfRange(v, 2, v.length), actual, 1e-8, row);
        }
        assertEquals(model.index(0), model.orbit(2).parent());
        for (double t : new double[] {100, 1234.5, 6000, 24000}) {
            assertEquals(5000, model.at(2, t).center().sub(model.at(0, t).center()).length(), 1e-8);
            var derivative =
                    model.at(2, t + .1).center().sub(model.at(2, t - .1).center()).scale(5);
            assertEquals(0, derivative.sub(model.at(2, t).velocity()).length(), 1e-6);
        }
    }

    @Test
    void moonsAppendWithoutMovingAnyPlanetOrChangingSeeds() throws Exception {
        var d = fixture();
        var with = AuthoredSystemTest.read(d).materialize(42);
        AuthoredSystemTest.planet(d, 0, 0).remove("moons");
        var without = AuthoredSystemTest.read(d).materialize(42);
        for (int slot = 0; slot < 2; slot++) {
            assertEquals(without.planets().get(slot).seed(), with.planets().get(slot).seed());
            assertEquals(without.sky().model().body(slot), with.sky().model().body(slot));
            assertEquals(without.sky().names().get(slot), with.sky().names().get(slot));
        }
        assertEquals(SystemDefinition.derivedSeed(42, 2), with.planets().get(2).seed());
        assertEquals(List.of(3), with.sky().groups());
        assertEquals(List.of("Home", "Outer", "Moon"), with.sky().names());
        d = fixture();
        d.getAsJsonObject("home").addProperty("planet", 1);
        var reordered = AuthoredSystemTest.read(d).materialize(42);
        assertEquals(List.of("Outer", "Home", "Moon"), reordered.sky().names());
        assertEquals(reordered.sky().model().index(1), reordered.sky().model().orbit(2).parent());
        assertEquals(SystemDefinition.derivedSeed(42, 2), reordered.planets().get(2).seed());
    }

    @Test
    void moonOrderFollowsTheFileRatherThanHomeOrder() throws Exception {
        var d = fixture();
        var other = moon(d).deepCopy();
        other.addProperty("name", "Second");
        var moons = new JsonArray();
        moons.add(other);
        AuthoredSystemTest.planet(d, 0, 1).add("moons", moons);
        d.getAsJsonObject("home").addProperty("planet", 1);
        var made = AuthoredSystemTest.read(d).materialize(42);
        assertEquals(List.of("Outer", "Home", "Moon", "Second"), made.sky().names());
        assertEquals(SystemDefinition.derivedSeed(42, 2), made.planets().get(2).seed());
        assertEquals(SystemDefinition.derivedSeed(42, 3), made.planets().get(3).seed());
        assertEquals(made.sky().model().index(1), made.sky().model().orbit(2).parent());
        assertEquals(made.sky().model().index(0), made.sky().model().orbit(3).parent());
    }

    @Test
    void moonsOfOtherSunsKeepWrittenSeedOrderWhenHomeSunMoves() throws Exception {
        var d = fixture();
        var second = AuthoredSystemTest.sun(d, 0).deepCopy();
        second.add("position", JsonParser.parseString("[100000,0,0]"));
        AuthoredSystemTest.planet(d, 0, 1).getAsJsonObject("orbit").addProperty("radius", 50000);
        second.getAsJsonArray("planets").remove(1);
        second.getAsJsonArray("planets").get(0).getAsJsonObject().addProperty("name", "OtherHome");
        second.getAsJsonArray("planets")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("moons")
                .get(0)
                .getAsJsonObject()
                .addProperty("name", "OtherMoon");
        d.getAsJsonArray("suns").add(second);
        d.getAsJsonObject("home").addProperty("sun", 1);
        var made = AuthoredSystemTest.read(d).materialize(42);
        assertEquals(
                List.of("OtherHome", "Home", "Outer", "Moon", "OtherMoon"), made.sky().names());
        assertEquals(List.of(2, 3), made.sky().groups());
        assertEquals(SystemDefinition.derivedSeed(42, 2), made.planets().get(0).seed());
        assertEquals(SystemDefinition.derivedSeed(42, 3), made.planets().get(3).seed());
        assertEquals(SystemDefinition.derivedSeed(42, 4), made.planets().get(4).seed());
        assertEquals(made.sky().model().index(1), made.sky().model().orbit(3).parent());
        assertEquals(made.sky().model().index(0), made.sky().model().orbit(4).parent());
        var universe = new Universe(made.sky().model().bodies(), made.planets());
        var saved = universe.save(new net.minecraft.nbt.CompoundTag(), PlanetTypeTest.lookup());
        assertEquals(universe.catalog(), Universe.load(saved, PlanetTypeTest.lookup()).catalog());
    }

    @Test
    void documentationMoonExampleDecodesAndGeneratedSystemsHaveOnlyFixedSunParents()
            throws Exception {
        var text = Files.readString(Path.of("docs/DATAPACKS.md"));
        var section = text.substring(text.indexOf("### Moons"), text.indexOf("## Example 2:"));
        var block =
                java.util.regex.Pattern.compile(
                                "```json\\s*\\n(.*?)\\n```", java.util.regex.Pattern.DOTALL)
                        .matcher(section);
        assertTrue(block.find());
        var example =
                AuthoredSystemTest.read(JsonParser.parseString(block.group(1)).getAsJsonObject());
        assertEquals(2, example.materialize(7).planets().size());
        var d = fixture();
        d.addProperty("beyond", "starfold:default");
        assertTrue(AuthoredSystemTest.failure(d).contains("beyond rings need"));
        d = fixture();
        var tiny = AuthoredSystemTest.planet(d, 0, 0);
        tiny.getAsJsonObject("orbit").addProperty("radius", 3000);
        tiny.remove("moons");
        d.addProperty("beyond", "starfold:default");
        assertTrue(AuthoredSystemTest.failure(d).contains("beyond rings need"));
        var home = model();
        var layout =
                UniverseLayout.CODEC
                        .parse(
                                PlanetTypeTest.ops(),
                                JsonParser.parseString(
                                        Files.readString(
                                                Path.of(
                                                        "src/main/resources/data/starfold/starfold/universe/small.json"))))
                        .getOrThrow();
        var generated = Universe.generate(42, layout, home);
        for (var body : generated.catalog()) {
            if (body.motion() instanceof GroundEphemeris.Orbit orbit)
                assertTrue(generated.catalog().get(orbit.parent()).light());
        }
        for (int slot = 0; slot < home.count(); slot++)
            assertEquals(home.body(slot), generated.model(home).body(slot));
    }

    @Test
    void parentAndSiblingShellsHaveClearValidationAndExactBoundary() throws Exception {
        var d = fixture();
        double required =
                new PlanetShape(1024).radius()
                        + 512
                        + new PlanetShape(512).radius()
                        + 512
                        + SystemDefinition.MARGIN;
        moon(d).getAsJsonObject("orbit").addProperty("radius", required);
        AuthoredSystemTest.read(d).validate();
        moon(d).getAsJsonObject("orbit").addProperty("radius", Math.nextDown(required));
        assertTrue(AuthoredSystemTest.failure(d).contains("moon 0 orbit too close to its planet"));
        d = fixture();
        var second = moon(d).deepCopy();
        second.getAsJsonObject("orbit").addProperty("phase", Math.PI + .01);
        AuthoredSystemTest.planet(d, 0, 0).getAsJsonArray("moons").add(second);
        assertTrue(AuthoredSystemTest.failure(d).contains("moons 0 and 1 orbits too close"));
        second.getAsJsonObject("orbit").addProperty("phase", 0);
        AuthoredSystemTest.read(d).validate();
        second.getAsJsonObject("orbit").addProperty("period", 24001);
        assertTrue(AuthoredSystemTest.failure(d).contains("orbits too close"));
    }

    @Test
    void wholeFamilyControlsSunSiblingAndDiscoveryEnvelopes() throws Exception {
        var d = fixture();
        double family = 5000 + new PlanetShape(512).radius() + 512;
        AuthoredSystemTest.planet(d, 0, 0)
                .getAsJsonObject("orbit")
                .addProperty("radius", 512 + family + 128);
        assertTrue(AuthoredSystemTest.failure(d).contains("too close to its sun"));
        d = fixture();
        AuthoredSystemTest.planet(d, 0, 1)
                .getAsJsonObject("orbit")
                .addProperty(
                        "radius", 30000 + family + new PlanetShape(1024).radius() + 2048 + 256 - 1);
        assertTrue(AuthoredSystemTest.failure(d).contains("orbits too close"));
        d = fixture();
        AuthoredSystemTest.sun(d, 0).getAsJsonArray("planets").remove(1);
        var home = AuthoredSystemTest.read(d).materialize(42).sky().model();
        double reserve = 30000 + 5000 + new PlanetShape(2048).radius() + 512 + 128 + 1000;
        assertFalse(
                Universe.clearOfHome(home.origin().add(new Point(reserve - 1, 0, 0)), 1000, home));
        assertTrue(
                Universe.clearOfHome(home.origin().add(new Point(reserve + 1, 0, 0)), 1000, home));
        var without = d.deepCopy();
        AuthoredSystemTest.planet(without, 0, 0).remove("moons");
        var bare = AuthoredSystemTest.read(without).materialize(42).sky().model();
        assertTrue(
                Universe.clearOfHome(home.origin().add(new Point(reserve - 1, 0, 0)), 1000, bare));
    }

    @Test
    void noNestedMoonsUnknownTypesOrOversizedCountsAreSilentlyDropped() throws Exception {
        var d = fixture();
        moon(d).add("moons", new JsonArray());
        assertTrue(AuthoredSystemTest.failure(d).contains("cannot have moons"));
        d = fixture();
        moon(d).addProperty("type", "handwritten:missing");
        assertTrue(AuthoredSystemTest.failure(d).contains("handwritten:missing"));
        d = fixture();
        moon(d).addProperty("size", 513);
        assertTrue(AuthoredSystemTest.failure(d).contains("size"));
        assertTrue(AuthoredSystemTest.failure(invalidReach()).contains("511"));
        d = fixture();
        var moons = AuthoredSystemTest.planet(d, 0, 0).getAsJsonArray("moons");
        while (moons.size() < 1599) moons.add(moon(d).deepCopy());
        assertTrue(AuthoredSystemTest.failure(d).contains("1..1600"));
    }

    @Test
    void daylightAndClientShadowWiringUseTheMoonsParentWithoutChangingElevation() throws Exception {
        var source = dev.starfold.Sources.text("GroundDaylight");
        assertTrue(source.contains("returnsurfaceLight(level,pos,tick,false)"));
        assertTrue(
                source.contains(
                        "surfaceLight(level,pos,GroundClock.get(level).clock.tick(),true)"));
        assertTrue(source.contains("surfaceLight(level,pos,now+ahead,true)"));
        assertTrue(
                source.contains(
                        "GroundSolar.daylight(elevation)*parentShade(clock.model,planet,world,tick)"));
        assertTrue(
                dev.starfold.Sources.text("GroundClient")
                        .contains(
                                "GroundDaylight.parentShade(model,planet(),universeEye(),time())"));
    }

    static JsonObject invalidReach() throws Exception {
        var d = fixture();
        moon(d).addProperty("reach", 511);
        return d;
    }

    @Test
    void fullSurfaceMetadataAndMotionRoundTripInDefinitionGeneratorAndSave() throws Exception {
        var d = fixture();
        var definition = AuthoredSystemTest.read(d);
        var ops = AuthoredSystemTest.ops();
        var encoded = SystemDefinition.CODEC.encodeStart(ops, definition).getOrThrow();
        assertEquals(
                encoded,
                SystemDefinition.CODEC
                        .encodeStart(ops, SystemDefinition.CODEC.parse(ops, encoded).getOrThrow())
                        .getOrThrow());
        var system = AuthoredSystemTest.generator(d, AuthoredSystemTest.preset());
        system.materialize(42);
        assertEquals(3, system.count());
        assertEquals(512, system.planet(2).terrain().shape().size());
        assertEquals(Optional.of("Moon"), system.planet(2).identity().name());
        var bare =
                net.minecraft.resources.RegistryOps.create(
                        com.mojang.serialization.JsonOps.INSTANCE, PlanetTypeTest.lookup());
        var snapshot = SystemChunkGenerator.CODEC.codec().encodeStart(bare, system).getOrThrow();
        var back = SystemChunkGenerator.CODEC.codec().parse(bare, snapshot).getOrThrow();
        assertEquals(system.homeModel(), back.homeModel());
        assertEquals(system.planet(2).seed(), back.planet(2).seed());
        assertEquals(
                system.homeModel(),
                GroundEphemerisSave.model(GroundEphemerisSave.modelTag(system.homeModel())));
    }

    @Test
    void personalFlightLeavesAndReattachesTheMoonsOwnFrameAndReach() throws Exception {
        var made = made();
        PlanetShape.system(
                made.planets().stream().mapToInt(p -> p.terrain().shape().size()).toArray(),
                made.planets().stream().mapToInt(p -> p.generation().depth()).toArray());
        try {
            var model = made.sky().model();
            var pose =
                    new PlanetShape.Pose(
                            dev.starfold.geometry.CubeSurfaceTopology.Face.TOP,
                            new Point(200, 1200, 300));
            var state =
                    GroundDynamics.local(
                            1234,
                            2,
                            pose,
                            GroundEphemeris.ZERO,
                            new org.joml.Quaterniond(),
                            true,
                            GroundGuidance.Options.OFF);
            var terrain =
                    new GroundDynamics.Terrain() {
                        public boolean clear(PlanetShape.Pose p) {
                            return true;
                        }

                        public double clearance(PlanetShape.Pose p) {
                            return 10000;
                        }
                    };
            var input =
                    dev.starfold.geometry.PlanetMotion.Input.idle(
                            1, GroundDynamics.orientation(model, state, state.tick()));
            var departed = GroundDynamics.step(model, terrain, state, input, false);
            assertTrue(departed.space());
            assertEquals(2, departed.planet());
            var attached = GroundDynamics.attach(model, departed, 2);
            assertFalse(attached.space());
            assertEquals(2, attached.planet());
            assertEquals(
                    0,
                    GroundDynamics.position(model, attached, attached.tick())
                            .sub(departed.position())
                            .length(),
                    1e-7);
            assertEquals(
                    0,
                    GroundDynamics.velocity(model, attached, attached.tick())
                            .sub(departed.velocity())
                            .length(),
                    1e-7);
        } finally {
            PlanetSystem.reset();
        }
    }

    @Test
    void moonShadowNavigationAndCollisionBoundsUseEvaluatedFrames() throws Exception {
        var made = made();
        PlanetShape.system(
                made.planets().stream().mapToInt(p -> p.terrain().shape().size()).toArray(),
                made.planets().stream().mapToInt(p -> p.generation().depth()).toArray());
        try {
            var m = made.sky().model();
            int slot = 2;
            var shadow = m.at(slot, 15000).center();
            assertEquals(0, GroundDaylight.parentShade(m, slot, shadow, 15000));
            assertEquals(1, GroundDaylight.parentShade(m, slot, m.at(slot, 0).center(), 0));
            assertEquals(1, GroundDaylight.parentShade(m, 0, m.at(0, 0).center(), 0));
            assertEquals(2, GroundDynamics.select(m, m.at(2, 1234).center(), 0, 1234));
            assertTrue(SpaceNavigation.cycle(m, m.at(2, 1234).center(), 1234, 0).contains(2));
            for (double t : new double[] {0, 6000, 12000}) {
                var world = m.at(2, t).center().add(new Point(1000, 0, 0));
                assertTrue(m.least(2, world) <= world.sub(m.at(2, t).center()).length() + 1e-8);
                assertTrue(m.extent() > world.sub(m.origin()).length());
            }
            assertTrue(m.drift() >= 2 * m.at(2, 0).velocity().length());
        } finally {
            PlanetSystem.reset();
        }
    }
}
