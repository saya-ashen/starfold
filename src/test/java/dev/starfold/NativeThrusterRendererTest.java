package dev.starfold;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeThrusterRendererTest {
    @org.junit.jupiter.api.BeforeAll static void boot(){NativePlanetEcologyTest.boot();}
    @Test void boundsIncludeEveryFacingAndItsBoostedFlickerWithoutBeingInfinite(){
        var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("starfold:thruster"));var renderer=new NativeThrusterRenderer();var pos=new BlockPos(20,30,40);
        for(var facing:Direction.values()){
            var entity=new NativeThruster.Thruster(pos,block.defaultBlockState().setValue(BlockStateProperties.FACING,facing));var n=facing.getNormal();var bounds=renderer.getRenderBoundingBox(entity);
            assertEquals(new AABB(pos).expandTowards(n.getX()*6.5,n.getY()*6.5,n.getZ()*6.5),bounds);
            assertTrue(bounds.contains(pos.getX()+.5+n.getX()*(.375+6*1.025),pos.getY()+.5+n.getY()*(.375+6*1.025),pos.getZ()+.5+n.getZ()*(.375+6*1.025)));
            assertTrue(renderer.shouldRenderOffScreen(entity));assertEquals(160,renderer.getViewDistance());
        }
    }
    @Test void insetOcclusionExceptionIsClientOnlyAndOnlyForThrusters()throws Exception{
        assertTrue(NativeThrusterClient.inset(BuiltInRegistries.BLOCK.get(ResourceLocation.parse("starfold:thruster")).defaultBlockState()));assertFalse(NativeThrusterClient.inset(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()));assertFalse(NativeThrusterClient.inset(BuiltInRegistries.BLOCK.get(ResourceLocation.parse("starfold:booster")).defaultBlockState()));
        var config=NativeFlightPermitTest.json("starfold.mixins.json");assertTrue(config.getAsJsonArray("client").asList().stream().anyMatch(v->v.getAsString().equals("NativeThrusterOcclusionMixin")));assertFalse(config.getAsJsonArray("mixins").asList().stream().anyMatch(v->v.getAsString().equals("NativeThrusterOcclusionMixin")));
    }
    @Test void plumeUsesUnlitDepthTestedColourOnlyGeometryAndSparseTipSpray()throws Exception{
        var renderer=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/NativeThrusterRenderer.java"));
        assertTrue(renderer.contains("DefaultVertexFormat.POSITION_COLOR"));assertTrue(renderer.contains("RENDERTYPE_LIGHTNING_SHADER"));assertTrue(renderer.contains("setWriteMaskState(COLOR_WRITE)"));assertTrue(renderer.contains("setTransparencyState(TRANSLUCENT_TRANSPARENCY)"));assertTrue(renderer.contains("setCullState(NO_CULL)"));assertFalse(renderer.contains("NO_DEPTH_TEST"));assertTrue(renderer.contains("Math.exp(-elapsed/1.5)"));assertTrue(renderer.contains("2.5f+3.5f*spool.boost"));
        var particles=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/NativeThrusterClient.java"));assertTrue(particles.contains("oldCount/4"));assertTrue(particles.contains("level.random.nextInt(4)<oldCount%4"));assertTrue(particles.contains("(boosted?6:2.5)*power/15.0"));
    }
}
