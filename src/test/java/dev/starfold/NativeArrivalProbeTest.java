package dev.starfold;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeArrivalProbeTest {
    private static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/dev/starfold/"+name+".java"));}
    @Test void timersAndSamplerAreOptIn()throws Exception{var s=source("NativeArrivalProbe");assertTrue(s.contains("ENABLED=Boolean.getBoolean(\"starfold.carryProbe\")"));assertTrue(s.contains("return ENABLED?System.nanoTime():0"));assertTrue(s.contains("tickStart(){if(!ENABLED)return;"));assertTrue(s.contains("Thread.sleep(100)"));assertTrue(s.contains("daemon()"));assertTrue(s.contains("active==s"));}
    @Test void ordinaryPlayDoesNotInstallHotProbeWrappers()throws Exception{var s=source("RendererMixinPlugin");assertTrue(s.contains("startsWith(\"dev.starfold.mixin.NativeArrival\")&&!Boolean.getBoolean(\"starfold.carryProbe\")"));assertTrue(s.indexOf("NativeArrival")<s.indexOf("RendererCompatibility.apply"));}
    @Test void hooksDelegateAndKeepTheirTimingEvenOnFailure()throws Exception{for(String name:List.of("Tick","Chunk","Frame","Physics","Unload")){var s=source("mixin/NativeArrival"+name+"Mixin");assertTrue(s.contains("original.call("),name);assertTrue(s.contains("finally{"),name);assertTrue(Files.readString(Path.of("src/main/resources/starfold.mixins.json")).contains("NativeArrival"+name+"Mixin"),name);}}
    @Test void physicsProbeWrapsTheRealFullLookupRatherThanReplacingIt()throws Exception{var s=source("mixin/NativeArrivalPhysicsMixin");assertTrue(s.contains("PhysicsChunkTicketManager"));assertTrue(s.contains("method=\"inhabitChunk\""));assertTrue(s.contains("ServerLevel;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"));assertTrue(s.contains("original.call(level,x,z,status,load)"));assertFalse(s.contains("getChunkNow"));}
    @Test void arrivalFixtureCannotRunWithoutProbeAndARegionBody()throws Exception{var s=source("NativeSpaceServer");assertTrue(s.contains("if(!NativeArrivalProbe.ENABLED||!in(player))throw"));assertTrue(s.contains("if(body==null)throw new IllegalStateException(\"No pilot body\")"));assertTrue(s.contains("literal(\"arrivalprobe\").requires(s->s.hasPermission(2))"));}
}
