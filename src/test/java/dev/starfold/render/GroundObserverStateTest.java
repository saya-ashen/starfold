package dev.starfold.render;

import dev.starfold.chart.GroundAir;
import dev.starfold.ground.ObserverFrame;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundObserverStateTest {
    @Test void resetDropsChartAndCachedStarsAndRestoresControls() {
        var state = new GroundObserverState();
        state.chartFrame = ObserverFrame.identity(0, 3);
        state.repair = false;
        state.heldF8 = true;
        state.clear();
        state.clear();
        assertNull(state.chartFrame);
        assertNull(state.model);
        assertNull(state.sunsOf);
        assertTrue(state.suns.isEmpty());
        assertTrue(state.repair);
        assertFalse(state.heldF8);
        assertSame(GroundAir.DEFAULT, state.air);
        state.chartFrame = ObserverFrame.identity(0, 1);
        assertEquals(0, state.chartFrame.planet());
        assertEquals(1, state.chartFrame.root());
    }
}
