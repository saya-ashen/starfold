package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.flight.FlightPermitTest;
import dev.starfold.worldgen.PlanetBiomeFixtures;

import org.junit.jupiter.api.Test;

class AirstairTest {
    @Test
    void clientKeepsDeploymentBetweenPacketBurstsButExpiresSilentServer() {
        long received = 2_000_000_000L;
        assertFalse(Airstair.clientStateExpired(received, received + 550_000_000L));
        assertTrue(Airstair.clientStateExpired(received, received + 1_050_000_000L));
    }

    @Test
    void observerProjectionMovesFlightWithoutMovingItsPlotBlock() {
        var plot = new net.minecraft.core.BlockPos(20481041, -104, 20481036);
        var state = new Airstair.State(plot, 65722, 153.6, 16589, 0, 1, 2.566, .5, 1);
        var hinge = new dev.starfold.geometry.BentStripGeometry.Point(74330, 153.6, 16589);
        var mapped = state.inObserverChart(hinge, 1);
        assertEquals(plot, mapped.pos());
        assertEquals(74330, mapped.x());
        assertEquals(-1, mapped.ox());
        assertEquals(0, mapped.oz());
        assertEquals(state.length(), mapped.length());
        assertEquals(state.treadRun(), mapped.treadRun());
    }

    @Test
    void manualDeploymentAllowsGroundedJitterButRejectsHoverAndMotion() {
        assertTrue(Airstair.canManuallyDeploy(LiftControl.CONTACT, .2));
        assertFalse(Airstair.canManuallyDeploy(LiftControl.CONTACT + .01, .2));
        assertFalse(Airstair.canManuallyDeploy(0, Airstair.EXIT_SPEED + .01));
        assertFalse(Airstair.canManuallyDeploy(0, Double.NaN));
        assertFalse(Airstair.canManuallyDeploy(Double.NaN, 0));
    }

    @Test
    void requiresOneSecondOfQuietAndDoesNotNeedAPilot() {
        int state = 0;
        for (int i = 0; i < 19; i++) state = Airstair.settled(state, true, true, false, .1, 10);
        assertEquals(19, state);
        assertEquals(20, Airstair.settled(state, true, true, false, .1, 10));
    }

    @Test
    void motionAndLiftRetractImmediately() {
        for (int previous : new int[] {0, 19, 20}) {
            assertEquals(0, Airstair.settled(previous, true, true, true, 0, 1));
            assertEquals(0, Airstair.settled(previous, true, true, false, .31, 1));
        }
    }

    @Test
    void loweredStairRidesOutRestingJitterButNotDeparture() {
        double near = Airstair.HOLD_DISTANCE * .9, straight = 1;
        int moving = 0;
        for (int tick = 0; tick < 200; tick++) {
            moving =
                    Airstair.held(
                            moving, true, true, false, tick % 3 == 0 ? .9 : .05, near, straight);
            assertTrue(moving >= 0);
        }
        moving = 0;
        for (int tick = 0; tick < Airstair.HOLD_TICKS - 1; tick++) {
            moving = Airstair.held(moving, true, true, false, 1, near, straight);
            assertTrue(moving > 0);
        }
        assertEquals(-1, Airstair.held(moving, true, true, false, 1, near, straight));
        assertEquals(0, Airstair.held(5, true, true, false, Airstair.EXIT_SPEED, near, straight));
        assertEquals(-1, Airstair.held(0, true, true, true, 0, 0, straight));
        assertEquals(-1, Airstair.held(0, false, true, false, 0, 0, straight));
        assertEquals(-1, Airstair.held(0, true, false, false, 0, 0, straight));
        assertEquals(
                -1, Airstair.held(0, true, true, false, 0, Airstair.HOLD_DISTANCE + .01, straight));
        assertEquals(-1, Airstair.held(0, true, true, false, 0, 0, Math.cos(Math.toRadians(5))));
        assertEquals(0, Airstair.held(0, true, true, false, 0, 0, Math.cos(Math.toRadians(3))));
        assertEquals(-1, Airstair.held(0, true, true, false, Double.NaN, 0, straight));
        assertEquals(-1, Airstair.held(0, true, true, false, 0, Double.NaN, straight));
        assertEquals(-1, Airstair.held(0, true, true, false, 0, 0, Double.NaN));
    }

    @Test
    void hysteresisDoesNotFlapOnSettlingNoise() {
        assertEquals(0, Airstair.settled(19, true, true, false, .2, 1));
        assertEquals(20, Airstair.settled(20, true, true, false, .2, 1));
        assertEquals(0, Airstair.settled(20, true, true, false, .301, 1));
    }

    @Test
    void noSpaceZeroGravityUnknownGroundOrExcessiveReach() {
        for (double reach : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0, -1, 10.001, 14})
            assertEquals(0, Airstair.settled(20, true, true, false, 0, reach));
        assertEquals(0, Airstair.settled(20, false, true, false, 0, 1));
        assertEquals(0, Airstair.settled(20, true, false, false, 0, 1));
        assertEquals(0, Airstair.settled(20, true, true, false, Double.NaN, 1));
    }

    @Test
    void hingeIsAtOuterTopEdgeForEveryFacing() {
        PlanetBiomeFixtures.boot();
        var block =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                        net.minecraft.resources.ResourceLocation.parse("starfold:airstair"));
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var stair =
                    new Airstair.Stair(
                            new net.minecraft.core.BlockPos(10, 20, 30),
                            block.defaultBlockState()
                                    .setValue(
                                            net.minecraft.world.level.block.state.properties
                                                    .BlockStateProperties.HORIZONTAL_FACING,
                                            d));
            var top = Airstair.localTop(stair);
            assertEquals(10.5 + .5 * d.getStepX(), top.x);
            assertEquals(21, top.y);
            assertEquals(30.5 + .5 * d.getStepZ(), top.z);
        }
    }

    @Test
    void registeredResourcesAndIronRecipe() throws Exception {
        PlanetBiomeFixtures.boot();
        var id = net.minecraft.resources.ResourceLocation.parse("starfold:airstair");
        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
        assertInstanceOf(Airstair.StairBlock.class, block);
        assertNotEquals(net.minecraft.world.item.Items.AIR, block.asItem());
        assertFalse(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(
                        net.minecraft.resources.ResourceLocation.parse(
                                "starfold:boarding_ladder")));
        var recipe = FlightPermitTest.json("data/starfold/recipe/airstair.json");
        var ops =
                net.minecraft.resources.RegistryOps.create(
                        com.mojang.serialization.JsonOps.INSTANCE, PlanetBiomeFixtures.lookup);
        assertInstanceOf(
                net.minecraft.world.item.crafting.ShapedRecipe.class,
                net.minecraft.world.item.crafting.Recipe.CODEC.parse(ops, recipe).getOrThrow());
        assertEquals(
                "minecraft:iron_trapdoor",
                recipe.getAsJsonObject("key").getAsJsonObject("T").get("item").getAsString());
        assertEquals(
                "minecraft:iron_ingot",
                recipe.getAsJsonObject("key").getAsJsonObject("I").get("item").getAsString());
        assertNotNull(
                net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC
                        .parse(
                                ops,
                                FlightPermitTest.json(
                                        "data/starfold/loot_table/blocks/airstair.json"))
                        .getOrThrow());
        for (var lang : new String[] {"en_us", "zh_cn"})
            assertTrue(
                    FlightPermitTest.json("assets/starfold/lang/" + lang + ".json")
                            .has("block.starfold.airstair"));
        for (var texture : new String[] {"plate", "panel", "metal", "hazard", "cyan"}) {
            var image =
                    javax.imageio.ImageIO.read(
                            java.nio.file.Path.of(
                                            "src/main/resources/assets/starfold/textures/block/airstair_"
                                                    + texture
                                                    + ".png")
                                    .toFile());
            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
        }
    }

    @Test
    void collisionHookUsesVanillaNotClimbing() throws Exception {
        assertTrue(
                FlightPermitTest.json("starfold.mixins.json")
                        .getAsJsonArray("mixins")
                        .toString()
                        .contains("AirstairCollisionMixin"));
    }
}
