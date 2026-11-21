package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.ryanhcode.sable.companion.math.Pose3d;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class PilotPoseTest {
    @Test
    void carriedFeetAndRoofMustUseTheSamePose() {
        for (double displacement : new double[] {-2, -1.5, 0, 1.5, 2}) {
            var before = new Pose3d();
            before.position().set(30, 100, 60);
            var now = new Pose3d(before);
            now.position().add(0, displacement, 0);
            var local = new Vector3d(1, 1, 1);
            var carried = now.transformPosition(new Vector3d(local));
            var correct = now.transformPositionInverse(new Vector3d(carried));
            var stale = before.transformPositionInverse(new Vector3d(carried));
            assertEquals(1, correct.y, 1e-9);
            assertEquals(1 + displacement, stale.y, 1e-9);
            assertTrue(correct.y + 1.8 < 4);
            if (displacement >= 1.5) assertTrue(stale.y + 1.8 > 4);
        }
    }

    @Test
    void poseFitScopeCannotLeakToMovementOrOtherPlayers() throws Exception {
        for (var name : new String[] {"HelmFitMixin", "HelmCollisionPoseMixin"}) {
            String mixin = "dev.starfold.mixin." + name;
            assertFalse(RendererCompatibility.apply(mixin, id -> false));
            assertTrue(RendererCompatibility.apply(mixin, id -> id.equals("sable")));
            assertTrue(
                    RendererCompatibility.apply(
                            mixin, id -> id.equals("sable") || id.equals("simulated")));
        }
    }
}
