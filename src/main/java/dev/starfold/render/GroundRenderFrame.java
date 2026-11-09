package dev.starfold.render;

import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import org.joml.Vector3f;

/** Frame and current-pass values, owned by the ground client and cleared at world teardown. */
final class GroundRenderFrame {
    void clear() {
        active = false;
        frameHome = null;
        frameStar = null;
        framePose = null;
        frameEye = frameUniverseEye = null;
        frameGap = null;
        frameOrbit = null;
    }

     int source = -1;
     boolean derived, coarse, cover, stitch;
     long lodKey = -1;
     float lodFade = 1, patchFade = 1;

     CoverageBinding coverageBinding;
     Point viewOffset = new Point(0, 0, 0);
     float partial;
     boolean active;
     GroundSpace.Pose framePose;
     Point frameEye, frameUniverseEye;
     GroundEphemeris.Frame frameHome;
     GroundEphemeris.Star frameStar;
     PlanetGap frameGap;
     PlanetOrbit frameOrbit;
    final Vector3f X = new Vector3f(), Y = new Vector3f(), Z = new Vector3f();

}
