package dev.starfold;

import dev.starfold.CubeSurfaceTopology.Face;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativePlanetDepthTest {
    @org.junit.jupiter.api.BeforeAll static void boot(){NativePlanetEcologyTest.boot();}
    static NativePlanetGeneration rocky(int size,int depth){return new NativePlanetGeneration("rocky","rocky_craters",1,1.2,.04,.09,size,depth);}
    @Test void aPlanetSavedWithoutADepthIsTheStandardDepth(){
        assertEquals(191,NativePlanetGeneration.STANDARD_DEPTH);var old=new NativePlanetGeneration("rocky","rocky_craters",1,1.2,.04,.09,1024);
        assertEquals(191,old.depth());assertEquals(NativePlanetTerrain.MIN_Y,old.floor());assertEquals(old,rocky(1024,191));
        var json=NativePlanetGeneration.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,old).getOrThrow().getAsJsonObject();assertFalse(json.has("depth"));
        var terrain=new NativePlanetTerrain(20261002,5,old);for(int y=-70;y<140;y+=7)assertEquals(y,terrain.layer(y));
    }
    @Test void aDepthIsSavedAndBoundedByThePlanet(){
        var deep=rocky(1024,326);assertEquals(-199,deep.floor());
        assertEquals(deep,NativePlanetGeneration.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,NativePlanetGeneration.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,deep).getOrThrow()).getOrThrow());
        assertThrows(IllegalArgumentException.class,()->rocky(1024,190));assertThrows(IllegalArgumentException.class,()->rocky(1024,600));assertEquals(652,rocky(2048,652).depth());
    }
    @Test void aDeeperPlanetHasTheSameSurfaceAndGroundDownToItsOwnFloor(){
        var shallow=new NativePlanetTerrain(20261002,5,rocky(1024,191));var deep=new NativePlanetTerrain(20261002,5,rocky(1024,326));assertEquals(-199,deep.floor());
        int solid=0,caves=0;for(var f:Face.values())for(int u=70;u<960;u+=223)for(int v=70;v<960;v+=211){
            var a=shallow.column(f,u,v);var b=deep.column(f,u,v);assertEquals(a.ground(),b.ground());
            for(int y=a.ground()-3;y<NativePlanetTerrain.MAX_Y;y++)assertEquals(a.state(y),b.state(y),f+" "+u+" "+v+" "+y);
            assertSame(Blocks.BEDROCK,b.state(-199).getBlock());assertTrue(b.state(-200).isAir());assertTrue(b.state(-528).isAir());assertNotSame(Blocks.BEDROCK,b.state(-64).getBlock());
            for(int y=-198;y<=-195;y++)assertFalse(b.state(y).isAir(),"no cave opens onto the floor");
            for(int y=-198;y<a.ground()-3;y++){if(b.state(y).isAir())caves++;else solid++;}
        }
        assertTrue(caves>0&&solid>caves*4,solid+" "+caves);
    }
    @Test void aPlanetSavedWithoutAShellKeepsItsBedrockFloorAndAChosenShellIsTheCoreNobodyDigsThrough(){
        var old=rocky(1024,326);assertEquals(NativePlanetGeneration.BEDROCK,old.shell());assertFalse(NativePlanetGeneration.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,old).getOrThrow().getAsJsonObject().has("shell"));
        var chosen=new NativePlanetGeneration("rocky","rocky_craters",1,1.2,.04,.09,1024,326,NativePlanetGeneration.CORE_SHELL);
        assertEquals(chosen,NativePlanetGeneration.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,NativePlanetGeneration.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,chosen).getOrThrow()).getOrThrow());
        assertThrows(IllegalArgumentException.class,()->new NativePlanetGeneration("rocky","rocky_craters",1,1.2,.04,.09,1024,326,"stone"));
        var a=new NativePlanetTerrain(20261002,5,old);var b=new NativePlanetTerrain(20261002,5,chosen);
        for(var f:Face.values())for(int u=70;u<960;u+=223)for(int v=70;v<960;v+=211){var x=a.column(f,u,v);var y=b.column(f,u,v);
            assertSame(Blocks.BEDROCK,x.state(-199).getBlock());assertSame(NativeCore.SHELL_BLOCK,y.state(-199).getBlock());
            for(int h=-198;h<NativePlanetTerrain.MAX_Y;h++)assertSame(x.state(h),y.state(h),"only the floor differs");}
        var shell=NativeCore.shell();assertEquals(-1,shell.getBlock().defaultDestroyTime());assertTrue(shell.getBlock().getExplosionResistance()>=Blocks.BEDROCK.getExplosionResistance());
        assertSame(net.minecraft.world.level.material.PushReaction.BLOCK,shell.getPistonPushReaction());assertEquals("starfold",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(shell.getBlock()).getNamespace());
        for(var tag:java.util.List.of("wither_immune","dragon_immune","features_cannot_replace"))assertTrue(readTag(tag).contains("starfold:core_shell"),tag);
        assertFalse(readTag("mineable/pickaxe").contains("core_shell"));
    }
    static String readTag(String name){try{return java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/data/minecraft/tags/block/"+name+".json"));}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    @Test void oreLayersStretchOverTheDepth(){
        var deep=new NativePlanetTerrain(20261002,5,rocky(1024,326));
        assertEquals(127,deep.layer(127));assertEquals(200,deep.layer(200));assertEquals(-64,deep.layer(-199));assertEquals(0,deep.layer(127-(int)Math.round(127*326/191.0)));
        for(int y=-199;y<127;y++)assertTrue(deep.layer(y)<=deep.layer(y+1));
        int deepslate=0,stone=0,high=0;for(var f:Face.values())for(int u=100;u<1000;u+=97)for(int v=100;v<1000;v+=89){var c=deep.column(f,u,v);
            for(int y=-198;y<c.ground()-8;y++){var block=c.state(y).getBlock();boolean below=deep.layer(y)<0;
                if(block==Blocks.DEEPSLATE){deepslate++;assertTrue(below,"deepslate at "+y);}if(block==Blocks.STONE||block==Blocks.ANDESITE||block==Blocks.BASALT){if(!below)stone++;}
                if(block==Blocks.DEEPSLATE_DIAMOND_ORE){assertTrue(deep.layer(y)<=-16);if(y<-64)high++;}}}
        assertTrue(deepslate>0&&stone>0,deepslate+" "+stone);
    }
    @Test void theExplorationWorldsDimensionReachesItsDeeperPlanetsFloor()throws Exception{
        var data=java.nio.file.Path.of("src/main/resources/data/starfold");var preset=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(data.resolve("worldgen/world_preset/native_exploration.json"))).getAsJsonObject().getAsJsonObject("dimensions").getAsJsonObject("starfold:native_planet");
        assertEquals("starfold:native_deep",preset.get("type").getAsString());var type=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(data.resolve("dimension_type/native_deep.json"))).getAsJsonObject();
        var generator=preset.getAsJsonObject("generator");int a=generator.getAsJsonObject("a").getAsJsonObject("planet_generation").get("depth").getAsInt(),b=generator.getAsJsonObject("b").getAsJsonObject("planet_generation").get("depth").getAsInt();
        // Half of each planet's radius.
        for(var planet:java.util.List.of("a","b"))assertEquals("core_shell",generator.getAsJsonObject(planet).getAsJsonObject("planet_generation").get("shell").getAsString());
        assertEquals(Math.round(new NativePlanetShape(2048).radius()/2),a);assertEquals(Math.round(new NativePlanetShape(1024).radius()/2),b);
        int min=type.get("min_y").getAsInt(),height=type.get("height").getAsInt();assertTrue(min<=127-a&&min>127-a-16&&min%16==0);assertEquals(NativePlanetTerrain.MAX_Y,min+height);assertEquals(height,type.get("logical_height").getAsInt());
        // Worlds made before depths keep the dimension they were made with.
        var old=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(data.resolve("dimension_type/native_ground.json"))).getAsJsonObject();assertEquals(-64,old.get("min_y").getAsInt());assertEquals(384,old.get("height").getAsInt());
        assertFalse(java.nio.file.Files.readString(data.resolve("worldgen/world_preset/native_system.json")).contains("depth"));assertFalse(java.nio.file.Files.readString(data.resolve("worldgen/world_preset/native_system.json")).contains("shell"));
    }
}
