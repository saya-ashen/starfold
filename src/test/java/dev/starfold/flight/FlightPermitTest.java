package dev.starfold.flight;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.ImageStream;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.worldgen.PlanetBiomeFixtures;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public class FlightPermitTest {
    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        PlanetBiomeFixtures.boot();
    }

    public static com.google.gson.JsonObject json(String path) throws Exception {
        return com.google.gson.JsonParser.parseString(
                        Files.readString(Path.of("src/main/resources/" + path)))
                .getAsJsonObject();
    }

    @Test
    void thePackIsTheModsOwnChestEquipmentAndOneToAStack() {
        var id = BuiltInRegistries.ITEM.getKey(FlightPermit.PACK);
        assertEquals("starfold:thruster_pack", id.toString());
        assertEquals(
                EquipmentSlot.CHEST,
                Equipable.get(new ItemStack(FlightPermit.PACK)).getEquipmentSlot());
        assertEquals(1, new ItemStack(FlightPermit.PACK).getMaxStackSize());
    }

    @Test
    void thePermitIsATagAnyEquipmentCanJoinAndThePackIsInIt() throws Exception {
        assertEquals("starfold:flight_permit", FlightPermit.TAG.location().toString());
        var tag = json("data/starfold/tags/item/flight_permit.json");
        assertFalse(tag.get("replace").getAsBoolean());
        assertEquals("starfold:thruster_pack", tag.getAsJsonArray("values").get(0).getAsString());
    }

    @Test
    void thePackIsCraftedAndShownWithItsOwnModelAndNames() throws Exception {
        var recipe = json("data/starfold/recipe/thruster_pack.json");
        assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
        assertEquals(
                "starfold:thruster_pack", recipe.getAsJsonObject("result").get("id").getAsString());
        for (var key : recipe.getAsJsonObject("key").entrySet())
            assertTrue(
                    BuiltInRegistries.ITEM.containsKey(
                            net.minecraft.resources.ResourceLocation.parse(
                                    key.getValue().getAsJsonObject().get("item").getAsString())),
                    key.getKey());
        // Everything it is made of is dug and smelted on the home planet: nothing that only a
        // creature drops.
        var made = new java.util.HashSet<String>();
        for (var key : recipe.getAsJsonObject("key").entrySet())
            made.add(key.getValue().getAsJsonObject().get("item").getAsString());
        assertEquals(
                java.util.Set.of(
                        "minecraft:iron_ingot",
                        "minecraft:redstone_block",
                        "minecraft:copper_ingot"),
                made);
        var home =
                new PlanetTerrain(
                        20261001,
                        dev.starfold.worldgen.PlanetTerrain.VERSION,
                        PlanetGeneration.defaults(dev.starfold.worldgen.PlanetTerrain.VERSION));
        var found = new java.util.HashSet<net.minecraft.world.level.block.Block>();
        for (var f : dev.starfold.geometry.CubeSurfaceTopology.Face.values())
            for (int u = 64; u < 2048; u += 96)
                for (int v = 64; v < 2048; v += 96) {
                    var column = home.column(f, u, v);
                    for (int y = home.floor() + 1; y < column.ground(); y++)
                        found.add(column.state(y).getBlock());
                }
        for (var ore :
                java.util.List.of(
                        net.minecraft.world.level.block.Blocks.IRON_ORE,
                        net.minecraft.world.level.block.Blocks.COPPER_ORE,
                        net.minecraft.world.level.block.Blocks.REDSTONE_ORE,
                        net.minecraft.world.level.block.Blocks.COAL_ORE))
            assertTrue(
                    found.contains(ore)
                            || found.contains(
                                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                                            net.minecraft.resources.ResourceLocation.parse(
                                                    "minecraft:deepslate_"
                                                            + net.minecraft.core.registries
                                                                    .BuiltInRegistries.BLOCK
                                                                    .getKey(ore)
                                                                    .getPath()))),
                    ore.toString());
        assertEquals(
                "starfold:item/thruster_pack",
                json("assets/starfold/models/item/thruster_pack.json")
                        .getAsJsonObject("textures")
                        .get("layer0")
                        .getAsString());
        assertTrue(
                Files.exists(
                        Path.of(
                                "src/main/resources/assets/starfold/textures/item/thruster_pack.png")));
        for (var lang : java.util.List.of("en_us", "zh_cn"))
            assertTrue(
                    json("assets/starfold/lang/" + lang + ".json")
                            .has("item.starfold.thruster_pack"),
                    lang);
    }

    @Test
    void onlyTheServerTickPermitCheckYieldsToAControlledVessel() throws Exception {
        assertEquals(
                1,
                json("starfold.mixins.json").getAsJsonArray("mixins").asList().stream()
                        .filter(e -> e.getAsString().equals("GroundFlightAbilitiesMixin"))
                        .count());
    }

    @Test
    void theWrappedNeoForgeCallIsUniqueAndOwnsTheAbilitiesReset() throws Exception {
        try (var in =
                net.minecraft.server.level.ServerPlayer.class.getResourceAsStream(
                        "/net/minecraft/server/level/ServerPlayer.class")) {
            assertNotNull(in);
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(in).accept(node, 0);
            var tick =
                    node.methods.stream()
                            .filter(m -> m.name.equals("doTick") && m.desc.equals("()V"))
                            .findFirst()
                            .orElseThrow();
            var calls = new java.util.ArrayList<org.objectweb.asm.tree.MethodInsnNode>();
            for (var i : tick.instructions)
                if (i instanceof org.objectweb.asm.tree.MethodInsnNode c && c.name.equals("mayFly"))
                    calls.add(c);
            assertEquals(
                    1, calls.size(), "no ordinal/slice is needed only while this call is unique");
            var call = calls.get(0);
            assertEquals("net/minecraft/server/level/ServerPlayer", call.owner);
            assertEquals("()Z", call.desc);
            assertEquals(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, call.getOpcode());
            boolean reset = false, update = false;
            for (var i = call.getNext(); i != null; i = i.getNext()) {
                if (i instanceof org.objectweb.asm.tree.FieldInsnNode f
                        && f.getOpcode() == org.objectweb.asm.Opcodes.PUTFIELD
                        && f.owner.equals("net/minecraft/world/entity/player/Abilities")
                        && f.name.equals("flying")) reset = true;
                if (i instanceof org.objectweb.asm.tree.MethodInsnNode c
                        && c.name.equals("onUpdateAbilities")) {
                    update = reset;
                    break;
                }
            }
            assertTrue(reset && update, "the selected check clears flying and sends abilities");
        }
    }

    @Test
    void onlyNativeLevelsSkipTheNeoForgePositionChunkLoad() throws Exception {
        assertEquals(
                1,
                json("starfold.mixins.json").getAsJsonArray("mixins").asList().stream()
                        .filter(e -> e.getAsString().equals("GroundEntityPositionMixin"))
                        .count());
        assertEquals("starfold:native_planet", PlanetLevels.PLANET.location().toString());
        assertFalse(PlanetLevels.is(null));
    }

    @Test
    void theWrappedNeoForgePositionLoadIsUniqueAndItsResultIsUnused() throws Exception {
        try (var in =
                net.minecraft.world.entity.Entity.class.getResourceAsStream(
                        "/net/minecraft/world/entity/Entity.class")) {
            assertNotNull(in);
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(in).accept(node, 0);
            var position =
                    node.methods.stream()
                            .filter(m -> m.name.equals("setPosRaw") && m.desc.equals("(DDD)V"))
                            .findFirst()
                            .orElseThrow();
            var calls = new java.util.ArrayList<org.objectweb.asm.tree.MethodInsnNode>();
            for (var i : position.instructions)
                if (i instanceof org.objectweb.asm.tree.MethodInsnNode c
                        && c.name.equals("getChunk")) calls.add(c);
            assertEquals(
                    1, calls.size(), "no ordinal/slice is needed only while this call is unique");
            var call = calls.get(0);
            assertEquals("net/minecraft/world/level/Level", call.owner);
            assertEquals("(II)Lnet/minecraft/world/level/chunk/LevelChunk;", call.desc);
            assertEquals(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, call.getOpcode());
            var next = call.getNext();
            while (next != null && next.getOpcode() < 0) next = next.getNext();
            assertNotNull(next);
            assertEquals(
                    org.objectweb.asm.Opcodes.POP,
                    next.getOpcode(),
                    "NeoForge discards the result, so returning null is safe");
        }
    }

    @Test
    void theHomePlanetIsReachedWithoutACommandAndDeathBeginsAgain() throws Exception {
        assertTrue(
                json("starfold.mixins.json").getAsJsonArray("mixins").asList().stream()
                        .anyMatch(e -> e.getAsString().equals("GroundDeathMixin")));
    }

    @Test
    void aVesselFarAboveAPlanetLoadsAndIsShownNoneOfIt() throws Exception {
        assertEquals(2048, GroundFlightServer.ALOFT);
        assertTrue(
                ImageStream.positions(
                                new net.minecraft.world.level.ChunkPos(40, -7), ImageStream.ALOFT)
                        .isEmpty(),
                "no position lies within that radius");
        assertFalse(
                ImageStream.positions(new net.minecraft.world.level.ChunkPos(40, -7), 2).isEmpty());
    }

    @Test
    void theScreenIsTheGamesOwnOnFootAndAVesselShowsFourLines() throws Exception {
        assertEquals("950", GroundFlightClient.distance(950.4));
        assertEquals("12.3k", GroundFlightClient.distance(12345));
        // Every line and message shown to a player is in both languages, and no entry of one is
        // missing from the other.
        var zh = json("assets/starfold/lang/zh_cn.json");
        var en = json("assets/starfold/lang/en_us.json");
        assertEquals(zh.keySet(), en.keySet());
        for (var face : CubeSurfaceTopology.Face.values())
            assertTrue(
                    zh.has(
                            "message.starfold.face."
                                    + face.name().toLowerCase(java.util.Locale.ROOT)));
        for (var entry : en.entrySet())
            assertFalse(
                    java.util.regex.Pattern.compile("\\p{IsHan}")
                            .matcher(entry.getValue().getAsString())
                            .find(),
                    entry.getKey());
        for (var entry : zh.entrySet())
            assertTrue(
                    java.util.regex.Pattern.compile("\\p{IsHan}")
                            .matcher(entry.getValue().getAsString())
                            .find(),
                    entry.getKey());
    }
}
