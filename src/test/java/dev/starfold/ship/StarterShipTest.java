package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.planet.PlanetShape;
import dev.starfold.planet.PlanetTypeTest;
import dev.starfold.sky.GroundDaylight;
import dev.starfold.sky.GroundSolar;
import dev.starfold.worldgen.PlanetEcologyTest;
import dev.starfold.worldgen.SystemChunkGenerator;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

class StarterShipTest {
    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    @Test
    void optionRoundTripsAndAbsenceDoesNotEnableShip() throws Exception {
        var json = PlanetTypeTest.preset("native_universe").getAsJsonObject();
        var ops = PlanetTypeTest.ops();
        assertTrue(
                SystemChunkGenerator.CODEC
                        .codec()
                        .parse(ops, json)
                        .getOrThrow()
                        .starterShip()
                        .isEmpty());
        json.add(
                "starter_ship",
                JsonParser.parseString("{\"structure\":\"starfold:starter_ship\"}"));
        var generator = SystemChunkGenerator.CODEC.codec().parse(ops, json).getOrThrow();
        var saved = SystemChunkGenerator.CODEC.codec().encodeStart(ops, generator).getOrThrow();
        assertEquals(
                generator.starterShip(),
                SystemChunkGenerator.CODEC.codec().parse(ops, saved).getOrThrow().starterShip());
        assertTrue(
                StarterShip.Option.CODEC
                        .parse(
                                JsonOps.INSTANCE,
                                JsonParser.parseString("{\"structure\":\"BAD ID\"}"))
                        .error()
                        .isPresent());
    }

    @Test
    void sitesPreferFlatDryClearGroundAndBoundFallback() {
        var flat =
                StarterSite.rank(0, 0, 10, 32, (x, z) -> new StarterSite.Column(128, false, false))
                        .getFirst();
        assertEquals(0, flat.x());
        assertEquals(128, flat.y());
        assertTrue(flat.qualified());
        var slope =
                StarterSite.rank(
                                0,
                                0,
                                10,
                                32,
                                (x, z) -> new StarterSite.Column(128 + x / 3, false, false))
                        .getFirst();
        assertFalse(slope.qualified());
        var water =
                StarterSite.rank(0, 0, 10, 32, (x, z) -> new StarterSite.Column(128, x < 12, false))
                        .getFirst();
        assertTrue(water.x() >= 12);
        assertTrue(water.qualified());
        var trees =
                StarterSite.rank(0, 0, 10, 32, (x, z) -> new StarterSite.Column(128, false, x < 12))
                        .getFirst();
        assertTrue(trees.x() >= 12);
        assertTrue(trees.qualified());
        var ocean =
                StarterSite.rank(0, 0, 10, 32, (x, z) -> new StarterSite.Column(127, true, false))
                        .getFirst();
        assertFalse(ocean.qualified());
        assertEquals(127, ocean.y());
    }

    @Test
    void oneBlockNaturalReliefGetsAFlushLevelCircle() {
        var site =
                StarterSite.inspect(
                        0,
                        0,
                        10,
                        (x, z) -> new StarterSite.Column(128 + Math.floorMod(x, 2), false, false));
        assertTrue(site.qualified());
        assertEquals(129, site.y());
        assertEquals(1, site.relief());
    }

    @Test
    void savedReservationAndCompletionNeverGenerateTwice() {
        var state = new StarterShip.State();
        assertTrue(state.fresh());
        state.reserve(new BlockPos(10, 129, 20));
        assertFalse(state.fresh());
        var copy = StarterShip.State.load(state.save(new CompoundTag(), null), null);
        assertFalse(copy.fresh());
        assertFalse(copy.complete());
        state.finish(java.util.UUID.randomUUID());
        copy = StarterShip.State.load(state.save(new CompoundTag(), null), null);
        assertTrue(copy.complete());
        assertFalse(copy.fresh());
        assertEquals(state.spawn(), copy.spawn());
    }

    @Test
    void padMetadataSurvivesAndOldWorldsHaveNoInventedPad() {
        var state = new StarterShip.State();
        state.reserve(new BlockPos(1, 130, 2));
        state.finish(java.util.UUID.randomUUID());
        var tag = state.save(new CompoundTag(), null);
        assertEquals(0, tag.getDouble("padRadius"));
        tag.putLong("padCentre", new BlockPos(50, 129, 60).asLong());
        tag.putDouble("padRadius", 27.50531854708632);
        var saved = StarterShip.State.load(tag, null).save(new CompoundTag(), null);
        assertEquals(tag.getLong("padCentre"), saved.getLong("padCentre"));
        assertEquals(tag.getDouble("padRadius"), saved.getDouble("padRadius"));
    }

    @Test
    void booksAreShortReadablePagesInBothLanguages() {
        for (var language : java.util.List.of("zh_cn", "en_us")) {
            var pages = StarterBooks.pages(language);
            assertTrue(pages.size() >= 10);
            assertTrue(pages.size() <= 100);
            for (var page : pages) {
                assertFalse(page.isBlank());
                assertTrue(page.length() <= 256, () -> language + " long page: " + page.length());
                assertTrue(page.lines().count() <= 14);
            }
            assertTrue(
                    String.join("\n", pages)
                            .contains(
                                    language.equals("zh_cn")
                                            ? "升降途中仍可能射击"
                                            : "moving gun may still fire"));
        }
    }

    @Test
    void missingOptionalMiningModsRefuseExperienceClearly() {
        for (boolean create : new boolean[] {false, true})
            for (boolean simulated : new boolean[] {false, true}) {
                if (create && simulated)
                    assertDoesNotThrow(() -> StarterShip.requireDependencies(create, simulated));
                else
                    assertTrue(
                            assertThrows(
                                            IllegalStateException.class,
                                            () ->
                                                    StarterShip.requireDependencies(
                                                            create, simulated))
                                    .getMessage()
                                    .contains("native_universe"));
            }
    }

    @Test
    void weaponRequiresBothModsAndSparseMergePreservesCabin() throws Exception {
        for (boolean sentry : new boolean[] {false, true})
            for (boolean tacz : new boolean[] {false, true})
                assertEquals(sentry && tacz, StarterShip.weaponEnabled(sentry, tacz));
        var root = java.nio.file.Path.of("src/main/resources/data/starfold/structure");
        var hull =
                net.minecraft.nbt.NbtIo.readCompressed(
                        root.resolve("starter_ship.nbt"),
                        net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        var weapon =
                net.minecraft.nbt.NbtIo.readCompressed(
                        root.resolve("starter_ship_weapon.nbt"),
                        net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        var sha = java.security.MessageDigest.getInstance("SHA-256");
        assertEquals(
                "46ea47397b3f986febee7ba289021972701fca166b2e0cf5c6bc3144931d062a",
                java.util.HexFormat.of()
                        .formatHex(
                                sha.digest(
                                        java.nio.file.Files.readAllBytes(
                                                root.resolve("starter_ship.nbt")))));
        assertEquals(
                "2ae8958a4b82ac790c4c55640b9efaf827834c6bc10411244becdb560a986f16",
                java.util.HexFormat.of()
                        .formatHex(
                                sha.digest(
                                        java.nio.file.Files.readAllBytes(
                                                root.resolve("starter_ship_weapon.nbt")))));
        var original = hull.copy();
        var merged = StarterShip.mergeWeapon(hull, weapon);
        assertEquals(original, hull);
        assertEquals(12, merged.getList("size", 3).getInt(1));
        assertEquals(new BlockPos(31, 4, 8), StarterShip.WEAPON_OFFSET);
        var palette = merged.getList("palette", 10);
        var seen = new java.util.HashSet<BlockPos>();
        int solids = 0, arms = 0, docks = 0;
        for (var entry : merged.getList("blocks", 10)) {
            var b = (CompoundTag) entry;
            var p = b.getList("pos", 3);
            var at = new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2));
            assertTrue(seen.add(at));
            var id = palette.getCompound(b.getInt("state")).getString("Name");
            if (!id.equals("minecraft:air")) solids++;
            if (id.equals("starfold:aircraft_dock")) {
                docks++;
                assertEquals(new BlockPos(28, 7, 11), at);
            }
            if (id.equals("sentrymechanicalarm:sentry_mechanical_arm")) {
                arms++;
                assertEquals(new BlockPos(32, 9, 11), at);
                assertEquals(
                        "false",
                        palette.getCompound(b.getInt("state"))
                                .getCompound("Properties")
                                .getString("ceiling"));
                var data =
                        b.getCompound("nbt")
                                .getCompound("SentryHeldItem")
                                .getCompound("components")
                                .getCompound("minecraft:custom_data");
                assertEquals("tacz:minigun", data.getString("GunId"));
                assertTrue(data.contains("HasBulletInBarrel", 1));
                assertEquals(30, data.getInt("GunCurrentAmmoCount"));
                assertEquals("AUTO", data.getString("GunFireMode"));
                assertEquals(100, b.getCompound("nbt").getInt("ScrollValue"));
                var ammo =
                        b.getCompound("nbt")
                                .getCompound("SentryAmmoBoxes")
                                .getList("Items", 10)
                                .getCompound(0)
                                .getCompound("components")
                                .getCompound("minecraft:custom_data");
                assertTrue(ammo.contains("AllTypeCreative", 1));
                assertTrue(ammo.getBoolean("AllTypeCreative"));
            }
        }
        assertEquals(2370, solids);
        assertEquals(1, arms);
        assertEquals(1, docks);
        var originalBlocks = new java.util.HashMap<BlockPos, CompoundTag>();
        for (var entry : original.getList("blocks", 10)) {
            var b = (CompoundTag) entry;
            var p = b.getList("pos", 3);
            originalBlocks.put(new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2)), b);
        }
        for (var entry : merged.getList("blocks", 10)) {
            var b = (CompoundTag) entry;
            var p = b.getList("pos", 3);
            var at = new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2));
            if (at.getX() < 31) assertEquals(originalBlocks.get(at), b);
        }
        // The optional overlay has no chin cutouts or explicit air at all.
        var weaponPalette = weapon.getList("palette", 10);
        int motors = 0, chassis = 0;
        assertTrue(
                weapon.getList("entities", 10)
                        .isEmpty()); // attachment is persisted blocks, not ignored glue entities
        for (var entry : weapon.getList("blocks", 10)) {
            var block = (CompoundTag) entry;
            var state = weaponPalette.getCompound(block.getInt("state"));
            var id = state.getString("Name");
            assertNotEquals("minecraft:air", id);
            var p = block.getList("pos", 3);
            assertTrue(p.getInt(1) + 4 <= 9, "No mast hardware above roof when stowed");
            if (id.equals("create:creative_motor")) {
                motors++;
                assertEquals(3, p.getInt(0));
                assertEquals(3, p.getInt(1));
                assertEquals(0, p.getInt(2));
                assertEquals("south", state.getCompound("Properties").getString("facing"));
                assertEquals(32, block.getCompound("nbt").getInt("ScrollValue"));
            }
            if (id.equals("create:linear_chassis")) {
                chassis++;
                assertEquals("z", state.getCompound("Properties").getString("axis"));
                assertEquals("true", state.getCompound("Properties").getString("sticky_bottom"));
                assertEquals("false", state.getCompound("Properties").getString("sticky_top"));
                assertEquals("create:chassis", block.getCompound("nbt").getString("id"));
                assertEquals(1, block.getCompound("nbt").getInt("ScrollValue"));
            }
        }
        assertEquals(1, motors);
        assertEquals(6, chassis);
    }

    @Test
    void experienceMorningUsesRisingCelestialSun() {
        var model = GroundSolar.defaults();
        var shape = PlanetShape.STANDARD;
        long tick = GroundDaylight.midMorning(model, shape);
        var face = CubeSurfaceTopology.Face.values()[GroundDaylight.homeFace(model, shape)];
        assertTrue(tick >= GroundDaylight.sunrise(model, shape));
        assertTrue(GroundDaylight.elevation(model, shape, face, tick) >= Math.sqrt(.5));
        assertTrue(
                GroundDaylight.elevation(model, shape, face, tick + 100)
                        > GroundDaylight.elevation(model, shape, face, tick));
        for (var language : java.util.List.of("zh_cn", "en_us")) {
            var text = String.join("\n", StarterBooks.pages(language));
            assertFalse(text.contains("船型仍在试选"));
            assertFalse(text.contains("provisional"));
            assertTrue(text.contains("8"));
            assertTrue(text.contains("Shift"));
        }
    }

    @Test
    void packagedStructureMatchesRegeneratedHash() throws Exception {
        var process =
                new ProcessBuilder(
                                "timeout",
                                "60",
                                "python3",
                                "-B",
                                "tools/ship/pack/check_resource.py")
                        .inheritIO()
                        .start();
        assertTrue(process.waitFor(65, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
    }
}
