package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;

import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.GroundPackets;
import dev.starfold.sky.*;
import dev.starfold.universe.*;

import net.minecraft.nbt.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class GasCatalogTest {
    static final Path FIXTURE = Path.of("src/test/resources/celestial/D1-legacy");

    @BeforeAll
    static void boot() throws Exception {
        MoonSystemTest.boot();
    }

    static JsonObject gas() {
        return JsonParser.parseString(
                        """
                {"radius":4096,"orbit":{"radius":45000,"phase":1.5707963267948966},
                 "spin":{"period":96000,"tilt":12},
                 "gas":{"seed":20261013,"name":"Amber","low_color":9987389,"high_color":15389090}}
                """)
                .getAsJsonObject();
    }

    static JsonObject withGas(JsonObject d) {
        var list = new JsonArray();
        list.add(gas());
        d.getAsJsonArray("suns").get(0).getAsJsonObject().add("gas_giants", list);
        return d;
    }

    static SystemDefinition.Made made() throws Exception {
        return AuthoredSystemTest.read(withGas(MoonSystemTest.fixture())).materialize(42);
    }

    static CompoundTag fixture(String name) throws Exception {
        return NbtIo.readCompressed(FIXTURE.resolve(name), NbtAccounter.unlimitedHeap())
                .getCompound("data");
    }

    @Test
    void gasAppendsAfterEveryOldBodyWithoutChangingSurfaceSeedsIdsOrNames() throws Exception {
        var old = AuthoredSystemTest.read(MoonSystemTest.fixture()).materialize(42);
        var added = made();
        var model = added.sky().model();
        assertEquals(old.planets().size(), added.planets().size());
        assertEquals(old.sky().names(), added.sky().names());
        assertEquals(old.sky().groups(), added.sky().groups());
        assertEquals(
                old.sky().model().bodies(),
                model.bodies().subList(0, old.sky().model().bodies().size()));
        for (int slot = 0; slot < old.planets().size(); slot++) {
            var a = old.planets().get(slot);
            var b = added.planets().get(slot);
            assertEquals(a.seed(), b.seed());
            assertEquals(a.generation(), b.generation());
            assertEquals(a.identity(), b.identity());
        }
        var body = model.bodies().getLast();
        assertEquals(GroundEphemeris.Appearance.GAS, body.appearance());
        assertEquals(new BodyId(0, old.sky().model().bodies().size()), body.id());
        assertEquals(0, ((GroundEphemeris.Orbit) body.motion()).parent());
        assertEquals(old.sky().model().count(), model.count());
        assertEquals(old.sky().model().everyStar(), model.everyStar());
        assertEquals(96000, ((GroundEphemeris.Orbit) body.motion()).spin().period());
        var allocator = new BodyIdAllocator();
        allocator.reserve(model.bodies());
        assertEquals(new BodyId(0, model.bodies().size()), allocator.allocate(0));
    }

    @Test
    void authoredCodecAndSavedSkyRetainResolvedGasWithoutTypeRegistry() throws Exception {
        var d = AuthoredSystemTest.read(withGas(MoonSystemTest.fixture()));
        var ops = AuthoredSystemTest.ops();
        var json = SystemDefinition.CODEC.encodeStart(ops, d).getOrThrow();
        var restored = SystemDefinition.CODEC.parse(ops, json).getOrThrow();
        assertEquals(json, SystemDefinition.CODEC.encodeStart(ops, restored).getOrThrow());
        assertEquals(d.suns().getFirst().gasGiants(), restored.suns().getFirst().gasGiants());
        assertEquals(d.materialize(42).sky(), restored.materialize(42).sky());
        var sky = d.materialize(42).sky();
        var saved = SystemDefinition.Sky.CODEC.encodeStart(JsonOps.INSTANCE, sky).getOrThrow();
        assertEquals(sky, SystemDefinition.Sky.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow());
        var old = AuthoredSystemTest.read(MoonSystemTest.fixture()).materialize(42).sky();
        var oldSave = SystemDefinition.Sky.CODEC.encodeStart(JsonOps.INSTANCE, old).getOrThrow();
        assertEquals(old, SystemDefinition.Sky.CODEC.parse(JsonOps.INSTANCE, oldSave).getOrThrow());
        assertTrue(old.model().bodies().stream().allMatch(b -> b.gas().isEmpty()));
        oldSave.getAsJsonObject().getAsJsonObject("model").addProperty("format", 8);
        assertTrue(SystemDefinition.Sky.CODEC.parse(JsonOps.INSTANCE, oldSave).isError());
    }

    @Test
    void gasMoonIsStoredAsASurfaceOrbitingItsGasParentAndUsesTheParentGm() throws Exception {
        var d = withGas(MoonSystemTest.fixture());
        var giant = d.getAsJsonArray("suns").get(0).getAsJsonObject()
                .getAsJsonArray("gas_giants").get(0).getAsJsonObject();
        giant.getAsJsonObject("orbit").addProperty("radius", 120000);
        giant.add("moons", JsonParser.parseString("""
                [{"type":"starfold:desert","size":512,"name":"Amber Moon",
                  "orbit":{"radius":10000,"phase":0}}]
                """));
        var definition = AuthoredSystemTest.read(d);
        var gas = definition.suns().getFirst().gasGiants().getFirst();
        var made = definition.materialize(42);
        var model = made.sky().model();
        int moon = made.planets().size() - 1;
        int moonBody = model.index(moon);
        int gasBody = moonBody - 1;
        assertEquals(gasBody, ((GroundEphemeris.Orbit) model.bodies().get(moonBody).motion()).parent());
        assertEquals(gas.gm(), 80 * gas.radius() * gas.radius());
        assertEquals(gas.moons().getFirst().orbit().period(gas.gm()), model.orbit(moon).period());
        assertEquals(GroundEphemeris.Appearance.GAS, model.bodies().get(gasBody).appearance());
        PlanetShape.system(
                made.planets().stream().mapToInt(p -> p.terrain().shape().size()).toArray(),
                made.planets().stream().mapToInt(p -> p.generation().depth()).toArray());
        try {
            var parentCenter = model.frame(gasBody, 0).center();
            var towardSun = model.star(parentCenter).position().sub(parentCenter);
            var eclipsePoint =
                    parentCenter.sub(
                            towardSun.scale(2 * model.bodies().get(gasBody).radius() / towardSun.length()));
            assertEquals(0, GroundDaylight.parentShade(model, moon, eclipsePoint, 0));
        } finally {
            PlanetShape.reset();
        }
    }

    @Test
    void gasValidationRejectsInvalidListsPoliciesAndOverlappingOrbitalShells() throws Exception {
        for (String field : List.of("radius", "gas", "orbit", "spin")) {
            var d = withGas(MoonSystemTest.fixture());
            d.getAsJsonArray("suns")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonArray("gas_giants")
                    .get(0)
                    .getAsJsonObject()
                    .remove(field);
            assertThrows(
                    RuntimeException.class, () -> AuthoredSystemTest.read(d).validate(), field);
        }
        for (double radius : new double[] {500, 40000}) {
            var d = withGas(MoonSystemTest.fixture());
            var g =
                    d.getAsJsonArray("suns")
                            .get(0)
                            .getAsJsonObject()
                            .getAsJsonArray("gas_giants")
                            .get(0)
                            .getAsJsonObject();
            g.addProperty("radius", radius);
            assertThrows(RuntimeException.class, () -> AuthoredSystemTest.read(d).validate());
        }
        // Near the sun, through a planet/moon family, and beyond the flight-save envelope.
        for (double orbit : new double[] {1000, 25000}) {
            var d = withGas(MoonSystemTest.fixture());
            d.getAsJsonArray("suns")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonArray("gas_giants")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("orbit")
                    .addProperty("radius", orbit);
            assertThrows(
                    IllegalArgumentException.class, () -> AuthoredSystemTest.read(d).validate());
        }
        var outside = withGas(MoonSystemTest.fixture());
        var giant = outside.getAsJsonArray("suns").get(0).getAsJsonObject();
        giant.add("position", JsonParser.parseString("[1000000000,0,0]"));
        assertThrows(IllegalArgumentException.class, () -> AuthoredSystemTest.read(outside).validate());
        var duplicate = withGas(MoonSystemTest.fixture());
        duplicate
                .getAsJsonArray("suns")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("gas_giants")
                .add(gas());
        assertThrows(
                IllegalArgumentException.class,
                () -> AuthoredSystemTest.read(duplicate).validate());
        var tooMany = withGas(MoonSystemTest.fixture());
        var list =
                tooMany.getAsJsonArray("suns")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("gas_giants");
        for (int i = 0; i < 8; i++) list.add(gas());
        assertThrows(RuntimeException.class, () -> AuthoredSystemTest.read(tooMany));
        var unknown = withGas(MoonSystemTest.fixture());
        unknown.getAsJsonArray("suns")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("gas_giants")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("gas")
                .addProperty("version", 2);
        assertThrows(RuntimeException.class, () -> AuthoredSystemTest.read(unknown));
    }

    @Test
    void catalogWireClockAndActualSkyPacketRoundTripGasAndRejectVersionSmuggling()
            throws Exception {
        var model = made().sky().model();
        var disk = new GroundEphemerisSave(model, 73).save(new CompoundTag(), null);
        assertEquals(9, disk.getInt("format"));
        assertEquals(9, disk.getCompound("motion").getInt("format"));
        assertEquals(model, GroundEphemerisSave.load(disk, null).model);
        var wire = GroundEphemerisSave.modelTag(model);
        assertEquals(8, wire.getInt("format"));
        assertFalse(wire.getList("bodies", 10).getCompound(0).contains("id"));
        assertEquals(model, GroundEphemerisSave.model(wire));
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var buf =
                    new net.minecraft.network.RegistryFriendlyByteBuf(
                            raw, net.minecraft.core.RegistryAccess.EMPTY);
            var packet = new GroundPackets.Sky(73, model);
            GroundPackets.Sky.CODEC.encode(buf, packet);
            assertEquals(packet, GroundPackets.Sky.CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
            assertEquals("native_ground_v6_sky", packet.type().id().getPath());
            var state =
                    GroundDynamics.local(
                            73,
                            new PlanetShape.Pose(
                                    dev.starfold.geometry.CubeSurfaceTopology.Face.TOP,
                                    new Point(100, 600, 100)),
                            GroundEphemeris.ZERO,
                            new org.joml.Quaterniond(),
                            true,
                            GroundDynamics.OFF);
            var setup =
                    new dev.starfold.ground.GroundFlightPackets.Setup(
                            99, model, GroundSpace.DEFAULT_MASK, state, true);
            dev.starfold.ground.GroundFlightPackets.Setup.CODEC.encode(buf, setup);
            assertEquals(setup, dev.starfold.ground.GroundFlightPackets.Setup.CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
            assertEquals("native_ground_v8_setup", setup.type().id().getPath());
        } finally {
            raw.release();
        }
        var downgraded = disk.copy();
        downgraded.putInt("format", 8);
        downgraded.getCompound("motion").putInt("format", 8);
        assertThrows(RuntimeException.class, () -> GroundEphemerisSave.load(downgraded, null));
        for (int version : new int[] {7, 10}) {
            var invalid = disk.copy();
            invalid.putInt("format", version);
            assertThrows(RuntimeException.class, () -> GroundEphemerisSave.load(invalid, null));
        }
        var mismatch = disk.copy();
        mismatch.putInt("format", 8);
        assertThrows(RuntimeException.class, () -> GroundEphemerisSave.load(mismatch, null));
        wire.putInt("format", 7);
        assertThrows(RuntimeException.class, () -> GroundEphemerisSave.model(wire));
        var noPolicy = disk.copy();
        noPolicy.getCompound("motion")
                .getList("bodies", 10)
                .getCompound(model.bodies().size() - 1)
                .remove("gas");
        assertThrows(RuntimeException.class, () -> GroundEphemerisSave.load(noPolicy, null));
    }

    @Test
    void realFrozenOldClockAndUniverseAreRejectedWhileCurrentCountersRoundTrip() throws Exception {
        var provenance =
                JsonParser.parseString(Files.readString(FIXTURE.resolve("provenance.json")))
                        .getAsJsonObject();
        for (var row : provenance.getAsJsonArray("files")) {
            var file = row.getAsJsonObject();
            assertEquals(
                    file.get("sha256").getAsString(),
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    Files.readAllBytes(
                                                            FIXTURE.resolve(
                                                                    file.get("file")
                                                                            .getAsString())))));
        }
        var oldClock = fixture("starfold_native_ground_clock_v1.dat");
        assertEquals(8, oldClock.getInt("format"));
        assertThrows(IllegalArgumentException.class, () -> GroundClock.load(oldClock, null));
        assertThrows(
                IllegalArgumentException.class, () -> GroundEphemerisSave.load(oldClock, null));
        assertThrows(IllegalStateException.class, () -> GroundClock.fresh(true));
        assertThrows(IllegalStateException.class, () -> GroundEphemerisSave.fresh(true));
        var counters = fixture("starfold_body_ids.dat");
        var ids = BodyIdAllocator.load(counters, null);
        assertEquals(counters, ids.save(new CompoundTag(), null));
        assertEquals(new BodyId(0, 16), ids.allocate(0));
        var oldUniverse = fixture("starfold_native_universe_v1.dat");
        assertEquals(7, oldUniverse.getInt("format"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Universe.load(oldUniverse, PlanetTypeFixtures.lookup(), ids));
    }

    @Test
    void universeRemapPreservesGasAndRejectsOldFormat() throws Exception {
        var made = made();
        var ids = new BodyIdAllocator();
        ids.reserve(made.sky().model().bodies());
        var universe = new Universe(made.sky().model().bodies(), made.planets(), ids);
        var lookup = AuthoredSystemTest.types();
        var saved = universe.save(new CompoundTag(), lookup);
        assertEquals(8, saved.getInt("format"));
        assertEquals(universe.catalog(), Universe.load(saved, lookup, ids).catalog());
        saved.putInt("format", 7);
        assertThrows(IllegalArgumentException.class, () -> Universe.load(saved, lookup, ids));
        // Compose a distinct region catalog, never duplicate lifetime IDs from the home model.
        var region = new ArrayList<GroundEphemeris.Body>();
        int n = 0;
        for (var body : made.sky().model().bodies())
            region.add(body.identified(new BodyId(2, n++)));
        var assembled = new Universe(region, made.planets()).model(made.sky().model());
        var appended = assembled.bodies().getLast();
        assertEquals(region.getLast().gas(), appended.gas());
        assertEquals(region.getLast().id(), appended.id());
        assertEquals(-1, appended.slot());
        assertEquals(
                made.sky().model().bodies().size(),
                ((GroundEphemeris.Orbit) appended.motion()).parent());
        assertEquals(made.planets().size() * 2, assembled.count());
    }

    @Test
    void multiSunAppendRetainsTheEntireOldPrefixEvenWithNonFirstHome() throws Exception {
        var original = AuthoredSystemTest.definition();
        // This fixture models the pre-gas catalog even when new defaults include Amber.
        for (var entry : original.getAsJsonArray("suns"))
            entry.getAsJsonObject().remove("gas_giants");
        original.getAsJsonObject("home").addProperty("sun", 1);
        original.getAsJsonArray("suns").get(1).getAsJsonObject()
                .add("position", JsonParser.parseString("[200000,0,0]"));
        var shift =
                original.getAsJsonArray("suns")
                        .get(1)
                        .getAsJsonObject()
                        .getAsJsonArray("position")
                        .deepCopy();
        for (var entry : original.getAsJsonArray("suns")) {
            var sunPosition = entry.getAsJsonObject().getAsJsonArray("position");
            var moved = new JsonArray();
            for (int axis = 0; axis < 3; axis++)
                moved.add(sunPosition.get(axis).getAsDouble() - shift.get(axis).getAsDouble());
            entry.getAsJsonObject().add("position", moved);
        }
        var old = AuthoredSystemTest.read(original).materialize(42);
        var d = withGas(original.deepCopy());
        // This gas belongs to authored sun0, which is not the first materialized star after home
        // reorder.
        var added = AuthoredSystemTest.read(d).materialize(42);
        var base = old.sky().model();
        var model = added.sky().model();
        assertEquals(base.bodies(), model.bodies().subList(0, base.bodies().size()));
        assertEquals(old.sky().names(), added.sky().names());
        assertEquals(old.sky().groups(), added.sky().groups());
        assertEquals(5, model.count());
        assertEquals(8, model.bodies().size());
        assertEquals(3, ((GroundEphemeris.Orbit) model.bodies().getLast().motion()).parent());
        for (int slot = 0; slot < model.count(); slot++)
            assertEquals(
                    old.planets().get(slot).generation(), added.planets().get(slot).generation());
        // Bound both authored total and catalog total, independently of per-sun validation.
        var sun = AuthoredSystemTest.read(d).suns().getFirst();
        var gases = java.util.Collections.nCopies(8, sun.gasGiants().getFirst());
        var crowded = new SystemDefinition.Sun(sun.position(), sun.radius(), sun.planets(), gases);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SystemDefinition(
                                java.util.Collections.nCopies(9, crowded),
                                new SystemDefinition.Home(0, 0),
                                Optional.empty()));
        var bodies = new ArrayList<>(base.bodies());
        for (int n = 0; n < 65; n++)
            bodies.add(model.bodies().getLast().identified(new BodyId(7, n)));
        assertThrows(IllegalArgumentException.class, () -> new GroundEphemeris.Model(bodies));
    }

    @Test
    void discoveryReservesTheGasEnvelopeNotOnlyTheSolidOrbits() throws Exception {
        var old = AuthoredSystemTest.read(MoonSystemTest.fixture()).materialize(42).sky().model();
        var d = withGas(MoonSystemTest.fixture());
        var gas =
                d.getAsJsonArray("suns")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("gas_giants")
                        .get(0)
                        .getAsJsonObject();
        gas.getAsJsonObject("orbit").addProperty("radius", 90000);
        var added = AuthoredSystemTest.read(d).materialize(42).sky().model();
        var position = added.frame(0, 0).center().add(new Point(92000, 0, 0));
        assertTrue(Universe.clearOfHome(position, 100, old));
        assertFalse(Universe.clearOfHome(position, 100, added));
        assertTrue(Universe.clearOfHome(position.add(new Point(10000, 0, 0)), 100, added));
    }
}
