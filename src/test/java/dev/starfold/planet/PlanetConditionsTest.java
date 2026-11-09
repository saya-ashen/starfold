package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.PlanetShape;
import dev.starfold.sky.Space;
import dev.starfold.worldgen.PlanetAtmosphere;
import dev.starfold.worldgen.PlanetBiomeFixtures;
import dev.starfold.worldgen.PlanetEnvironment;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlanetConditionsTest {
    @BeforeAll
    static void boot() {
        PlanetBiomeFixtures.boot();
    }

    @Test
    void storageLookupSelectsEachPlanetAndNeverGuessesInSpaceOrGaps() {
        PlanetShape.reset();
        var small = new PlanetEnvironment(PlanetAtmosphere.NONE, .35, 0);
        var large = new PlanetEnvironment(PlanetAtmosphere.EARTH, 1.4, 0);
        java.util.function.IntFunction<PlanetEnvironment> table = id -> id == 0 ? small : large;
        assertSame(small, PlanetConditions.at(new BlockPos(1024, 128, 1024), table));
        assertSame(large, PlanetConditions.at(new BlockPos(32768 + 1024, 128, 1024), table));
        assertNull(PlanetConditions.at(BlockPos.containing(Space.X, 128, Space.Z), table));
        assertNull(PlanetConditions.at(new BlockPos(-100000, 128, -100000), table));
        assertEquals(1, PlanetConditions.gravity((PlanetEnvironment) null));
    }

    @Test
    void observedProfileFollowsRotatedTranslatedCoordinatesWithoutLeakingToOtherPlanets() {
        PlanetShape.reset();
        var airless = new PlanetEnvironment(PlanetAtmosphere.NONE, .35, 0);
        var home = new BlockPos(1024, 128, 1024);
        var other = home.offset(32768, 0, 0);
        var gap = new BlockPos(-100000, 128, -100000);
        var space = BlockPos.containing(Space.X, 128, Space.Z);
        for (int turns = 0; turns < 4; turns++) {
            var frame = new dev.starfold.ground.ObserverFrame(0, 0, turns, 65536, -8192);
            for (var storage : new BlockPos[] {home, home.offset(-1100, 0, 0)}) {
                var plane = frame.toClient(storage);
                assertSame(
                        airless,
                        PlanetConditions.observedAt(
                                frame.toStorage(plane), frame.planet(), airless));
            }
            for (var storage : new BlockPos[] {other, gap, space})
                assertNull(
                        PlanetConditions.observedAt(
                                frame.toStorage(frame.toClient(storage)), frame.planet(), airless));
        }
        assertNull(PlanetConditions.observedAt(home, 0, null));
        assertSame(airless, PlanetConditions.observedAt(other, 1, airless));
    }

    @Test
    void fallEnergyScalesDistanceAndSafeThresholdTogether() {
        for (double g : new double[] {.35, 1, 1.4, 3}) {
            double safe = 3 * PlanetConditions.safeFallScale(g);
            assertEquals(0, (safe - safe) * PlanetConditions.fallScale(g));
            assertEquals(10 * g - 3, (10 - safe) * PlanetConditions.fallScale(g), 1e-10);
            // A normal jump rises inversely with gravity and still lands safely.
            assertTrue(1.3 / g < safe);
        }
    }

    @Test
    void transientModifiersAreIdempotentAndRestoreEquipmentAcrossPlanetSpacePlanet() {
        var gravity = new AttributeInstance(Attributes.GRAVITY, unused -> {});
        gravity.setBaseValue(.08);
        var equipment =
                new AttributeModifier(
                        ResourceLocation.parse("starfold:test_equipment"),
                        .25,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        gravity.addTransientModifier(equipment);
        for (double factor : new double[] {.35, .35, 1, 1.4, 1, .35}) {
            PlanetGravity.update(gravity, factor);
            assertEquals(.08 * 1.25 * factor, gravity.getValue(), 1e-10);
            assertEquals(factor == 1 ? 1 : 2, gravity.getModifiers().size());
            assertSame(equipment, gravity.getModifier(equipment.id()));
        }
        var safe = new AttributeInstance(Attributes.SAFE_FALL_DISTANCE, unused -> {});
        safe.setBaseValue(3);
        PlanetGravity.update(safe, PlanetConditions.safeFallScale(.35));
        assertEquals(3 / .35, safe.getValue(), 1e-10);
        PlanetGravity.update(safe, 1);
        assertEquals(3, safe.getValue());
        var damage = new AttributeInstance(Attributes.FALL_DAMAGE_MULTIPLIER, unused -> {});
        damage.setBaseValue(1);
        for (double factor : new double[] {.35, 1, 1.4, 1, .35}) {
            PlanetGravity.update(safe, PlanetConditions.safeFallScale(factor));
            PlanetGravity.update(damage, PlanetConditions.fallScale(factor));
            assertEquals(3 / factor, safe.getValue(), 1e-10);
            assertEquals(factor, damage.getValue(), 1e-10);
            assertEquals(factor == 1 ? 0 : 1, safe.getModifiers().size());
            assertEquals(factor == 1 ? 0 : 1, damage.getModifiers().size());
            assertFalse(safe.save().contains("modifiers"));
            assertFalse(damage.save().contains("modifiers"));
        }
        PlanetGravity.update(null, .35);
    }

    @Test
    void heavyPlanetsStillClearOneBlockAndLightPlanetsJumpHigher() {
        for (double g : new double[] {.35, 1, 1.4}) {
            double y = 0, v = .42 * PlanetConditions.jumpScale(g), top = 0;
            for (int tick = 0; tick < 80; tick++) {
                y += v;
                top = Math.max(top, y);
                v = (v - .08 * g) * .98;
            }
            assertTrue(top > 1.2, "jump height " + top + " at gravity " + g);
            if (g < 1) assertTrue(top > 2);
        }
    }

    @Test
    void savedFireAndCampfiresAreExtinguishedWithoutChangingLava() {
        var fire = net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState();
        var soul = net.minecraft.world.level.block.Blocks.SOUL_FIRE.defaultBlockState();
        var camp = net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState();
        var lava = net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState();
        assertTrue(PlanetFire.fire(fire));
        assertTrue(PlanetFire.fire(soul));
        assertFalse(PlanetFire.fire(camp));
        assertFalse(PlanetFire.fire(lava));
        assertTrue(PlanetFire.burning(fire));
        assertTrue(PlanetFire.burning(soul));
        assertTrue(PlanetFire.burning(camp));
        assertTrue(PlanetFire.extinguished(fire).isAir());
        assertTrue(PlanetFire.extinguished(soul).isAir());
        assertFalse(PlanetFire.burning(PlanetFire.extinguished(camp)));
        assertSame(camp.getBlock(), PlanetFire.extinguished(camp).getBlock());
        assertFalse(PlanetFire.burning(lava));
    }

    @Test
    void mufflingLeavesUiMusicRecordsAndVoiceUntouched() {
        for (var source : net.minecraft.sounds.SoundSource.values()) {
            boolean world =
                    switch (source) {
                        case BLOCKS, HOSTILE, NEUTRAL, WEATHER, AMBIENT, PLAYERS -> true;
                        default -> false;
                    };
            assertEquals(world, PlanetSoundClient.world(source), source.name());
        }
    }

    @Test
    void fireThresholdDoesNotDependOnBreathabilityAndThinAirIsContinuousForSound() {
        assertFalse(PlanetConditions.canBurn(0));
        assertFalse(PlanetConditions.canBurn(PlanetConditions.AIRLESS_DENSITY));
        assertTrue(PlanetConditions.canBurn(Math.nextUp(PlanetConditions.AIRLESS_DENSITY)));
        assertTrue(PlanetConditions.canBurn(.1));
        assertEquals(.12, PlanetConditions.soundScale(0, false));
        assertEquals(.45, PlanetConditions.soundScale(0, true));
        assertEquals(.56, PlanetConditions.soundScale(.5, false), 1e-10);
        assertEquals(1, PlanetConditions.soundScale(1, false));
        assertEquals(1, PlanetConditions.soundScale(4, true));
        assertEquals(.12, PlanetConditions.soundScale(-1, false));
    }
}
