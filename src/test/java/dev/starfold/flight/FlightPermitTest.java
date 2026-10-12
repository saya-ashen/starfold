package dev.starfold.flight;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.ImageStream;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.worldgen.PlanetEcologyTest;
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
        PlanetEcologyTest.boot();
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
        var home = new PlanetTerrain(20261001, 4, PlanetGeneration.defaults(4));
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
    void noPartOfThePlanetRuntimeAsksForCreativeModeAnyMore() throws Exception {
        for (var name :
                java.util.List.of(
                        "GroundServer",
                        "PlanetPlayers",
                        "StarfoldServer",
                        "GroundFlightServer",
                        "GroundViewServer",
                        "GroundFlightClient")) {
            var text = Sources.text(name);
            if (name.equals("PlanetPlayers")) {
                assertEquals(
                        1,
                        text.split("isCreative", -1).length - 1,
                        "only vanilla's sleep safety exemption");
                continue;
            }
            assertFalse(text.contains("isCreative"), name);
        }
        var server = Sources.text("GroundFlightServer");
        assertTrue(server.contains("FlightPermit.sync(player)"));
        // On foot above the handoff is still ground; a vessel without the permit has no thrust.
        assertTrue(server.contains("y()>GroundDynamics.HANDOFF&&s.state.flying()"));
        assertTrue(server.contains("if(!permit){motion=PlanetMotion.Input.idle("));
        assertTrue(Sources.text("PlanetPlayers").contains("FlightPermit.revoke(player)"));
        assertTrue(Sources.text("GroundFlightClient").contains("getAbilities().mayfly"));
    }

    @Test
    void onlyTheServerTickPermitCheckYieldsToAControlledVessel() throws Exception {
        var mixin = Sources.text("GroundFlightAbilitiesMixin");
        assertTrue(mixin.contains("@Mixin(ServerPlayer.class)"));
        assertTrue(
                mixin.contains(
                        "@WrapOperation(method=\"doTick\",at=@At(value=\"INVOKE\",target=\"Lnet/minecraft/server/level/ServerPlayer;mayFly()Z\"))"));
        assertTrue(
                mixin.contains(
                        "return GroundFlightServer.controlled(player)||original.call(player);"));
        assertEquals(1, mixin.split("@WrapOperation", -1).length - 1);
        assertFalse(mixin.contains("@Inject"));
        assertFalse(mixin.contains("require=0"));
        assertEquals(
                1,
                json("starfold.mixins.json").getAsJsonArray("mixins").asList().stream()
                        .filter(e -> e.getAsString().equals("GroundFlightAbilitiesMixin"))
                        .count());
        var server = Sources.text("GroundFlightServer");
        assertTrue(
                server.contains(
                        "public static boolean controlled(Entity entity){if(!PlanetLevels.is(entity.level()))return false;"));
        assertTrue(
                server.contains("var s=sessions.get(p.getUUID());return s!=null&&s.controlled;"));
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
        var mixin = Sources.text("GroundEntityPositionMixin");
        assertTrue(mixin.contains("@Mixin(Entity.class)"));
        assertTrue(
                mixin.contains(
                        "@WrapOperation(method=\"setPosRaw\",at=@At(value=\"INVOKE\",target=\"Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;\"))"));
        assertTrue(mixin.contains("Level level,int x,int z,Operation<LevelChunk> original"));
        assertTrue(mixin.contains("return PlanetLevels.is(level)?null:original.call(level,x,z);"));
        assertEquals(1, mixin.split("@WrapOperation", -1).length - 1);
        assertFalse(mixin.contains("@Inject"));
        assertFalse(mixin.contains("require=0"));
        assertEquals(
                1,
                json("starfold.mixins.json").getAsJsonArray("mixins").asList().stream()
                        .filter(e -> e.getAsString().equals("GroundEntityPositionMixin"))
                        .count());
        assertTrue(
                Sources.text("PlanetLevels")
                        .contains(
                                "public static boolean is(Level level){return level!=null&&(level.dimension().equals(DIMENSION)||level.dimension().equals(PLANET)||level.dimension().equals(ROCK));}"));
        assertEquals("starfold:native_ground", PlanetLevels.DIMENSION.location().toString());
        assertEquals("starfold:native_planet", PlanetLevels.PLANET.location().toString());
        assertEquals("starfold:native_rock", PlanetLevels.ROCK.location().toString());
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
        var server = Sources.text("PlanetPlayers");
        assertTrue(
                Sources.text("StarfoldServer")
                        .contains(
                                "NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post cpEvent)->{var server=cpEvent.getServer();if(server.getTickCount()%20==0)PlanetPlayers.arrive(server);});"));
        assertTrue(
                server.contains(
                        "player.getTags().contains(STAY)||!GroundServer.arrivalAttempted(player.getUUID())"),
                "once for each time someone appears in the overworld, unless they chose to stay");
        assertTrue(
                server.contains("player.addTag(STAY)")
                        && server.contains("player.removeTag(STAY)"));
        assertTrue(
                server.contains(
                        "public static void afterDeath(ServerPlayer player){GroundServer.arrivalForgotten(player.getUUID());if(PlanetLevels.is(player.level()))GroundFlightServer.forget(player);}"),
                "a completed death lets them arrive again, and ends its journey");
        var state = Sources.text("GroundServer");
        assertTrue(
                state.contains("arrivalForgotten(java.util.UUID player){arrived.remove(player);}"));
        assertTrue(
                state.contains(
                        "arrivalAttempted(java.util.UUID player){return arrived.add(player);}"));
        var death = Sources.text("GroundDeathMixin");
        assertTrue(death.contains("@Mixin(ServerPlayer.class)"));
        assertTrue(death.contains("@Inject(method=\"die\",at=@At(\"TAIL\"))"));
        assertTrue(death.contains("GroundHost.get().afterDeath((ServerPlayer)(Object)this)"));
        assertTrue(
                Sources.text("StarfoldServer")
                        .contains(
                                "public void afterDeath(net.minecraft.server.level.ServerPlayer player){PlanetPlayers.afterDeath(player);}"));
        assertTrue(
                json("starfold.mixins.json").getAsJsonArray("mixins").asList().stream()
                        .anyMatch(e -> e.getAsString().equals("GroundDeathMixin")));
        var flight = Sources.text("GroundFlightServer");
        assertTrue(flight.contains("if(!player.isAlive())continue;"));
        assertTrue(
                flight.contains(
                        "GroundNavigationSave.get(player.serverLevel()).remove(player.getUUID())"));
        // Going, steering and marking need no permission; setting up and looking into a world does.
        var commands = Sources.text("StarfoldServer");
        assertFalse(commands.contains("Commands.literal(\"starfold\").requires("));
        for (var open : java.util.List.of("terrain", "leave", "status", "universe", "nav"))
            assertFalse(commands.contains("Commands.literal(\"" + open + "\").requires("), open);
        for (var closed : java.util.List.of("enter", "orbit", "face", "spin"))
            assertTrue(
                    commands.contains(
                            "Commands.literal(\"" + closed + "\").requires(s->s.hasPermission(2))"),
                    closed);
    }

    @Test
    void aVesselFarAboveAPlanetLoadsAndIsShownNoneOfIt() throws Exception {
        assertEquals(2048, GroundFlightServer.ALOFT);
        var stream = Sources.text("ImageStream");
        assertTrue(
                stream.contains("int radius=GroundHost.get().aloft(player)?ALOFT:radius(player);"));
        assertTrue(
                ImageStream.positions(
                                new net.minecraft.world.level.ChunkPos(40, -7), ImageStream.ALOFT)
                        .isEmpty(),
                "no position lies within that radius");
        assertFalse(
                ImageStream.positions(new net.minecraft.world.level.ChunkPos(40, -7), 2).isEmpty());
        assertTrue(
                Sources.text("GroundChunkMapMixin")
                        .contains(
                                "@Inject(method=\"skipPlayer\",at=@At(\"HEAD\"),cancellable=true)"));
        assertTrue(
                Sources.text("GroundFlightServer")
                        .contains(
                                "return s!=null&&s.controlled&&(s.state.space()||s.state.local().point().y()>ALOFT);"));
    }

    @Test
    void theScreenIsTheGamesOwnOnFootAndAVesselShowsFourLines() throws Exception {
        var flight = Sources.text("GroundFlightClient");
        assertTrue(
                flight.contains(
                        "if(FlightClientHost.get().diagnosticHud()){debugHud(g,mc);return;}"));
        assertTrue(
                Sources.text("StarfoldClient")
                        .contains("boolean diagnosticHud(){return GroundClient.DEBUG_HUD;}"));
        assertTrue(flight.contains("if(!controlled)return;"), "nothing is drawn on the ground");
        assertTrue(
                Sources.text("GroundClient")
                        .contains(
                                "public static final boolean DEBUG_HUD=Boolean.getBoolean(\"starfold.debug\");"));
        assertEquals("950", GroundFlightClient.distance(950.4));
        assertEquals("12.3k", GroundFlightClient.distance(12345));
        // Every line and message shown to a player is in both languages, and no entry of one is
        // missing from the other.
        var zh = json("assets/starfold/lang/zh_cn.json");
        var en = json("assets/starfold/lang/en_us.json");
        assertEquals(zh.keySet(), en.keySet());
        for (var name :
                java.util.List.of(
                        "GroundFlightClient",
                        "Waypoint",
                        "WaypointSave",
                        "WaypointServer",
                        "WaypointClient",
                        "GroundServer",
                        "PlanetPlayers",
                        "GroundFlightServer",
                        "GroundClient")) {
            var m =
                    java.util.regex.Pattern.compile(
                                    "\"((?:hud|message)\\.starfold\\.[a-z_]+(?:\\.[a-z_]+)*)\"")
                            .matcher(Sources.text(name));
            while (m.find()) assertTrue(zh.has(m.group(1)), name + " " + m.group(1));
        }
        for (var face : CubeSurfaceTopology.Face.values())
            assertTrue(
                    zh.has(
                            "message.starfold.face."
                                    + face.name().toLowerCase(java.util.Locale.ROOT)));
        for (var preset : java.util.List.of("experience", "experience_named_preview")) {
            assertTrue(
                    Files.exists(
                            Path.of(
                                    "src/main/resources/data/starfold_pack/worldgen/world_preset/"
                                            + preset
                                            + ".json")));
            assertTrue(zh.has("generator.starfold_pack." + preset));
        }
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
        // Arriving says nothing: the world is there to be seen.
        assertFalse(
                Sources.text("GroundClient").contains("sendSystemMessage"),
                "renderer notice has a single owner");
        assertTrue(
                Sources.text("RendererGuard")
                        .contains(
                                "rendererNotice.enter(mc.level,PlanetLevels.is(mc.level),mc.player!=null,pipelineSupported())"),
                "only an unsupported renderer in a planet world is spoken of");
        assertFalse(Sources.text("GroundServer").contains("仍需游戏内验收"));
        assertFalse(Sources.text("PlanetPlayers").contains("仍需游戏内验收"));
        assertFalse(Sources.text("GroundClient").contains("新地形仍需游戏内验收"));
    }
}
