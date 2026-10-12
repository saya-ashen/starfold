package dev.starfold.chart;

import org.joml.Quaterniond;

/** Local chart body state shared by Native camera and orbital projection. */
public final class GroundMotion {
    public record State(
            GroundSpace.Pose pose,
            double vertical,
            boolean grounded,
            boolean flying,
            boolean jumpHeld,
            boolean cut,
            double qx,
            double qy,
            double qz,
            double qw) {
        public State {
            double n = qx * qx + qy * qy + qz * qz + qw * qw;
            if (pose == null
                    || !Double.isFinite(vertical)
                    || Math.abs(vertical) > 3
                    || !Double.isFinite(n)
                    || Math.abs(n - 1) > .001) throw new IllegalArgumentException("Body");
        }

        public State(
                GroundSpace.Pose p,
                double v,
                boolean grounded,
                boolean flying,
                boolean jump,
                boolean cut,
                Quaterniond q) {
            this(p, v, grounded, flying, jump, cut, q.x, q.y, q.z, q.w);
        }
    }

    private GroundMotion() {}
}
