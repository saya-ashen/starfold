package dev.starfold;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeAirstairTest {
    @Test void requiresOneSecondOfQuietAndDoesNotNeedAPilot(){int state=0;for(int i=0;i<19;i++)state=NativeAirstair.settled(state,true,true,false,.1,10);assertEquals(19,state);assertEquals(20,NativeAirstair.settled(state,true,true,false,.1,10));}
    @Test void motionAndLiftRetractImmediately(){for(int previous:new int[]{0,19,20}){assertEquals(0,NativeAirstair.settled(previous,true,true,true,0,1));assertEquals(0,NativeAirstair.settled(previous,true,true,false,.31,1));}}
    @Test void loweredStairRidesOutRestingJitterButNotDeparture(){
        double near=NativeAirstair.HOLD_DISTANCE*.9,straight=1;
        int moving=0;for(int tick=0;tick<200;tick++){moving=NativeAirstair.held(moving,true,true,false,tick%3==0?.9:.05,near,straight);assertTrue(moving>=0);}
        moving=0;for(int tick=0;tick<NativeAirstair.HOLD_TICKS-1;tick++){moving=NativeAirstair.held(moving,true,true,false,1,near,straight);assertTrue(moving>0);}assertEquals(-1,NativeAirstair.held(moving,true,true,false,1,near,straight));
        assertEquals(0,NativeAirstair.held(5,true,true,false,NativeAirstair.EXIT_SPEED,near,straight));
        assertEquals(-1,NativeAirstair.held(0,true,true,true,0,0,straight));assertEquals(-1,NativeAirstair.held(0,false,true,false,0,0,straight));assertEquals(-1,NativeAirstair.held(0,true,false,false,0,0,straight));
        assertEquals(-1,NativeAirstair.held(0,true,true,false,0,NativeAirstair.HOLD_DISTANCE+.01,straight));assertEquals(-1,NativeAirstair.held(0,true,true,false,0,0,Math.cos(Math.toRadians(5))));assertEquals(0,NativeAirstair.held(0,true,true,false,0,0,Math.cos(Math.toRadians(3))));
        assertEquals(-1,NativeAirstair.held(0,true,true,false,Double.NaN,0,straight));assertEquals(-1,NativeAirstair.held(0,true,true,false,0,Double.NaN,straight));assertEquals(-1,NativeAirstair.held(0,true,true,false,0,0,Double.NaN));
    }
    @Test void hysteresisDoesNotFlapOnSettlingNoise(){assertEquals(0,NativeAirstair.settled(19,true,true,false,.2,1));assertEquals(20,NativeAirstair.settled(20,true,true,false,.2,1));assertEquals(0,NativeAirstair.settled(20,true,true,false,.301,1));}
    @Test void noSpaceZeroGravityUnknownGroundOrExcessiveReach(){for(double reach:new double[]{Double.NaN,Double.POSITIVE_INFINITY,0,-1,10.001,14})assertEquals(0,NativeAirstair.settled(20,true,true,false,0,reach));assertEquals(0,NativeAirstair.settled(20,false,true,false,0,1));assertEquals(0,NativeAirstair.settled(20,true,false,false,0,1));assertEquals(0,NativeAirstair.settled(20,true,true,false,Double.NaN,1));}
    @Test void hingeIsAtOuterTopEdgeForEveryFacing(){NativePlanetEcologyTest.boot();var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("starfold:airstair"));for(var d:net.minecraft.core.Direction.Plane.HORIZONTAL){var stair=new NativeAirstair.Stair(new net.minecraft.core.BlockPos(10,20,30),block.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,d));var top=NativeAirstair.localTop(stair);assertEquals(10.5+.5*d.getStepX(),top.x);assertEquals(21,top.y);assertEquals(30.5+.5*d.getStepZ(),top.z);}}
    @Test void registeredResourcesAndIronRecipe()throws Exception{
        NativePlanetEcologyTest.boot();var id=net.minecraft.resources.ResourceLocation.parse("starfold:airstair");var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);assertInstanceOf(NativeAirstair.StairBlock.class,block);assertNotEquals(net.minecraft.world.item.Items.AIR,block.asItem());
        assertFalse(net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.ResourceLocation.parse("starfold:boarding_ladder")));
        var recipe=NativeFlightPermitTest.json("data/starfold/recipe/airstair.json");var ops=net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,NativePlanetEcologyTest.lookup);assertInstanceOf(net.minecraft.world.item.crafting.ShapedRecipe.class,net.minecraft.world.item.crafting.Recipe.CODEC.parse(ops,recipe).getOrThrow());
        assertEquals("minecraft:iron_trapdoor",recipe.getAsJsonObject("key").getAsJsonObject("T").get("item").getAsString());assertEquals("minecraft:iron_ingot",recipe.getAsJsonObject("key").getAsJsonObject("I").get("item").getAsString());
        assertNotNull(net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC.parse(ops,NativeFlightPermitTest.json("data/starfold/loot_table/blocks/airstair.json")).getOrThrow());
        for(var lang:new String[]{"en_us","zh_cn"})assertTrue(NativeFlightPermitTest.json("assets/starfold/lang/"+lang+".json").has("block.starfold.airstair"));
        for(var texture:new String[]{"plate","panel","metal","hazard","cyan"}){var image=javax.imageio.ImageIO.read(java.nio.file.Path.of("src/main/resources/assets/starfold/textures/block/airstair_"+texture+".png").toFile());assertEquals(16,image.getWidth());assertEquals(16,image.getHeight());}
    }
    @Test void collisionHookUsesVanillaNotClimbing()throws Exception{var source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/mixin/NativeAirstairCollisionMixin.java"));assertTrue(source.contains("getEntityCollisions"));assertFalse(source.contains("onClimbable"));assertTrue(NativeFlightPermitTest.json("starfold.mixins.json").getAsJsonArray("mixins").toString().contains("NativeAirstairCollisionMixin"));}
}
