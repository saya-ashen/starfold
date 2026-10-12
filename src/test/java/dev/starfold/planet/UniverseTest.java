package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import dev.starfold.Sources;
import dev.starfold.chart.GroundSpace;
import dev.starfold.flight.GroundDynamics;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.ground.GroundPackets;
import dev.starfold.ground.GroundServer;
import dev.starfold.ground.GroundTopology;
import dev.starfold.sky.GroundEphemeris;
import dev.starfold.worldgen.PlanetEcologyTest;
import dev.starfold.worldgen.SystemChunkGenerator;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

/** A world's seed makes a universe of its layout once; the world keeps it. */
class UniverseTest {
    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    static UniverseLayout layout(String name) throws Exception {
        return UniverseLayout.CODEC
                .parse(
                        PlanetTypeTest.ops(),
                        JsonParser.parseString(
                                Files.readString(
                                        PlanetTypeTest.DATA.resolve(
                                                "starfold/universe/" + name + ".json"))))
                .getOrThrow();
    }

    /** The layout with a fixed number of suns. */
    static UniverseLayout layout() throws Exception {
        return layout("small");
    }

    static final GroundEphemeris.Model HOME = PlanetSystem.model();

    @Test
    void theSameSeedMakesTheSameUniverseAndAnotherSeedAnother() throws Exception {
        var layout = layout();
        var a = Universe.generate(12345, layout, HOME);
        var again = Universe.generate(12345, layout, HOME);
        var other = Universe.generate(12346, layout, HOME);
        assertEquals(a.stars(), again.stars());
        assertEquals(a.bodies().size(), again.bodies().size());
        for (int i = 0; i < a.bodies().size(); i++) {
            var x = a.bodies().get(i);
            var y = again.bodies().get(i);
            assertEquals(x.star(), y.star());
            assertEquals(x.radius(), y.radius());
            assertEquals(x.phase(), y.phase());
            assertEquals(x.planet().seed(), y.planet().seed());
            assertEquals(x.planet().generation(), y.planet().generation());
        }
        assertNotEquals(a.stars(), other.stars());
    }

    @Test
    void everyUniverseHoldsToItsLayoutAndKeepsItsSunsAndPlanetsApart() throws Exception {
        var layout = layout();
        var covers = new java.util.HashSet<String>();
        var counts = new java.util.HashSet<Integer>();
        var sizes = new java.util.HashSet<Integer>();
        for (long seed = 0; seed < 300; seed++) {
            var u = Universe.generate(seed * 7919 + 1, layout, HOME);
            int stars = u.stars().size();
            counts.add(stars);
            assertTrue(stars >= layout.minStars() && stars <= layout.maxStars());
            var perStar = new int[stars];
            for (var body : u.bodies()) {
                perStar[body.star()]++;
                assertEquals(
                        HOME.radius() + (perStar[body.star()] - 1) * layout.ring(),
                        body.radius(),
                        1e-9,
                        "rings in order");
                assertTrue(body.period() >= 12000);
                var g = body.planet().generation();
                assertTrue(g.coreShell());
                sizes.add(g.size());
                covers.add(
                        g.rocky()
                                ? g.palette().cover().map(Object::toString).orElse("rock")
                                : "temperate");
            }
            for (int n : perStar)
                assertTrue(
                        n >= layout.minPlanets() && n <= layout.maxPlanets(),
                        "planets of a sun: " + n);
            double reach = HOME.radius() + (layout.maxPlanets() - 1) * layout.ring();
            for (int i = 0; i < stars; i++) {
                var p = u.stars().get(i).position();
                assertTrue(
                        p.sub(HOME.center()).length() >= layout.gap() * .75 - 1e-6,
                        "clear of home");
                assertTrue(Math.abs(p.y() - HOME.center().y()) <= layout.gap() * .35 + 1e-6);
                for (int j = 0; j < i; j++)
                    assertTrue(
                            p.sub(u.stars().get(j).position()).length() >= 2 * reach + 8192,
                            "suns " + i + " and " + j + " keep their rings apart");
            }
            var model = u.model(HOME);
            assertEquals(2 + u.bodies().size(), model.count());
            assertEquals(1 + stars, model.everyStar().size());
            for (int id = 2; id < model.count(); id++) {
                var body = u.bodies().get(id - 2);
                var centre = model.at(id, 5000).center();
                assertEquals(
                        body.radius(),
                        centre.sub(u.stars().get(body.star()).position()).length(),
                        1e-6);
                assertEquals(u.stars().get(body.star()), model.star(centre));
            }
            for (int id = 0; id < 2; id++)
                assertEquals(HOME.at(id, 5000).center(), model.at(id, 5000).center());
        }
        assertEquals(java.util.Set.of(2, 3, 4), counts, "every count the layout allows turns up");
        assertEquals(4, covers.size(), "every kind turns up: " + covers);
        assertTrue(covers.contains("temperate"));
        assertTrue(sizes.size() > 40, "sizes vary: " + sizes.size());
    }

    @Test
    void aUniverseIsSavedWholeAndReadBackTheSame() throws Exception {
        var u = Universe.generate(987654321L, layout(), HOME);
        var lookup = PlanetTypeTest.lookup();
        var tag = u.save(new CompoundTag(), lookup);
        var back = Universe.load(tag, lookup);
        assertEquals(u.stars(), back.stars());
        assertEquals(u.bodies().size(), back.bodies().size());
        for (int i = 0; i < u.bodies().size(); i++) {
            var x = u.bodies().get(i);
            var y = back.bodies().get(i);
            assertEquals(x.star(), y.star());
            assertEquals(x.radius(), y.radius());
            assertEquals(x.period(), y.period());
            assertEquals(x.phase(), y.phase());
            assertEquals(x.planet().seed(), y.planet().seed());
            assertEquals(x.planet().generation(), y.planet().generation());
        }
        assertEquals(tag, back.save(new CompoundTag(), lookup));
        assertEquals(u.model(HOME), back.model(HOME));
        var broken = tag.copy();
        broken.putInt("format", 2);
        assertThrows(IllegalArgumentException.class, () -> Universe.load(broken, lookup));
        var orphan = tag.copy();
        ((CompoundTag) orphan.getList("planets", 10).get(0)).putInt("star", 99);
        assertThrows(IllegalArgumentException.class, () -> Universe.load(orphan, lookup));
    }

    @Test
    void aLayoutWhoseSunsWouldCrowdIsRefused() throws Exception {
        var json =
                JsonParser.parseString(
                                Files.readString(
                                        PlanetTypeTest.DATA.resolve(
                                                "starfold/universe/small.json")))
                        .getAsJsonObject();
        var near = json.deepCopy();
        near.addProperty("star_gap", 49152);
        assertThrows(
                IllegalArgumentException.class,
                () -> UniverseLayout.CODEC.parse(PlanetTypeTest.ops(), near));
        var many = json.deepCopy();
        many.getAsJsonObject("planets").addProperty("max", 5);
        assertThrows(
                IllegalArgumentException.class,
                () -> UniverseLayout.CODEC.parse(PlanetTypeTest.ops(), many));
        var none = json.deepCopy();
        none.add("types", new com.google.gson.JsonArray());
        assertThrows(
                IllegalArgumentException.class,
                () -> UniverseLayout.CODEC.parse(PlanetTypeTest.ops(), none));
        assertEquals(98304, UniverseLayout.minimumGap(2, 12288));
        assertEquals(98304, layout().gap());
    }

    @Test
    void aSystemWithALayoutCountsItsUniverseOnceBoundAndSavesTheLayoutWrittenOut()
            throws Exception {
        var ops = PlanetTypeTest.ops();
        var codec = SystemChunkGenerator.CODEC.codec();
        var g = codec.parse(ops, PlanetTypeTest.preset("native_universe")).getOrThrow();
        assertTrue(g.universe().isPresent());
        assertEquals(2, g.count());
        assertArrayEquals(new int[] {2}, g.groups());
        var u = Universe.generate(42, g.universe().get().value(), PlanetSystem.model(g.groups()));
        g.bind(u.planets());
        assertEquals(2 + u.bodies().size(), g.count());
        for (int id = 2; id < g.count(); id++)
            assertSame(u.bodies().get(id - 2).planet(), g.planet(id));
        assertThrows(IllegalArgumentException.class, () -> g.planet(g.count()));
        assertEquals(2048, g.planet(0).terrain().shape().size());
        assertEquals(-528, g.getMinY());
        var saved = codec.encodeStart(ops, g).getOrThrow().getAsJsonObject();
        assertEquals(
                2,
                saved.getAsJsonArray("planets").size(),
                "the universe is kept by the world, not by the generator");
        assertTrue(
                saved.get("universe").isJsonObject(),
                "written out, so that the world does not need the pack");
        assertTrue(
                saved.getAsJsonObject("universe")
                        .getAsJsonArray("types")
                        .get(0)
                        .getAsJsonObject()
                        .get("type")
                        .isJsonObject());
        var back = codec.parse(ops, saved).getOrThrow();
        assertEquals(
                saved,
                codec.encodeStart(ops, back).getOrThrow(),
                "the same layout, read back from what was written out");
        assertEquals(2, back.count());
        // Experience is additional: the original universe remains first; experimental presets still
        // wait behind Alt.
        var normal =
                Files.readString(
                                java.nio.file.Path.of(
                                        "src/main/resources/data/minecraft/tags/worldgen/world_preset/normal.json"))
                        .strip();
        assertEquals(
                "{\"replace\":false,\"values\":[\"starfold:native_universe\",\"starfold_pack:experience\"]}",
                normal);
        assertNotNull(
                net.minecraft.tags.TagFile.CODEC
                        .parse(
                                com.mojang.serialization.JsonOps.INSTANCE,
                                com.google.gson.JsonParser.parseString(normal))
                        .getOrThrow());
    }

    @Test
    void aVesselFarBetweenSunsIsStillPlacedOverItsPlanet() throws Exception {
        var shape = new PlanetShape(1024);
        for (double height : new double[] {40000, 82000, 500000, 4000000}) {
            var pose =
                    GroundTopology.locate(
                            shape,
                            new dev.starfold.geometry.BentStripGeometry.Point(
                                    3, shape.radius() + height, -2));
            assertEquals(height, pose.point().y(), 1e-3);
            assertEquals(
                    shape.radius() + height, GroundTopology.planet(shape, pose).length(), 1e-3);
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GroundTopology.locate(
                                shape,
                                new dev.starfold.geometry.BentStripGeometry.Point(0, 2.0e7, 0)));
        // Its planet is drawn from no further than the view of a planet reaches.
        assertEquals(
                GroundSpace.CEILING,
                GroundTopology.locate(
                                shape,
                                new dev.starfold.geometry.BentStripGeometry.Point(
                                        3, shape.radius() + 145000, -2))
                        .renderPose()
                        .point()
                        .y());
        assertEquals(
                900,
                GroundTopology.locate(
                                shape,
                                new dev.starfold.geometry.BentStripGeometry.Point(
                                        3, shape.radius() + 900, -2))
                        .renderPose()
                        .point()
                        .y(),
                .01);
        // The whole of any universe a layout allows lies within reach of flight.
        var u = Universe.generate(77, layout(), HOME);
        var model = u.model(HOME);
        for (var star : u.stars())
            assertTrue(star.position().sub(model.center()).length() + 8192 <= model.extent());
        assertTrue(model.extent() < GroundTopology.MAX_HEIGHT);
    }

    @Test
    void anEndlessUniverseFindsTheSameSunInACellWheneverAndFromWhereverItIsReached()
            throws Exception {
        var layout = layout("default");
        assertTrue(layout.endless());
        assertFalse(layout().endless());
        long seed = 20261004L;
        var begun = Universe.generate(seed, layout, HOME);
        assertEquals(8, begun.explored(), "the eight cells about home");
        assertFalse(begun.discover(seed, layout, HOME, 0, 0, 900), "home is written in the preset");
        // One traveller goes east then north, another north then east: the cells they both reach
        // hold the same.
        var east = Universe.generate(seed, layout, HOME);
        east.explore(seed, layout, HOME, 3, 0, 1, 900);
        east.explore(seed, layout, HOME, 3, 3, 1, 900);
        var north = Universe.generate(seed, layout, HOME);
        north.explore(seed, layout, HOME, 0, 3, 1, 900);
        north.explore(seed, layout, HOME, 3, 3, 1, 900);
        north.explore(seed, layout, HOME, 3, 0, 1, 900);
        var a = new java.util.HashMap<GroundEphemeris.Star, java.util.List<String>>();
        var b = new java.util.HashMap<GroundEphemeris.Star, java.util.List<String>>();
        for (var pair :
                java.util.List.of(java.util.Map.entry(east, a), java.util.Map.entry(north, b)))
            for (var body : pair.getKey().bodies())
                pair.getValue()
                        .computeIfAbsent(
                                pair.getKey().stars().get(body.star()),
                                k -> new java.util.ArrayList<>())
                        .add(
                                body.radius()
                                        + " "
                                        + body.phase()
                                        + " "
                                        + body.planet().seed()
                                        + " "
                                        + body.planet().generation());
        int shared = 0;
        for (var star : a.keySet())
            if (b.containsKey(star)) {
                shared++;
                assertEquals(a.get(star), b.get(star));
            }
        assertTrue(shared >= 8, "suns both found: " + shared);
        // What was there stays where it was in the list: a planet keeps its number as the universe
        // grows.
        var before = begun.bodies();
        assertTrue(begun.explore(seed, layout, HOME, 2, 0, 1, 900) || begun.explored() > 8);
        var after = begun.bodies();
        for (int id = 0; id < before.size(); id++) assertSame(before.get(id), after.get(id));
        assertFalse(begun.explore(seed, layout, HOME, 2, 0, 1, 900), "nothing is found twice");
    }

    @Test
    void anEndlessUniverseIsAsDenseAsItsLayoutSaysAndKeepsItsSunsApart() throws Exception {
        var layout = layout("default");
        var u = new Universe(java.util.List.of(), java.util.List.of());
        u.explore(99, layout, HOME, 0, 0, 12, 100000);
        int cells = u.explored();
        assertEquals(25 * 25 - 1, cells);
        double share = u.stars().size() / (double) cells;
        assertEquals(layout.density(), share, .06);
        double reach = HOME.radius() + (layout.maxPlanets() - 1) * layout.ring();
        var stars = u.stars();
        for (int i = 0; i < stars.size(); i++) {
            assertTrue(
                    stars.get(i).position().sub(HOME.center()).length()
                            >= layout.gap() * .75 - 1e-6);
            for (int j = 0; j < i; j++)
                assertTrue(
                        stars.get(i).position().sub(stars.get(j).position()).length()
                                >= 2 * reach + 8192);
        }
        assertArrayEquals(new int[] {0, 0}, Universe.cell(layout, HOME, HOME.center()));
        assertArrayEquals(
                new int[] {2, -1},
                Universe.cell(
                        layout,
                        HOME,
                        HOME.center()
                                .add(
                                        new dev.starfold.geometry.BentStripGeometry.Point(
                                                2.3 * layout.gap(), 5000, -.6 * layout.gap()))));
        for (var star : stars) {
            int[] at = Universe.cell(layout, HOME, star.position());
            assertFalse(at[0] == 0 && at[1] == 0);
        }
    }

    @Test
    void aWorldThatCannotHoldMoreLeavesTheCellToBeFoundLater() throws Exception {
        var layout = layout("default");
        var u = new Universe(java.util.List.of(), java.util.List.of());
        assertFalse(u.explore(5, layout, HOME, 0, 0, 3, 0));
        assertEquals(0, u.bodies().size());
        int empty = u.explored();
        assertTrue(empty > 0 && empty < 48, "only the cells without a sun were settled: " + empty);
        assertTrue(u.explore(5, layout, HOME, 0, 0, 3, 900));
        assertEquals(48, u.explored());
    }

    @Test
    void theLastDiscoverablePlanetFitsAndTheNextCellWaitsDespiteSpareStorage() throws Exception {
        assertEquals(1600, Universe.CAPACITY);
        assertEquals(900 * 900, PlanetStorage.MAX_PLANETS);
        var choices =
                java.util.List.of(
                        new UniverseLayout.Choice(
                                net.minecraft.core.Holder.direct(PlanetTypeTest.type("rocky")), 1));
        var single = new UniverseLayout(0, 0, 1, 1, 98304, 12288, choices, 1);
        var pair = new UniverseLayout(0, 0, 2, 2, 98304, 12288, choices, 1);
        var u = new Universe(java.util.List.of(), java.util.List.of());
        int capacity = Universe.CAPACITY - HOME.count();
        // Use the same remaining-capacity contract as generate/grow, with a guaranteed sun in every
        // tested cell.
        for (int x = 1; x < capacity; x++)
            assertTrue(u.explore(7, single, HOME, x, 0, 0, capacity));
        assertEquals(Universe.CAPACITY - 1, u.model(HOME).count());
        var before = u.bodies();
        var suns = u.stars();
        int explored = u.explored();
        u.setDirty(false);
        assertFalse(
                u.explore(7, pair, HOME, capacity, 0, 0, capacity),
                "one slot cannot take part of a two-planet cell");
        assertEquals(before, u.bodies());
        assertEquals(suns, u.stars());
        assertEquals(explored, u.explored());
        assertFalse(u.isDirty());
        assertTrue(
                u.explore(7, single, HOME, capacity, 0, 0, capacity),
                "the rejected cell was not marked explored");
        var system =
                SystemChunkGenerator.CODEC
                        .codec()
                        .parse(PlanetTypeTest.ops(), PlanetTypeTest.preset("native_universe"))
                        .getOrThrow();
        system.bind(u.planets());
        int last = Universe.CAPACITY - 1;
        assertEquals(Universe.CAPACITY, system.count());
        assertEquals(system.count(), u.model(HOME).count());
        assertSame(u.bodies().get(capacity - 1).planet(), system.planet(last));
        for (int id = 0; id < before.size(); id++) assertSame(before.get(id), u.bodies().get(id));
        var lookup = PlanetTypeTest.lookup();
        var full = u.save(new CompoundTag(), lookup);
        u.setDirty(false);
        assertFalse(
                u.explore(7, single, HOME, capacity + 1, 0, 0, capacity),
                "index 1600 is not discovered");
        assertEquals(full, u.save(new CompoundTag(), lookup));
        assertFalse(u.isDirty());
        assertThrows(IllegalArgumentException.class, () -> system.planet(Universe.CAPACITY));
        for (int id : new int[] {last, Universe.CAPACITY, PlanetStorage.MAX_PLANETS - 1})
            assertEquals(
                    id,
                    PlanetStorage.index(PlanetStorage.column(id), PlanetStorage.row(id)),
                    "address capacity is not discovery capacity");
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanetStorage.column(PlanetStorage.MAX_PLANETS));
        // Even after reload the refused cell is still available if the caller supplies more room;
        // no runtime cap change.
        var back = Universe.load(full, lookup);
        assertTrue(back.explore(7, single, HOME, capacity + 1, 0, 0, capacity + 1));
        assertEquals(Universe.CAPACITY + 1, back.model(HOME).count());
    }

    @Test
    void theCellsLookedIntoAreSavedWithTheUniverse() throws Exception {
        var layout = layout("default");
        var u = Universe.generate(31, layout, HOME);
        u.explore(31, layout, HOME, 4, -2, 1, 900);
        var lookup = PlanetTypeTest.lookup();
        var tag = u.save(new CompoundTag(), lookup);
        var back = Universe.load(tag, lookup);
        assertEquals(u.explored(), back.explored());
        assertEquals(u.stars(), back.stars());
        assertEquals(tag, back.save(new CompoundTag(), lookup));
        assertFalse(
                back.explore(31, layout, HOME, 4, -2, 1, 900), "read back, nothing is found again");
        assertFalse(
                Universe.generate(31, layout(), HOME)
                        .save(new CompoundTag(), lookup)
                        .contains("cells"),
                "a universe with a fixed number of suns has no cells");
    }

    @Test
    void aLayoutIsEndlessOrHasANumberOfSunsNotBoth() throws Exception {
        var json =
                JsonParser.parseString(
                                Files.readString(
                                        PlanetTypeTest.DATA.resolve(
                                                "starfold/universe/small.json")))
                        .getAsJsonObject();
        json.addProperty("density", .5);
        assertThrows(
                IllegalArgumentException.class,
                () -> UniverseLayout.CODEC.parse(PlanetTypeTest.ops(), json));
        var endless =
                JsonParser.parseString(
                                Files.readString(
                                        PlanetTypeTest.DATA.resolve(
                                                "starfold/universe/default.json")))
                        .getAsJsonObject();
        assertFalse(endless.has("stars"));
        endless.addProperty("density", 1.5);
        assertTrue(UniverseLayout.CODEC.parse(PlanetTypeTest.ops(), endless).isError());
    }

    @Test
    void theTimeGoesEverySecondAndTheModelOnlyWhenItIsNew() throws Exception {
        var buffer =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        GroundPackets.Sky.CODEC.encode(buffer, new GroundPackets.Sky(4321, null));
        int bare = buffer.readableBytes();
        var read = GroundPackets.Sky.CODEC.decode(buffer);
        assertEquals(4321, read.tick());
        assertNull(read.model());
        assertTrue(bare <= 9, "time alone: " + bare);
        var model = Universe.generate(7, layout("default"), HOME).model(HOME);
        GroundPackets.Sky.CODEC.encode(buffer, new GroundPackets.Sky(4322, model));
        int whole = buffer.readableBytes();
        assertEquals(model, GroundPackets.Sky.CODEC.decode(buffer).model());
        assertTrue(whole > bare * 20);
        var server = Sources.text("GroundServer");
        assertTrue(server.contains("new GroundPackets.Sky(clock.clock.tick(),whole?model:null)"));
        assertTrue(server.contains("if(whole){shapes(player);"));
        assertEquals(1200, GroundServer.WHOLE);
        assertTrue(
                Sources.text("GroundClient")
                        .contains(
                                "if(packet.model()!=null){level=net.minecraft.client.Minecraft.getInstance().level;model=packet.model();}if(model==null||level!=net.minecraft.client.Minecraft.getInstance().level)return;"));
    }

    @Test
    void aUniverseOfNineHundredPlanetsIsStillCheapToFlyIn() throws Exception {
        // As many as a world holds: every cell within fifteen of home, about a planet to a cell.
        var layout = layout("default");
        var u = new Universe(java.util.List.of(), java.util.List.of());
        u.explore(1, layout, HOME, 0, 0, 15, PlanetStorage.MAX_PLANETS - 2);
        var model = u.model(HOME);
        assertTrue(model.count() > 800, "planets: " + model.count());
        var buffer =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        GroundPackets.Sky.CODEC.encode(buffer, new GroundPackets.Sky(1, model));
        int bytes = buffer.readableBytes();
        // One second's flight in deep space: twenty steps, each asking every planet where it is.
        var terrain =
                new GroundDynamics.Terrain() {
                    public boolean clear(GroundTopology.Pose p) {
                        return true;
                    }

                    public double clearance(GroundTopology.Pose p) {
                        return p.point().y();
                    }
                };
        int[] sizes = new int[model.count()], depths = new int[sizes.length];
        java.util.Arrays.fill(sizes, 1024);
        java.util.Arrays.fill(depths, 191);
        PlanetShape.system(sizes, depths);
        try {
            var pose =
                    new GroundTopology.Pose(
                            CubeSurfaceTopology.Face.values()[0],
                            new dev.starfold.geometry.BentStripGeometry.Point(512, 20000, 512));
            var state =
                    GroundDynamics.local(
                            100,
                            0,
                            pose,
                            GroundEphemeris.ZERO,
                            new org.joml.Quaterniond(),
                            true,
                            GroundDynamics.OFF);
            var input = new PlanetMotion.Input(1, 0, 0, 0, 1, 1, 0, 0, true, true);
            for (int i = 0; i < 40; i++)
                state = GroundDynamics.step(model, terrain, state, input, false);
            long began = System.nanoTime();
            for (int i = 0; i < 200; i++)
                state = GroundDynamics.step(model, terrain, state, input, false);
            double perStep = (System.nanoTime() - began) / 200 / 1e6;
            System.out.println(
                    "UNIVERSE "
                            + model.count()
                            + " planets, "
                            + model.everyStar().size()
                            + " suns | whole sky "
                            + bytes
                            + " bytes | flight step "
                            + perStep
                            + " ms");
            assertTrue(
                    perStep < 15,
                    "a step of flight among " + model.count() + " planets: " + perStep + " ms");
            // The bound a planet is passed over by never overstates how near it is.
            for (int id = 0; id < model.count(); id += 37)
                for (long tick : new long[] {0, 5000, 123456}) {
                    var centre = model.at(id, tick).center();
                    for (var probe :
                            new dev.starfold.geometry.BentStripGeometry.Point[] {
                                HOME.center(),
                                centre.add(
                                        new dev.starfold.geometry.BentStripGeometry.Point(
                                                3000, -200, 50)),
                                new dev.starfold.geometry.BentStripGeometry.Point(
                                        250000, 10000, -90000)
                            })
                        assertTrue(model.least(id, probe) <= probe.sub(centre).length() + 1e-6);
                }
            assertTrue(bytes < 1_000_000);
        } finally {
            PlanetShape.reset();
        }
    }
}
