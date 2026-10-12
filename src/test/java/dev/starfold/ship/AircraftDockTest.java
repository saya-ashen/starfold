package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.flight.FlightPermitTest;
import dev.starfold.worldgen.PlanetEcologyTest;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class AircraftDockTest {
    @Test
    void cylindricalZoneInLevelAndYawPitchRollHullFrames() {
        for (var q :
                new Quaterniond[] {
                    new Quaterniond(),
                    new Quaterniond().rotationY(Math.PI / 2),
                    new Quaterniond().rotationYXZ(.7, .4, -.3)
                }) {
            var frame = new AircraftDock.Frame(new Vec3(100, 52, -300), q);
            for (var v :
                    new Vec3[] {
                        Vec3.ZERO,
                        new Vec3(1.5, 0, 0),
                        new Vec3(0, 2, -1.5),
                        new Vec3(.9, 1, .9),
                        new Vec3(1.5001, 1, 0),
                        new Vec3(1.4, 1, 1.4),
                        new Vec3(0, -.001, 0),
                        new Vec3(0, 2.001, 0)
                    }) {
                var world = q.transform(new Vector3d(v.x, v.y, v.z));
                var back = frame.local(frame.centre().add(world.x, world.y, world.z));
                assertEquals(v.x, back.x, 1e-10);
                assertEquals(v.y, back.y, 1e-10);
                assertEquals(v.z, back.z, 1e-10);
                assertEquals(AircraftDock.zone(v), AircraftDock.zone(back));
            }
        }
        assertFalse(AircraftDock.zone(new Vec3(Double.NaN, 0, 0)));
        assertFalse(AircraftDock.zone(new Vec3(0, Double.POSITIVE_INFINITY, 0)));
        assertEquals(
                -90,
                new AircraftDock.Frame(Vec3.ZERO, new Quaterniond().rotationY(Math.PI / 2)).yaw(),
                1e-5);
    }

    @Test
    void sameVehicleNeedsTwentyConsecutivePassengerFreeTicks() {
        int state = 0;
        for (int i = 1; i <= 20; i++) {
            state = AircraftDock.settled(state, true, true, false, false);
            assertEquals(i, state);
        }
        assertEquals(20, AircraftDock.settled(state, true, true, false, false));
        assertEquals(0, AircraftDock.settled(19, true, false, false, false));
        assertEquals(0, AircraftDock.settled(19, true, true, true, false));
        assertEquals(1, AircraftDock.settled(19, false, true, false, false));
        assertEquals(1, AircraftDock.settled(0, true, true, false, false));
    }

    @Test
    void sneakReleaseSuppressionLastsUntilFirstExitNotATimer() {
        boolean suppressed = true;
        for (int i = 0; i < 2000; i++) {
            suppressed = AircraftDock.suppressed(suppressed, true);
            assertTrue(suppressed);
            assertEquals(0, AircraftDock.settled(19, true, true, false, suppressed));
        }
        suppressed = AircraftDock.suppressed(suppressed, false);
        assertFalse(suppressed);
        assertFalse(AircraftDock.suppressed(suppressed, true));
        assertEquals(1, AircraftDock.settled(0, true, true, false, suppressed));
    }

    @Test
    void unknownVehicleNbtSurvivesSaveLoadAndSyncWithoutResolution() {
        PlanetEcologyTest.boot();
        var dock = new AircraftDock.Dock(BlockPos.ZERO, AircraftDock.block.defaultBlockState());
        var vehicle = new CompoundTag();
        vehicle.putString("id", "removed_mod:aircraft");
        vehicle.putString("CustomName", "\"Explorer\"");
        vehicle.putInt("Fuel0", 127);
        var inventory = new CompoundTag();
        inventory.putString("upgrade", "unknown:engine");
        vehicle.put("UnknownInventory", inventory);
        dock.stored = vehicle;
        dock.yaw = 73;
        dock.ignored.add(java.util.UUID.randomUUID());
        dock.ignored.add(java.util.UUID.randomUUID());
        var saved = dock.getUpdateTag(PlanetEcologyTest.lookup);
        var restored = new AircraftDock.Dock(BlockPos.ZERO, AircraftDock.block.defaultBlockState());
        restored.loadAdditional(saved, PlanetEcologyTest.lookup);
        assertEquals(vehicle, restored.stored);
        assertEquals(73, restored.yaw);
        assertEquals(dock.ignored, restored.ignored);
        assertEquals(
                vehicle, restored.getUpdateTag(PlanetEcologyTest.lookup).getCompound("Vehicle"));
        saved.getCompound("Vehicle").putString("id", "changed:test");
        assertEquals("removed_mod:aircraft", restored.stored.getString("id"));
    }

    @Test
    void registeredResourcesAndIronRecipe() throws Exception {
        PlanetEcologyTest.boot();
        var id = net.minecraft.resources.ResourceLocation.parse("starfold:aircraft_dock");
        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
        assertInstanceOf(AircraftDock.DockBlock.class, block);
        assertNotEquals(net.minecraft.world.item.Items.AIR, block.asItem());
        assertSame(
                AircraftDock.type,
                net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id));
        var recipe = FlightPermitTest.json("data/starfold/recipe/aircraft_dock.json");
        var ops =
                net.minecraft.resources.RegistryOps.create(
                        com.mojang.serialization.JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        assertInstanceOf(
                net.minecraft.world.item.crafting.ShapedRecipe.class,
                net.minecraft.world.item.crafting.Recipe.CODEC.parse(ops, recipe).getOrThrow());
        assertEquals(
                "minecraft:iron_trapdoor",
                recipe.getAsJsonObject("key").getAsJsonObject("T").get("item").getAsString());
        assertEquals(
                "minecraft:iron_ingot",
                recipe.getAsJsonObject("key").getAsJsonObject("I").get("item").getAsString());
        assertNotNull(
                net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC
                        .parse(
                                ops,
                                FlightPermitTest.json(
                                        "data/starfold/loot_table/blocks/aircraft_dock.json"))
                        .getOrThrow());
        for (var lang : new String[] {"en_us", "zh_cn"})
            for (var key :
                    new String[] {
                        "block.starfold.aircraft_dock",
                        "message.starfold.aircraft_dock.missing_mod",
                        "advancement.starfold.ship.aircraft_dock.title",
                        "advancement.starfold.ship.aircraft_dock.description"
                    })
                assertTrue(
                        FlightPermitTest.json("assets/starfold/lang/" + lang + ".json").has(key));
        for (var texture : new String[] {"top", "side"}) {
            var image =
                    javax.imageio.ImageIO.read(
                            java.nio.file.Path.of(
                                            "src/main/resources/assets/starfold/textures/block/aircraft_dock_"
                                                    + texture
                                                    + ".png")
                                    .toFile());
            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
        }
        for (var path :
                new String[] {
                    "blockstates/aircraft_dock",
                    "models/block/aircraft_dock",
                    "models/item/aircraft_dock"
                }) assertNotNull(FlightPermitTest.json("assets/starfold/" + path + ".json"));
        assertNotNull(FlightPermitTest.json("data/starfold/advancement/ship/aircraft_dock.json"));
    }

    @Test
    void vanillaPersistenceLevelReleaseAndOptionalLinkageContracts() throws Exception {
        var source = Sources.text("AircraftDock");
        var renderer = Sources.text("AircraftDockRenderer");
        assertTrue(source.contains("found.save(data)"));
        assertTrue(source.contains("found.discard()"));
        assertTrue(source.contains("EntityType.create(stored.copy(),level)"));
        assertTrue(source.contains("server.addFreshEntity(entity)"));
        assertTrue(source.contains("player.startRiding(entity)"));
        assertTrue(source.contains("entity.setDeltaMovement(Vec3.ZERO)"));
        assertTrue(source.contains("dock.saveToItem(recovery"));
        var outer = source.substring(0, source.indexOf("private static final class Hull"));
        assertFalse(outer.contains("dev.ryanhcode"));
        assertTrue(outer.contains("if(ModList.get().isLoaded(\"sable\"))Hull.initialize()"));
        assertFalse(source.contains("import immersive_aircraft"));
        assertFalse(renderer.contains("dev.ryanhcode"));
        assertTrue(renderer.contains("catch(RuntimeException|LinkageError"));
        assertTrue(renderer.contains("getEntityRenderDispatcher().render"));
        assertFalse(renderer.contains("conjugate()"));
        assertTrue(Sources.text("StarfoldServer").contains("AircraftDock.initialize(modBus)"));
        assertTrue(
                Sources.text("StarfoldClient").contains("AircraftDockRenderer.initialize(modBus)"));
        for (var lang : new String[] {"en_us", "zh_cn"})
            assertTrue(
                    String.join("\n", StarterBooks.pages(lang))
                            .contains(
                                    lang.equals("en_us")
                                            ? "Sneak-click: release only"
                                            : "潜行右键只放出"));
    }
}
