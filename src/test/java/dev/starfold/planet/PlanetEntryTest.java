package dev.starfold.planet;

import dev.starfold.Sources;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class PlanetEntryTest {
    @Test
    void homePreparationRunsAfterAllLevelsExistBeforeTheServerStartsTicking() throws Exception {
        var mixin=Sources.text("PlanetPrepareMixin");
        assertTrue(mixin.contains("@Mixin(MinecraftServer.class)"));
        assertTrue(mixin.contains("@Inject(method=\"prepareLevels\",at=@At(\"HEAD\"))"));
        assertTrue(mixin.contains("getLevel(PlanetLevels.PLANET)"));
        assertTrue(mixin.contains("instanceof SystemChunkGenerator system)PlanetPlanStore.prepareHome(system)"));
        var config=com.google.gson.JsonParser.parseString(Files.readString(Path.of("src/main/resources/starfold.mixins.json"))).getAsJsonObject();
        assertEquals(1,config.getAsJsonArray("mixins").asList().stream().filter(v->v.getAsString().equals("PlanetPrepareMixin")).count());
        var load=calls(method("net/minecraft/server/MinecraftServer","loadLevel"));
        assertTrue(load.indexOf("createLevels")>=0);
        assertTrue(load.indexOf("createLevels")<load.indexOf("prepareLevels"));
        var run=calls(method("net/minecraft/server/MinecraftServer","runServer"));
        assertTrue(run.indexOf("initServer")<run.indexOf("tickServer"));
        for(var name:java.util.List.of("net/minecraft/server/dedicated/DedicatedServer","net/minecraft/client/server/IntegratedServer")) {
            var init=calls(method(name,"initServer"));
            assertTrue(init.contains("loadLevel"),name);
        }
    }

    @Test
    void readyLoginSelectsHomeAndItsPositionBeforeTheFirstLoginPacketAndCoordinateLog() throws Exception {
        var mixin=Sources.text("PlanetJoinMixin");
        assertTrue(mixin.contains("method=\"placeNewPlayer\""));
        assertTrue(mixin.contains("return level==null?null:PlanetPlayers.join(connection,player,level)"));
        var players=Sources.text("PlanetPlayers");
        assertTrue(players.contains("var arrival=arrival(connection,player,level);if(arrival==null)return level;player.moveTo(arrival.spawn.x(),arrival.spawn.y(),arrival.spawn.z(),arrival.yaw,0);return arrival.level;"));
        assertTrue(players.contains("var planet=server.getLevel(PlanetLevels.PLANET)"));
        assertTrue(players.contains("!GroundFlightServer.entryReady(player,planet)"));
        assertTrue(players.contains("return new Arrival(level,clock,saved,spawn,saved==null?home.yaw():0)"));
        var login=calls(method("net/minecraft/server/players/PlayerList","placeNewPlayer"));
        assertEquals(1,login.stream().filter("getLevel"::equals).count());
        assertTrue(login.indexOf("getLevel")<login.indexOf("setServerLevel"));
        assertTrue(login.indexOf("setServerLevel")<login.indexOf("getX"),"the first logged coordinates already belong to home");
        assertTrue(login.indexOf("setServerLevel")<login.indexOf("createCommonSpawnInfo"),"the first login packet names home");
    }

    private static MethodNode method(String name,String method) throws IOException {
        try(var in=net.minecraft.world.entity.Entity.class.getResourceAsStream("/"+name+".class")) {
            assertNotNull(in,name);
            var node=new ClassNode();new ClassReader(in).accept(node,0);
            return node.methods.stream().filter(m->m.name.equals(method)).findFirst().orElseThrow();
        }
    }

    private static java.util.List<String> calls(MethodNode method) {
        var result=new ArrayList<String>();
        for(var instruction:method.instructions)
            if(instruction instanceof MethodInsnNode call)result.add(call.name);
        return result;
    }
}
