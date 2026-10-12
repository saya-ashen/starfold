package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;

import dev.starfold.flight.FlightPermitTest;
import dev.starfold.worldgen.PlanetEcologyTest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.storage.loot.LootTable;

import org.junit.jupiter.api.Test;

import java.util.List;

class ThrusterSurvivalTest {
    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    private static com.google.gson.JsonObject json(String path) throws Exception {
        return FlightPermitTest.json("data/" + path + ".json");
    }

    @Test
    void allRecipesParseAndCraftOneRegisteredBlockFromVanillaOnly() throws Exception {
        var lookup = PlanetEcologyTest.lookup;
        var ops = net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, lookup);
        for (var name : List.of("thruster", "gyro", "booster", "scope")) {
            var data = json("starfold/recipe/" + name);
            var recipe =
                    assertInstanceOf(
                            ShapedRecipe.class, Recipe.CODEC.parse(ops, data).getOrThrow());
            assertEquals(CraftingBookCategory.REDSTONE, recipe.category());
            assertEquals(3, recipe.getWidth());
            assertEquals(3, recipe.getHeight());
            var stacks = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
            for (var row : data.getAsJsonArray("pattern"))
                for (char symbol : row.getAsString().toCharArray()) {
                    var id =
                            ResourceLocation.parse(
                                    data.getAsJsonObject("key")
                                            .getAsJsonObject(String.valueOf(symbol))
                                            .get("item")
                                            .getAsString());
                    assertEquals("minecraft", id.getNamespace());
                    assertTrue(BuiltInRegistries.ITEM.containsKey(id));
                    stacks.add(BuiltInRegistries.ITEM.get(id).getDefaultInstance());
                }
            var input = CraftingInput.of(3, 3, stacks);
            assertTrue(recipe.matches(input, null));
            var result = recipe.assemble(input, lookup);
            var id = ResourceLocation.parse("starfold:" + name);
            assertEquals(1, result.getCount());
            assertSame(BuiltInRegistries.ITEM.get(id), result.getItem());
            assertSame(BuiltInRegistries.BLOCK.get(id).asItem(), result.getItem());
            assertFalse(result.is(Items.AIR));
            stacks.set(4, Items.DIRT.getDefaultInstance());
            assertFalse(recipe.matches(CraftingInput.of(3, 3, stacks), null));
        }
    }

    @Test
    void poweredNozzlesCoverEveryDirectionAndPowerExactlyOnce() throws Exception {
        var parts =
                FlightPermitTest.json("assets/starfold/blockstates/thruster.json")
                        .getAsJsonArray("multipart");
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("starfold:thruster"));
        for (var facing : net.minecraft.core.Direction.values())
            for (int power = 0; power <= 15; power++) {
                int matches = 0;
                for (var entry : parts) {
                    var part = entry.getAsJsonObject();
                    var when = part.getAsJsonObject("when");
                    if (!when.get("facing").getAsString().equals(facing.getSerializedName())
                            || !List.of(when.get("power").getAsString().split("\\|", -1))
                                    .contains(Integer.toString(power))) continue;
                    matches++;
                    var apply = part.getAsJsonObject("apply");
                    assertEquals(
                            "starfold:block/thruster"
                                    + (power == 0 ? "" : power < 8 ? "_low" : "_high"),
                            apply.get("model").getAsString());
                    assertEquals(
                            facing == net.minecraft.core.Direction.UP
                                    ? 270
                                    : facing == net.minecraft.core.Direction.DOWN ? 90 : 0,
                            apply.has("x") ? apply.get("x").getAsInt() : 0);
                    assertEquals(
                            switch (facing) {
                                case SOUTH -> 180;
                                case WEST -> 270;
                                case EAST -> 90;
                                default -> 0;
                            },
                            apply.has("y") ? apply.get("y").getAsInt() : 0);
                }
                assertEquals(1, matches);
                assertEquals(
                        power == 0 ? 0 : power < 8 ? 8 : 15,
                        block.defaultBlockState()
                                .setValue(
                                        net.minecraft.world.level.block.state.properties
                                                .BlockStateProperties.POWER,
                                        power)
                                .getLightEmission());
            }
        var booster = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("starfold:booster"));
        var variants =
                FlightPermitTest.json("assets/starfold/blockstates/booster.json")
                        .getAsJsonObject("variants");
        for (boolean on : new boolean[] {false, true}) {
            assertEquals(
                    on ? 12 : 0,
                    booster.defaultBlockState()
                            .setValue(
                                    net.minecraft.world.level.block.state.properties
                                            .BlockStateProperties.POWERED,
                                    on)
                            .getLightEmission());
            assertEquals(
                    "starfold:block/booster" + (on ? "_on" : ""),
                    variants.getAsJsonObject("powered=" + on).get("model").getAsString());
        }
        assertEquals(
                0,
                BuiltInRegistries.BLOCK
                        .get(ResourceLocation.parse("starfold:gyro"))
                        .defaultBlockState()
                        .setValue(
                                net.minecraft.world.level.block.state.properties
                                        .BlockStateProperties.POWERED,
                                true)
                        .getLightEmission());
    }

    @Test
    void poweredTexturesAreSixteenPixelsAndModelsOnlyReplaceFaces() throws Exception {
        for (var suffix : List.of("low", "high")) {
            var model =
                    FlightPermitTest.json(
                            "assets/starfold/models/block/thruster_" + suffix + ".json");
            assertEquals("starfold:block/thruster", model.get("parent").getAsString());
            assertEquals(
                    "starfold:block/thruster_nozzle_" + suffix,
                    model.getAsJsonObject("textures").get("north").getAsString());
            assertFalse(model.has("elements"));
        }
        for (var name :
                List.of(
                        "thruster_side",
                        "thruster_back",
                        "thruster_nozzle",
                        "thruster_nozzle_low",
                        "thruster_nozzle_high",
                        "booster_on")) {
            var image =
                    javax.imageio.ImageIO.read(
                            java.nio.file.Path.of(
                                            "src/main/resources/assets/starfold/textures/block/"
                                                    + name
                                                    + ".png")
                                    .toFile());
            assertNotNull(image);
            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
            assertNotEquals(image.getRGB(0, 0), image.getRGB(7, 7));
        }
        var model = FlightPermitTest.json("assets/starfold/models/block/booster_on.json");
        assertEquals("minecraft:block/cube_all", model.get("parent").getAsString());
        assertEquals(
                "starfold:block/booster_on",
                model.getAsJsonObject("textures").get("all").getAsString());
    }

    @Test
    void nozzleHasAnInsetBodyFourSidedBellAndRecessWithoutChangingHitbox() throws Exception {
        var model = FlightPermitTest.json("assets/starfold/models/block/thruster.json");
        var elements = model.getAsJsonArray("elements");
        assertEquals(6, elements.size());
        assertEquals("[2,2,4]", elements.get(0).getAsJsonObject().get("from").toString());
        assertEquals("[14,14,14]", elements.get(0).getAsJsonObject().get("to").toString());
        assertEquals("[4,4,2]", elements.get(5).getAsJsonObject().get("from").toString());
        assertEquals("[12,12,4]", elements.get(5).getAsJsonObject().get("to").toString());
        for (var element : elements)
            for (var face : element.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
                var name =
                        face.getValue().getAsJsonObject().get("texture").getAsString().substring(1);
                var texture = model.getAsJsonObject("textures").get(name).getAsString().split(":");
                assertTrue(
                        java.nio.file.Files.exists(
                                java.nio.file.Path.of(
                                        "src/main/resources/assets/"
                                                + texture[0]
                                                + "/textures/"
                                                + texture[1]
                                                + ".png")));
                assertFalse(face.getValue().getAsJsonObject().has("cullface"));
            }
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("starfold:thruster"));
        assertEquals(
                new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1),
                block.defaultBlockState()
                        .getCollisionShape(
                                net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                                net.minecraft.core.BlockPos.ZERO)
                        .bounds());
        var item = FlightPermitTest.json("assets/starfold/models/item/thruster.json");
        assertEquals("starfold:block/thruster", item.get("parent").getAsString());
        assertEquals(
                "[25,225,0]",
                item.getAsJsonObject("display").getAsJsonObject("gui").get("rotation").toString());
    }

    @Test
    void boosterQuadruplesImpulseWithoutChangingSignalOrStep() {
        for (int power = 0; power <= 15; power++)
            for (double step : new double[] {0, .01, .05}) {
                double ordinary = 3350 * power / 15.0 * step;
                assertEquals(ordinary, Thrusters.impulse(power, step, false), 1e-10);
                assertEquals(ordinary * 4, Thrusters.impulse(power, step, true), 1e-10);
            }
    }

    @Test
    void thrusterCostsMoreThanThePackAndNeedsABlastFurnace() throws Exception {
        var recipe = json("starfold/recipe/thruster");
        assertEquals(
                List.of("IRI", "IFI", "CCC"),
                recipe.getAsJsonArray("pattern").asList().stream()
                        .map(e -> e.getAsString())
                        .toList());
        assertEquals(
                "minecraft:blast_furnace",
                recipe.getAsJsonObject("key").getAsJsonObject("F").get("item").getAsString());
        // Four iron, one redstone block and three copper, plus the furnace's five iron and stone.
        assertEquals(
                "minecraft:iron_ingot",
                recipe.getAsJsonObject("key").getAsJsonObject("I").get("item").getAsString());
        assertEquals(
                "minecraft:redstone_block",
                recipe.getAsJsonObject("key").getAsJsonObject("R").get("item").getAsString());
        assertEquals(
                "minecraft:copper_ingot",
                recipe.getAsJsonObject("key").getAsJsonObject("C").get("item").getAsString());
    }

    @Test
    void allBlocksHaveSelfLootAndRequireAnIronPickaxe() throws Exception {
        var ops =
                net.minecraft.resources.RegistryOps.create(
                        JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        for (var name : List.of("thruster", "gyro", "booster", "scope")) {
            var id = ResourceLocation.parse("starfold:" + name);
            var block = BuiltInRegistries.BLOCK.get(id);
            assertEquals(id, BuiltInRegistries.BLOCK.getKey(block));
            assertTrue(block.defaultBlockState().requiresCorrectToolForDrops());
            assertEquals(
                    2,
                    block.defaultBlockState()
                            .getDestroySpeed(null, net.minecraft.core.BlockPos.ZERO));
            assertEquals(2, block.getExplosionResistance());
            assertEquals("starfold:blocks/" + name, block.getLootTable().location().toString());
            var loot = json("starfold/loot_table/blocks/" + name);
            assertNotNull(LootTable.DIRECT_CODEC.parse(ops, loot).getOrThrow());
            assertEquals("minecraft:block", loot.get("type").getAsString());
            assertEquals(1, loot.getAsJsonArray("pools").size());
            var pool = loot.getAsJsonArray("pools").get(0).getAsJsonObject();
            assertEquals(1, pool.get("rolls").getAsInt());
            assertEquals(1, pool.getAsJsonArray("entries").size());
            var entry = pool.getAsJsonArray("entries").get(0).getAsJsonObject();
            assertEquals("minecraft:item", entry.get("type").getAsString());
            assertEquals(id.toString(), entry.get("name").getAsString());
            assertEquals(1, pool.getAsJsonArray("conditions").size());
            assertEquals(
                    "minecraft:survives_explosion",
                    pool.getAsJsonArray("conditions")
                            .get(0)
                            .getAsJsonObject()
                            .get("condition")
                            .getAsString());
            for (var tag : List.of("mineable/pickaxe", "needs_iron_tool")) {
                var data = json("minecraft/tags/block/" + tag);
                assertFalse(data.get("replace").getAsBoolean());
                assertTrue(
                        data.getAsJsonArray("values").asList().stream()
                                .anyMatch(e -> e.getAsString().equals(id.toString())),
                        tag);
            }
            for (var lang : List.of("en_us", "zh_cn"))
                assertTrue(
                        FlightPermitTest.json("assets/starfold/lang/" + lang + ".json")
                                .has("block.starfold." + name));
        }
    }
}
