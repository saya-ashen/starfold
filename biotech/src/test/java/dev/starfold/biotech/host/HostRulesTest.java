package dev.starfold.biotech.host;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostRulesTest {
    @Test void closeRangeVentsAndDistantAttacksAlternate() {
        double edge = HostRules.VENT_RADIUS * HostRules.VENT_RADIUS;
        assertEquals(HostRules.GroundAttack.VENT, HostRules.groundAttack(edge, false));
        assertEquals(HostRules.GroundAttack.VENT, HostRules.groundAttack(edge, true));
        assertEquals(HostRules.GroundAttack.ROOTS, HostRules.groundAttack(edge + .01, false));
        assertEquals(HostRules.GroundAttack.SLURRY, HostRules.groundAttack(edge + .01, true));
    }

    @Test void slurryArcReachesTheMarkedPointWithVanillaDragAndGravity() {
        var origin = new Vec3(10, 70 + HostRules.MOUTH_HEIGHT, -20);
        for (var target : new Vec3[]{origin.add(10, -4, 0), origin.add(60, -4, 30), origin.add(20, 8, -30)}) {
            var position = origin;
            var velocity = HostRules.lobVelocity(origin, target);
            double peak = origin.y;
            for (int tick = 0; tick < HostRules.LOB_FLIGHT_TICKS; tick++) {
                position = position.add(velocity);
                velocity = velocity.scale((double) .99f).subtract(0, HostRules.SLURRY_GRAVITY, 0);
                peak = Math.max(peak, position.y);
            }
            assertEquals(0, position.distanceTo(target), 1e-4);
            assertTrue(peak > Math.max(origin.y, target.y) + 2, "readable high arc");
        }
    }

    @Test void sacsOrHalfHealthReleaseTheShell() {
        assertFalse(HostRules.secondPhase(6, 600, 600));
        assertFalse(HostRules.secondPhase(1, 300, 600));
        assertTrue(HostRules.secondPhase(6, 299, 600));
        assertTrue(HostRules.secondPhase(0, 600, 600));
    }

    @Test void onlyGroundWithLivingSacsReducesDamage() {
        assertEquals(2, HostRules.damage(20, true, 6));
        assertEquals(20, HostRules.damage(20, false, 6));
        assertEquals(20, HostRules.damage(20, true, 0));
        assertEquals(0, HostRules.damage(0, true, 1));
    }

    @Test void airSampleClimbsAndCirclesItsSummonPointWithoutAPlayerOrNest() {
        var spawn = new Vec3(1400, 70, -2300);
        var position = spawn;
        var velocity = Vec3.ZERO;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int tick = 1; tick <= 2400; tick++) {
            var destination = HostRules.orbitPoint(spawn, tick);
            assertTrue(destination.y >= spawn.y + 22 * HostRules.SCALE && destination.y <= spawn.y + 34 * HostRules.SCALE);
            assertEquals(Math.min(45 * HostRules.SCALE, HostRules.FLIGHT_RADIUS * .6),
                    destination.subtract(spawn).horizontalDistance(), 1e-9);
            assertTrue(destination.distanceTo(spawn) + 10 * HostRules.SCALE < HostRules.FLIGHT_RADIUS,
                    "orbit leaves clearance for the body");
            velocity = HostRules.flightVelocity(position, velocity, destination, .65 * HostRules.SCALE);
            position = position.add(velocity);
            // Vanilla dry-air drag with NoGravity; this is not a world/collision simulation.
            velocity = velocity.multiply(.91, .98, .91);
            if (tick == 200) assertTrue(position.y > spawn.y + 20 * HostRules.SCALE, "must climb, not skim the ground");
            if (tick > 600) {
                assertTrue(position.y > spawn.y + 20 * HostRules.SCALE);
                assertTrue(position.distanceTo(spawn) < HostRules.FLIGHT_RADIUS);
                minX = Math.min(minX, position.x); maxX = Math.max(maxX, position.x);
                minZ = Math.min(minZ, position.z); maxZ = Math.max(maxZ, position.z);
            }
        }
        assertTrue(maxX - minX > 40 && maxZ - minZ > 40, "orbit moves around both axes");
        assertEquals(Vec3.ZERO, HostRules.flightVelocity(spawn, Vec3.ZERO, spawn, .65 * HostRules.SCALE));
    }

    @Test void spawnHasHorizontalAndVerticalBoundsAndNeverRepeats() {
        double edge = (60 + 48) * (60 + 48);
        assertTrue(HostRules.shouldSpawn(false, false, edge, 96, 60));
        assertTrue(HostRules.shouldSpawn(false, false, 0, -96, 60));
        assertFalse(HostRules.shouldSpawn(false, false, edge + .01, 0, 60));
        assertFalse(HostRules.shouldSpawn(false, false, 0, -96.01, 60));
        assertFalse(HostRules.shouldSpawn(true, false, 0, 0, 60));
        assertFalse(HostRules.shouldSpawn(false, true, 0, 0, 60));
        assertFalse(HostRules.shouldSpawn(false, false, Double.NaN, 0, 60));
        assertFalse(HostRules.shouldSpawn(false, false, 0, Double.NaN, 60));
        assertFalse(HostRules.shouldSpawn(false, false, -1, 0, 60));
    }
}
