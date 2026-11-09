package dev.starfold.render;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GroundViewStateTest {
    @Test void sameObservationRetainsDataWhilePlanetWorldAndSessionChangesAreDistinct() {
        var state = new GroundViewState<Object>();
        var world = new Object();
        var shape = dev.starfold.chart.PlanetShape.STANDARD;
        state.bind(world, 1, 0, shape);
        state.changed.set(7);
        assertTrue(state.matches(world, 1, 0, shape));
        assertTrue(state.changed.get(7));
        assertFalse(state.matches(world, 2, 0, shape));
        assertTrue(state.sameWorld(world, 0, shape), "chart changes preserve the renderer");
        assertFalse(state.sameWorld(world, 1, shape));
        assertFalse(state.sameWorld(new Object(), 0, shape));
        assertFalse(state.sameWorld(world, 0, new dev.starfold.chart.PlanetShape(1024)));
        state.clear();
        state.bind(world, 2, 1, shape);
        assertFalse(state.matches(world, 1, 0, shape), "old completion cannot match B");
        state.clear();
        state.bind(world, 3, 0, shape);
        assertFalse(state.matches(world, 1, 0, shape), "returning to A cannot revive its old session");
        assertTrue(state.matches(world, 3, 0, shape));
        assertTrue(state.changed.isEmpty());
        state.clear();
        state.clear();
        assertNull(state.level);
        assertEquals(-1, state.planet);
        assertFalse(state.matches(world, 3, 0, shape));
    }

    @Test void revisionsTombstonesAndResetBelongToOneObservation() {
        var state = new GroundViewState<Object>();
        var marked = new BitSet();
        marked.set(0);
        assertFalse(state.coverage(marked, 1));
        var runs = new GroundSurface.Run[256][];
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Arrays.setAll(runs, i -> new GroundSurface.Run[] {new GroundSurface.Run(128, 128,
                net.minecraft.world.level.block.Block.getId(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()))});
        var surface = new GroundSurface(2, runs);
        assertTrue(state.surface(0, 2, surface));
        assertSame(surface, state.overlays.get(0L));
        assertFalse(state.surface(0, 1, null));
        assertSame(surface, state.surfaces.get(0L));
        assertTrue(state.coverage(new BitSet(), 3));
        assertNull(state.overlays.get(0L));
        assertNull(state.surfaces.get(0L));
        assertFalse(state.surface(0, 2, surface));
        assertFalse(state.invalidated.drain().isEmpty());
        state.clear();
        state.clear();
        assertTrue(state.changed.isEmpty());
        assertTrue(state.surfaceRevisions.isEmpty());
        assertTrue(state.invalidated.drain().isEmpty());
        assertTrue(state.surface(0, 2, surface), "a new observation has its own revision clock");
    }
}
