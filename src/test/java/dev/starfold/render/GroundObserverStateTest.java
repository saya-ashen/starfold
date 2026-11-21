package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundAir;
import dev.starfold.ground.ObserverFrame;

import org.junit.jupiter.api.Test;

class GroundObserverStateTest {
    @Test
    void resetDropsChartAndCachedStarsAndRestoresControls() {
        var state = new GroundObserverState();
        state.chartFrame = ObserverFrame.identity(0, 3);
        state.clear();
        state.clear();
        assertNull(state.chartFrame);
        assertNull(state.model);
        assertNull(state.sunsOf);
        assertTrue(state.suns.isEmpty());
        assertSame(GroundAir.DEFAULT, state.air);
        state.chartFrame = ObserverFrame.identity(0, 1);
        assertEquals(0, state.chartFrame.planet());
        assertEquals(1, state.chartFrame.root());
    }
}
