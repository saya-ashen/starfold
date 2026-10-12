package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.flight.FlightPermitTest;
import dev.starfold.worldgen.PlanetEcologyTest;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

class HelmBlockTest {
    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    @Test
    void fourDirectionsAndEntityAreRegistered() {
        var id = ResourceLocation.parse("starfold:helm");
        var block = BuiltInRegistries.BLOCK.get(id);
        assertInstanceOf(Thrusters.HelmBlock.class, block);
        assertEquals(id, BuiltInRegistries.ITEM.getKey(block.asItem()));
        for (var d :
                new Direction[] {
                    Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
                }) {
            var state =
                    block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, d);
            var entity = ((EntityBlock) block).newBlockEntity(BlockPos.ZERO, state);
            assertInstanceOf(Thrusters.Helm.class, entity);
            assertEquals(id, BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()));
            assertNull(((EntityBlock) block).getTicker(null, state, entity.getType()));
        }
    }

    @Test
    void resourcesHaveFourModelsVanillaRecipeLootAndTools() throws Exception {
        var variants =
                FlightPermitTest.json("assets/starfold/blockstates/helm.json")
                        .getAsJsonObject("variants");
        assertEquals(4, variants.size());
        assertEquals(
                3,
                FlightPermitTest.json("assets/starfold/models/block/helm.json")
                        .getAsJsonArray("elements")
                        .size());
        assertEquals(
                "starfold:block/helm",
                FlightPermitTest.json("assets/starfold/models/item/helm.json")
                        .get("parent")
                        .getAsString());
        var recipe = FlightPermitTest.json("data/starfold/recipe/helm.json");
        for (var e : recipe.getAsJsonObject("key").entrySet())
            assertTrue(
                    e.getValue()
                            .getAsJsonObject()
                            .get("item")
                            .getAsString()
                            .startsWith("minecraft:"));
        assertTrue(
                FlightPermitTest.json("data/starfold/loot_table/blocks/helm.json")
                        .toString()
                        .contains("starfold:helm"));
        for (var tag : new String[] {"mineable/pickaxe", "needs_iron_tool"})
            assertTrue(
                    FlightPermitTest.json("data/minecraft/tags/block/" + tag + ".json")
                            .toString()
                            .contains("starfold:helm"));
        for (var lang : new String[] {"en_us", "zh_cn"})
            assertNotNull(
                    FlightPermitTest.json("assets/starfold/lang/" + lang + ".json")
                            .get("block.starfold.helm"));
        for (var name : new String[] {"helm_housing", "helm_screen", "helm_arrow"}) {
            var image =
                    javax.imageio.ImageIO.read(
                            Path.of(
                                            "src/main/resources/assets/starfold/textures/block/"
                                                    + name
                                                    + ".png")
                                    .toFile());
            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
            assertNotEquals(image.getRGB(0, 0), image.getRGB(7, 7));
        }
    }

    @Test
    void serverAndClientShareSelectorAndDoNotSavePilotOrWritePowerStates() throws Exception {
        var server = Sources.text("HelmServer");
        var client = Sources.text("HelmInputClient");
        assertTrue(server.contains("HelmCommand.fires"));
        assertTrue(client.contains("HelmCommand.fires"));
        assertFalse(server.contains("setBlock("));
        assertFalse(server.contains("saveAdditional"));
        assertFalse(server.contains("Class.forName"));
        assertTrue(client.contains("MovementInputUpdateEvent"));
        for (var key :
                new String[] {
                    "keyUp", "keyDown", "keyLeft", "keyRight", "keyJump", "keyShift", "keySprint"
                }) assertTrue(client.contains(key + ".isDown()"));
        assertTrue(Sources.text("Thrusters").contains("event.accept(helm)"));
        try (var files = Files.walk(Path.of("src/main/java"))) {
            assertFalse(
                    files.anyMatch(p -> p.getFileName().toString().equals("HelmUserMixin.java")));
        }
    }
}
