package dev.starfold;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class NativeFlightPermitTest {
    @org.junit.jupiter.api.BeforeAll static void boot(){NativePlanetEcologyTest.boot();}
    static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/dev/starfold/"+name+".java"));}
    static com.google.gson.JsonObject json(String path)throws Exception{return com.google.gson.JsonParser.parseString(Files.readString(Path.of("src/main/resources/"+path))).getAsJsonObject();}
    @Test void thePackIsTheModsOwnChestEquipmentAndOneToAStack(){
        var id=BuiltInRegistries.ITEM.getKey(NativeFlightPermit.PACK);assertEquals("starfold:thruster_pack",id.toString());
        assertEquals(EquipmentSlot.CHEST,Equipable.get(new ItemStack(NativeFlightPermit.PACK)).getEquipmentSlot());assertEquals(1,new ItemStack(NativeFlightPermit.PACK).getMaxStackSize());
    }
    @Test void thePermitIsATagAnyEquipmentCanJoinAndThePackIsInIt()throws Exception{
        assertEquals("starfold:flight_permit",NativeFlightPermit.TAG.location().toString());
        var tag=json("data/starfold/tags/item/flight_permit.json");assertFalse(tag.get("replace").getAsBoolean());assertEquals("starfold:thruster_pack",tag.getAsJsonArray("values").get(0).getAsString());
    }
    @Test void thePackIsCraftedAndShownWithItsOwnModelAndNames()throws Exception{
        var recipe=json("data/starfold/recipe/thruster_pack.json");assertEquals("minecraft:crafting_shaped",recipe.get("type").getAsString());assertEquals("starfold:thruster_pack",recipe.getAsJsonObject("result").get("id").getAsString());
        for(var key:recipe.getAsJsonObject("key").entrySet())assertTrue(BuiltInRegistries.ITEM.containsKey(net.minecraft.resources.ResourceLocation.parse(key.getValue().getAsJsonObject().get("item").getAsString())),key.getKey());
        // Everything it is made of is dug and smelted on the home planet: nothing that only a creature drops.
        var made=new java.util.HashSet<String>();for(var key:recipe.getAsJsonObject("key").entrySet())made.add(key.getValue().getAsJsonObject().get("item").getAsString());assertEquals(java.util.Set.of("minecraft:iron_ingot","minecraft:redstone_block","minecraft:copper_ingot"),made);
        var home=new NativePlanetTerrain(20261001,4,NativePlanetGeneration.defaults(4));var found=new java.util.HashSet<net.minecraft.world.level.block.Block>();
        for(var f:dev.starfold.CubeSurfaceTopology.Face.values())for(int u=64;u<2048;u+=96)for(int v=64;v<2048;v+=96){var column=home.column(f,u,v);for(int y=home.floor()+1;y<column.ground();y++)found.add(column.state(y).getBlock());}
        for(var ore:java.util.List.of(net.minecraft.world.level.block.Blocks.IRON_ORE,net.minecraft.world.level.block.Blocks.COPPER_ORE,net.minecraft.world.level.block.Blocks.REDSTONE_ORE,net.minecraft.world.level.block.Blocks.COAL_ORE))assertTrue(found.contains(ore)||found.contains(net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("minecraft:deepslate_"+net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(ore).getPath()))),ore.toString());
        assertEquals("starfold:item/thruster_pack",json("assets/starfold/models/item/thruster_pack.json").getAsJsonObject("textures").get("layer0").getAsString());
        assertTrue(Files.exists(Path.of("src/main/resources/assets/starfold/textures/item/thruster_pack.png")));
        for(var lang:java.util.List.of("en_us","zh_cn"))assertTrue(json("assets/starfold/lang/"+lang+".json").has("item.starfold.thruster_pack"),lang);
    }
    @Test void noPartOfThePlanetRuntimeAsksForCreativeModeAnyMore()throws Exception{
        for(var name:java.util.List.of("NativeGroundServer","NativeGroundFlightServer","NativeGroundViewServer","NativeGroundFlightClient")){var text=source(name);if(name.equals("NativeGroundServer")){var start=text.indexOf("private static void canSleep(net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent event)");var end=text.indexOf("public static void initialize(",start);assertTrue(start>=0&&end>start);assertEquals(1,text.substring(start,end).split("isCreative",-1).length-1,"only vanilla's sleep safety exemption");text=text.substring(0,start)+text.substring(end);}assertFalse(text.contains("isCreative"),name);}
        var server=source("NativeGroundFlightServer");assertTrue(server.contains("NativeFlightPermit.sync(player)"));
        // On foot above the handoff is still ground; a vessel without the permit has no thrust.
        assertTrue(server.contains("y()>NativeGroundDynamics.HANDOFF&&s.state.flying()"));assertTrue(server.contains("if(!permit){motion=PlanetMotion.Input.idle("));
        assertTrue(source("NativeGroundServer").contains("NativeFlightPermit.revoke(player)"));assertTrue(source("NativeGroundFlightClient").contains("getAbilities().mayfly"));
    }
    @Test void onlyTheServerTickPermitCheckYieldsToAControlledVessel()throws Exception{
        var mixin=source("mixin/NativeGroundFlightAbilitiesMixin");assertTrue(mixin.contains("@Mixin(ServerPlayer.class)"));
        assertTrue(mixin.contains("@WrapOperation(method=\"doTick\",at=@At(value=\"INVOKE\",target=\"Lnet/minecraft/server/level/ServerPlayer;mayFly()Z\"))"));
        assertTrue(mixin.contains("return NativeGroundFlightServer.controlled(player)||original.call(player);"));assertEquals(1,mixin.split("@WrapOperation",-1).length-1);assertFalse(mixin.contains("@Inject"));assertFalse(mixin.contains("require=0"));
        assertEquals(1,json("starfold.mixins.json").getAsJsonArray("mixins").asList().stream().filter(e->e.getAsString().equals("NativeGroundFlightAbilitiesMixin")).count());
        var server=source("NativeGroundFlightServer");assertTrue(server.contains("public static boolean controlled(Entity entity){if(!NativeGroundServer.is(entity.level()))return false;"));assertTrue(server.contains("var s=sessions.get(p.getUUID());return s!=null&&s.controlled;"));
    }
    @Test void theWrappedNeoForgeCallIsUniqueAndOwnsTheAbilitiesReset()throws Exception{
        try(var in=net.minecraft.server.level.ServerPlayer.class.getResourceAsStream("/net/minecraft/server/level/ServerPlayer.class")){
            assertNotNull(in);var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(in).accept(node,0);
            var tick=node.methods.stream().filter(m->m.name.equals("doTick")&&m.desc.equals("()V")).findFirst().orElseThrow();
            var calls=new java.util.ArrayList<org.objectweb.asm.tree.MethodInsnNode>();for(var i:tick.instructions)if(i instanceof org.objectweb.asm.tree.MethodInsnNode c&&c.name.equals("mayFly"))calls.add(c);
            assertEquals(1,calls.size(),"no ordinal/slice is needed only while this call is unique");var call=calls.get(0);assertEquals("net/minecraft/server/level/ServerPlayer",call.owner);assertEquals("()Z",call.desc);assertEquals(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,call.getOpcode());
            boolean reset=false,update=false;for(var i=call.getNext();i!=null;i=i.getNext()){
                if(i instanceof org.objectweb.asm.tree.FieldInsnNode f&&f.getOpcode()==org.objectweb.asm.Opcodes.PUTFIELD&&f.owner.equals("net/minecraft/world/entity/player/Abilities")&&f.name.equals("flying"))reset=true;
                if(i instanceof org.objectweb.asm.tree.MethodInsnNode c&&c.name.equals("onUpdateAbilities")){update=reset;break;}
            }assertTrue(reset&&update,"the selected check clears flying and sends abilities");
        }
    }
    @Test void onlyNativeLevelsSkipTheNeoForgePositionChunkLoad()throws Exception{
        var mixin=source("mixin/NativeGroundEntityPositionMixin");assertTrue(mixin.contains("@Mixin(Entity.class)"));
        assertTrue(mixin.contains("@WrapOperation(method=\"setPosRaw\",at=@At(value=\"INVOKE\",target=\"Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;\"))"));
        assertTrue(mixin.contains("Level level,int x,int z,Operation<LevelChunk> original"));assertTrue(mixin.contains("return NativeGroundServer.is(level)?null:original.call(level,x,z);"));assertEquals(1,mixin.split("@WrapOperation",-1).length-1);assertFalse(mixin.contains("@Inject"));assertFalse(mixin.contains("require=0"));
        assertEquals(1,json("starfold.mixins.json").getAsJsonArray("mixins").asList().stream().filter(e->e.getAsString().equals("NativeGroundEntityPositionMixin")).count());
        assertTrue(source("NativeGroundServer").contains("public static boolean is(Level level){return level!=null&&(level.dimension().equals(DIMENSION)||level.dimension().equals(PLANET)||level.dimension().equals(ROCK));}"));
        assertEquals("starfold:native_ground",NativeGroundServer.DIMENSION.location().toString());assertEquals("starfold:native_planet",NativeGroundServer.PLANET.location().toString());assertEquals("starfold:native_rock",NativeGroundServer.ROCK.location().toString());assertFalse(NativeGroundServer.is(null));
    }
    @Test void theWrappedNeoForgePositionLoadIsUniqueAndItsResultIsUnused()throws Exception{
        try(var in=net.minecraft.world.entity.Entity.class.getResourceAsStream("/net/minecraft/world/entity/Entity.class")){
            assertNotNull(in);var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(in).accept(node,0);
            var position=node.methods.stream().filter(m->m.name.equals("setPosRaw")&&m.desc.equals("(DDD)V")).findFirst().orElseThrow();
            var calls=new java.util.ArrayList<org.objectweb.asm.tree.MethodInsnNode>();for(var i:position.instructions)if(i instanceof org.objectweb.asm.tree.MethodInsnNode c&&c.name.equals("getChunk"))calls.add(c);
            assertEquals(1,calls.size(),"no ordinal/slice is needed only while this call is unique");var call=calls.get(0);assertEquals("net/minecraft/world/level/Level",call.owner);assertEquals("(II)Lnet/minecraft/world/level/chunk/LevelChunk;",call.desc);assertEquals(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,call.getOpcode());
            var next=call.getNext();while(next!=null&&next.getOpcode()<0)next=next.getNext();assertNotNull(next);assertEquals(org.objectweb.asm.Opcodes.POP,next.getOpcode(),"NeoForge discards the result, so returning null is safe");
        }
    }
    @Test void theHomePlanetIsReachedWithoutACommandAndDeathBeginsAgain()throws Exception{
        var server=source("NativeGroundServer");assertTrue(server.contains("NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post cpEvent)->{var server=cpEvent.getServer();if(server.getTickCount()%20==0)arrive(server);});"));
        assertTrue(server.contains("player.getTags().contains(STAY)||!arrived.add(player.getUUID())"),"once for each time someone appears in the overworld, unless they chose to stay");assertTrue(server.contains("player.addTag(STAY)")&&server.contains("player.removeTag(STAY)"));
        assertTrue(server.contains("public static void afterDeath(ServerPlayer player){arrived.remove(player.getUUID());if(is(player.level()))NativeGroundFlightServer.forget(player);}"),"a completed death lets them arrive again, and ends its journey");var death=source("mixin/NativeGroundDeathMixin");assertTrue(death.contains("@Mixin(ServerPlayer.class)"));assertTrue(death.contains("@Inject(method=\"die\",at=@At(\"TAIL\"))"));assertTrue(death.contains("NativeGroundServer.afterDeath((ServerPlayer)(Object)this)"));assertTrue(json("starfold.mixins.json").getAsJsonArray("mixins").asList().stream().anyMatch(e->e.getAsString().equals("NativeGroundDeathMixin")));
        var flight=source("NativeGroundFlightServer");assertTrue(flight.contains("if(!player.isAlive())continue;"));assertTrue(flight.contains("NativeGroundNavigationSave.get(player.serverLevel()).remove(player.getUUID())"));
        // Going, steering and marking need no permission; setting up and looking into a world does.
        assertFalse(server.contains("Commands.literal(\"starfold\").requires("));for(var open:java.util.List.of("terrain","leave","status","universe","nav"))assertFalse(server.contains("Commands.literal(\""+open+"\").requires("),open);
        for(var closed:java.util.List.of("enter","orbit","face","spin"))assertTrue(server.contains("Commands.literal(\""+closed+"\").requires(s->s.hasPermission(2))"),closed);
    }
    @Test void aVesselFarAboveAPlanetLoadsAndIsShownNoneOfIt()throws Exception{
        assertEquals(2048,NativeGroundFlightServer.ALOFT);
        var stream=source("NativeImageStream");assertTrue(stream.contains("int radius=NativeGroundFlightServer.aloft(player)?ALOFT:radius(player);"));
        assertTrue(NativeImageStream.positions(new net.minecraft.world.level.ChunkPos(40,-7),NativeImageStream.ALOFT).isEmpty(),"no position lies within that radius");assertFalse(NativeImageStream.positions(new net.minecraft.world.level.ChunkPos(40,-7),2).isEmpty());
        assertTrue(Files.readString(Path.of("src/main/java/dev/starfold/mixin/NativeGroundChunkMapMixin.java")).contains("@Inject(method=\"skipPlayer\",at=@At(\"HEAD\"),cancellable=true)"));
        assertTrue(source("NativeGroundFlightServer").contains("return s!=null&&s.controlled&&(s.state.space()||s.state.local().point().y()>ALOFT);"));
    }
    @Test void theScreenIsTheGamesOwnOnFootAndAVesselShowsFourLines()throws Exception{
        var flight=source("NativeGroundFlightClient");assertTrue(flight.contains("if(NativeGroundClient.DEBUG_HUD){debugHud(g,mc);return;}"));assertTrue(flight.contains("if(!controlled)return;"),"nothing is drawn on the ground");
        assertTrue(source("NativeGroundClient").contains("public static final boolean DEBUG_HUD=Boolean.getBoolean(\"starfold.debug\");"));
        assertEquals("950",NativeGroundFlightClient.distance(950.4));assertEquals("12.3k",NativeGroundFlightClient.distance(12345));
        // Every line and message shown to a player is in both languages, and no entry of one is missing from the other.
        var zh=json("assets/starfold/lang/zh_cn.json");var en=json("assets/starfold/lang/en_us.json");assertEquals(zh.keySet(),en.keySet());
        for(var name:java.util.List.of("NativeGroundFlightClient","NativeWaypoint","NativeWaypointSave","NativeWaypointServer","NativeWaypointClient","NativeGroundServer","NativeGroundFlightServer","NativeGroundClient")){var m=java.util.regex.Pattern.compile("\"((?:hud|message)\\.starfold\\.[a-z_]+(?:\\.[a-z_]+)*)\"").matcher(source(name));while(m.find())assertTrue(zh.has(m.group(1)),name+" "+m.group(1));}
        for(var face:CubeSurfaceTopology.Face.values())assertTrue(zh.has("message.starfold.face."+face.name().toLowerCase(java.util.Locale.ROOT)));
        for(var preset:java.util.List.of("experience","experience_named_preview")){assertTrue(Files.exists(Path.of("src/main/resources/data/starfold_pack/worldgen/world_preset/"+preset+".json")));assertTrue(zh.has("generator.starfold_pack."+preset));}
        for(var entry:en.entrySet())assertFalse(java.util.regex.Pattern.compile("\\p{IsHan}").matcher(entry.getValue().getAsString()).find(),entry.getKey());
        for(var entry:zh.entrySet())assertTrue(java.util.regex.Pattern.compile("\\p{IsHan}").matcher(entry.getValue().getAsString()).find(),entry.getKey());
        // Arriving says nothing: the world is there to be seen.
        assertFalse(source("NativeGroundClient").contains("sendSystemMessage"),"renderer notice has a single owner");
        assertTrue(source("LocalCapClient").contains("rendererNotice.enter(mc.level,NativeGroundServer.is(mc.level),mc.player!=null,pipelineSupported())"),"only an unsupported renderer in a planet world is spoken of");
        assertFalse(source("NativeGroundServer").contains("仍需游戏内验收"));assertFalse(source("NativeGroundClient").contains("新地形仍需游戏内验收"));
    }
}
