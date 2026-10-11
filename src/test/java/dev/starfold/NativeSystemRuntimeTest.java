package dev.starfold;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Protocol/storage/source contracts, NOT an interactive Minecraft runtime acceptance. */
class NativeSystemRuntimeTest {
    @BeforeAll static void boot(){NativePlanetEcologyTest.boot();}
    @Test void selectedPresetHasExactlyOneNativePlanetLevelAndBothSavedPolicies()throws Exception{
        var dims=JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/starfold/worldgen/world_preset/native_system.json"))).getAsJsonObject().getAsJsonObject("dimensions");
        assertEquals(4,dims.size());assertFalse(dims.has("starfold:native_rock"));
        var encoded=dims.getAsJsonObject("starfold:native_planet").getAsJsonObject("generator");assertEquals("starfold:native_system",encoded.get("type").getAsString());
        var g=NativeSystemChunkGenerator.CODEC.codec().parse(RegistryOps.create(JsonOps.INSTANCE,NativePlanetEcologyTest.lookup),encoded).getOrThrow();
        assertEquals(4,g.planet(0).version());assertEquals(5,g.planet(1).version());assertNotEquals(g.planet(0).seed(),g.planet(1).seed());
        assertFalse(g.planet(0).multiple());assertFalse(g.planet(1).multiple());
        // The setup presets stay loadable by id but are not offered in the world creation screen.
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of("src/main/resources/data/minecraft/tags/worldgen/world_preset/extended.json")));
        var original=JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/starfold/dimension/native_planet.json"))).getAsJsonObject();assertEquals(2,original.getAsJsonObject("generator").get("terrain_version").getAsInt());
        var evidence=Path.of("build/native-system");Files.createDirectories(evidence);Files.writeString(evidence.resolve("reproduction.json"),"{\"gpu_verified\":false,\"runtime_verified\":false,\"single_level_preset\":true,\"old_default_version\":2,\"native_dimensions_in_preset\":1,\"storage_layout\":\"six_face_x32768_v1\"}\n");
    }
    @Test void setupDistinguishesSharedAndLegacyLayoutsEvenWithSameTwoPlanetModel(){
        var model=NativePlanetSystem.model();
        for(int id=0;id<2;id++){
            var state=NativeGroundDynamics.local(100,id,new NativeGroundTopology.Pose(Face.TOP,new Point(1024,1000,1024)),GroundEphemeris.ZERO,new org.joml.Quaterniond(),true,NativeGroundDynamics.OFF);
            for(boolean shared:new boolean[]{false,true}){
                var packet=new NativeGroundFlightPackets.Setup(23,model,GroundSpace.DEFAULT_MASK,state,true,GroundAir.DEFAULT,shared);var raw=io.netty.buffer.Unpooled.buffer();
                try{var buf=new RegistryFriendlyByteBuf(raw,RegistryAccess.EMPTY);NativeGroundFlightPackets.Setup.CODEC.encode(buf,packet);var copy=NativeGroundFlightPackets.Setup.CODEC.decode(buf);assertEquals(packet,copy);assertEquals(shared,copy.shared());assertEquals(0,buf.readableBytes());assertEquals("starfold:native_ground_v4_setup",copy.type().id().toString());}finally{raw.release();}
            }
        }
    }
    @Test void blockEditsAndSurfaceSaveDataAreIsolatedWithinOneStorageLayout(){
        var a=new NativeGroundSurfaceIndex(0);var b=new NativeGroundSurfaceIndex(1);
        var local=new BlockPos(1024,250,1024);var remote=local.offset(32768,0,0);a.mark(local);b.mark(remote);
        long key=NativeGroundSurfaceIndex.key(64,64);assertTrue(a.coverage().get((int)key));assertTrue(b.coverage().get((int)key));
        var values=new int[NativeGroundChunkSnapshot.VALUES];Arrays.fill(values,Block.getId(Blocks.AIR.defaultBlockState()));values[16*16*(250+64)]=Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());
        b.update(NativeGroundChunkSnapshot.fromStates(0,64,64,5,values));assertNull(a.surface(key));assertSame(Blocks.GOLD_BLOCK.defaultBlockState(),b.surface(key).state(0,0));
        var loaded=NativeGroundSurfaceIndex.load(b.save(new net.minecraft.nbt.CompoundTag(),null),null);assertSame(Blocks.GOLD_BLOCK.defaultBlockState(),loaded.surface(key).state(0,0));assertTrue(loaded.dirty(key));
        assertNull(NativeGroundSurfaceIndex.load(a.save(new net.minecraft.nbt.CompoundTag(),null),null).surface(key));
        var other=new NativeGroundSurfaceIndex(0);other.mark(remote);assertTrue(other.coverage().isEmpty());
    }
    @Test void invalidationStampsAndTicketsDoNotCollideBetweenPlanets(){
        var versions=new NativeGroundViewVersions();var access=NativePlanetAccess.SHARED;
        long a=access.key(64,64),b=access.key(2112,64);assertNotEquals(a,b);versions.changed(a);long before=versions.get(a);versions.changed(b);assertEquals(before,versions.get(a));assertTrue(versions.get(b)>before);
        assertEquals(2112,access.chunk(b).x);assertThrows(IllegalArgumentException.class,()->NativePlanetAccess.LEGACY.chunk(b));
    }
    @Test void renderingAndPhysicsChartTranslationsAreIdenticalOnBothPlanets(){
        for(var face:Face.values())for(int id=0;id<2;id++){
            var access=NativePlanetAccess.SHARED;var point=new Point(123,1000,456);var pose=new NativeGroundTopology.Pose(face,point);
            var world=access.feet(id,pose).add(new Point(0,.9,0));assertEquals(point,world.sub(access.origin(id,face.ordinal())));
            var restored=access.body(access.feet(id,pose));assertEquals(pose.face(),restored.face());assertEquals(0,pose.point().sub(restored.point()).length(),1e-10);
            for(var source:Face.values())assertEquals(NativeGroundCoordinates.origin(source.ordinal()).sub(NativeGroundCoordinates.origin(face.ordinal())),access.origin(id,source.ordinal()).sub(access.origin(id,face.ordinal())));
        }
    }
    @Test void sharedFlightBranchCannotInvokeCrossLevelTeleportAndClientBindsLayoutBeforeCaches()throws Exception{
        var root=Path.of("src/main/java/dev/starfold");var server=Files.readString(root.resolve("NativeGroundFlightServer.java"));int start=server.indexOf("if(access==NativePlanetAccess.SHARED)");int end=server.indexOf("else if(destination!=",start);assertTrue(start>=0&&end>start);var branch=server.substring(start,end);assertTrue(branch.contains("player.connection.teleport("));assertFalse(branch.contains("player.teleportTo("));
        var system=Files.readString(root.resolve("NativePlanetSystem.java"));assertTrue(system.contains("if(shared(current))return current"));
        var client=Files.readString(root.resolve("NativeGroundFlightClient.java"));assertTrue(client.indexOf("shared=packet.shared()")<client.indexOf("NativeGroundViewClient.bindSession(level,session)"));
        var views=Files.readString(root.resolve("NativeGroundViewClient.java"));assertTrue(views.contains("clear();level=world;session=id;boundPlanet="));
        assertTrue(Files.readString(root.resolve("NativeGroundViewServer.java")).contains("offset+face*NativeGroundCoordinates.STRIDE/16+u"));
    }
}
