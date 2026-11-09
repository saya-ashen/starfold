package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.ryanhcode.sable.api.physics.mass.MassTracker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;

import org.joml.Matrix3d;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

class FuelMassTest {
    @Test
    void samplesOncePerSecondAndAppliesOnlyChangesIncludingRemovedTanks() {
        var mass = new FuelMass();
        var deltas = new HashMap<BlockPos, Double>();
        var a = BlockPos.ZERO;
        var b = new BlockPos(4, 0, 0);
        var atA = Map.of(a, 6.2);
        assertTrue(mass.due(100));
        mass.update(100, atA, deltas::put);
        assertEquals(atA, deltas);
        assertEquals(6.2, mass.total());
        deltas.clear();
        for (long tick = 100; tick < 120; tick++) {
            assertFalse(mass.due(tick));
            mass.update(tick, Map.of(), deltas::put);
        }
        assertTrue(deltas.isEmpty());
        assertEquals(6.2, mass.total());
        mass.update(120, Map.of(a, 3.1, b, 2.0), deltas::put);
        assertEquals(Map.of(a, -3.1, b, 2.0), deltas);
        deltas.clear();
        mass.update(140, Map.of(a, 3.1, b, 2.0), deltas::put);
        assertTrue(deltas.isEmpty());
        // Removed tanks / removed fuel definitions subtract previous mass at its old location.
        mass.update(160, Map.of(), deltas::put);
        assertEquals(Map.of(a, -3.1, b, -2.0), deltas);
        assertEquals(0, mass.total());
    }

    @Test
    void nativeIncrementUpdatesMassComAndInertiaAndRemovalRestoresTheHull() {
        var tracker = new MassTracker();
        var level = EmptyBlockGetter.INSTANCE;
        var cube = Blocks.STONE.defaultBlockState();
        tracker.addBlockMass(level, cube, BlockPos.ZERO, 10, null);
        var dryInertia = new Matrix3d(tracker.getInertiaTensor());
        var fuel = new BlockPos(4, 0, 0);
        tracker.addBlockMass(level, cube, fuel, 10, null);
        assertEquals(20, tracker.getMass());
        assertEquals(2.5, tracker.getCenterOfMass().x(), 1e-12);
        assertEquals(.5, tracker.getCenterOfMass().y(), 1e-12);
        assertEquals(20.0 / 6, tracker.getInertiaTensor().m00(), 1e-12);
        assertEquals(20.0 / 6 + 80, tracker.getInertiaTensor().m11(), 1e-12);
        tracker.addBlockMass(level, cube, fuel, -10, null);
        assertEquals(10, tracker.getMass());
        assertEquals(.5, tracker.getCenterOfMass().x(), 1e-12);
        assertTrue(dryInertia.equals(tracker.getInertiaTensor(), 1e-10));
    }
}
