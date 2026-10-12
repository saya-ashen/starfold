package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.flight.FlightPermitTest;
import dev.starfold.worldgen.PlanetEcologyTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.Test;

class ThrusterRendererTest {
    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    @Test
    void boundsIncludeEveryFacingAndItsBoostedFlickerWithoutBeingInfinite() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("starfold:thruster"));
        var renderer = new ThrusterRenderer();
        var pos = new BlockPos(20, 30, 40);
        for (var facing : Direction.values()) {
            var entity =
                    new Thrusters.Thruster(
                            pos,
                            block.defaultBlockState()
                                    .setValue(BlockStateProperties.FACING, facing));
            var n = facing.getNormal();
            var bounds = renderer.getRenderBoundingBox(entity);
            assertEquals(
                    new AABB(pos).expandTowards(n.getX() * 6.5, n.getY() * 6.5, n.getZ() * 6.5),
                    bounds);
            assertTrue(
                    bounds.contains(
                            pos.getX() + .5 + n.getX() * (.375 + 6 * 1.025),
                            pos.getY() + .5 + n.getY() * (.375 + 6 * 1.025),
                            pos.getZ() + .5 + n.getZ() * (.375 + 6 * 1.025)));
            assertTrue(renderer.shouldRenderOffScreen(entity));
            assertEquals(160, renderer.getViewDistance());
        }
    }

    @Test
    void insetOcclusionExceptionIsClientOnlyAndOnlyForThrusters() throws Exception {
        assertTrue(
                ThrusterClient.inset(
                        BuiltInRegistries.BLOCK
                                .get(ResourceLocation.parse("starfold:thruster"))
                                .defaultBlockState()));
        assertFalse(
                ThrusterClient.inset(
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()));
        assertFalse(
                ThrusterClient.inset(
                        BuiltInRegistries.BLOCK
                                .get(ResourceLocation.parse("starfold:booster"))
                                .defaultBlockState()));
        var config = FlightPermitTest.json("starfold.mixins.json");
        assertTrue(
                config.getAsJsonArray("client").asList().stream()
                        .anyMatch(v -> v.getAsString().equals("ThrusterOcclusionMixin")));
        assertFalse(
                config.getAsJsonArray("mixins").asList().stream()
                        .anyMatch(v -> v.getAsString().equals("ThrusterOcclusionMixin")));
    }

    @Test
    void plumeUsesUnlitDepthTestedColourOnlyGeometryAndSparseTipSpray() throws Exception {
        var renderer = Sources.text("ThrusterRenderer");
        assertTrue(renderer.contains("DefaultVertexFormat.POSITION_COLOR"));
        assertTrue(renderer.contains("RENDERTYPE_LIGHTNING_SHADER"));
        assertTrue(renderer.contains("setWriteMaskState(COLOR_WRITE)"));
        assertTrue(renderer.contains("setTransparencyState(TRANSLUCENT_TRANSPARENCY)"));
        assertTrue(renderer.contains("setCullState(NO_CULL)"));
        assertFalse(renderer.contains("NO_DEPTH_TEST"));
        assertTrue(renderer.contains("Math.exp(-elapsed/1.5)"));
        assertTrue(renderer.contains("2.5f+3.5f*spool.boost"));
        var particles = Sources.text("ThrusterClient");
        assertTrue(particles.contains("oldCount/4"));
        assertTrue(particles.contains("level.random.nextInt(4)<oldCount%4"));
        assertTrue(particles.contains("(boosted?6:2.5)*power/15.0"));
    }
}
