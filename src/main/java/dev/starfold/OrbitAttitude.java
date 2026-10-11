package dev.starfold;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/** Navigation axes: +Z forward, +X screen-left, +Y screen-up. No Euler pitch clamp. */
public final class OrbitAttitude {
    private OrbitAttitude() {}
    public static Quaterniond upright(double yaw, double pitch) {
        return new Quaterniond().rotationY(Math.toRadians(-yaw)).rotateX(Math.toRadians(pitch));
    }
    public static Quaterniond turn(Quaterniondc attitude, double yaw, double pitch) {
        return new Quaterniond(attitude).rotateY(Math.toRadians(-yaw)).rotateX(Math.toRadians(pitch)).normalize();
    }
    /** Apply input about the displayed axes, including during the near-ground roll blend. */
    public static Quaterniond turnInView(Quaterniondc attitude, Quaterniondc view, double yaw, double pitch) {
        var delta=turn(new Quaterniond(),yaw,pitch);
        return new Quaterniond(view).mul(delta).mul(new Quaterniond(view).conjugate()).mul(attitude).normalize();
    }
    /** Local radial-frame yaw, not camera-up yaw. Left multiplication preserves the
     * forward ray's radial component, even when pitched, inverted or rolled.
     * Pitch remains an unrestricted rotation about the camera's own left axis. */
    public static Quaterniond turnRadial(Quaterniondc local, double yaw, double pitch) {
        return new Quaterniond().rotationY(Math.toRadians(-yaw)).mul(local)
                .rotateX(Math.toRadians(pitch)).normalize();
    }
    public static Vector3d direction(Quaterniondc q, double forward, double left, double up) {
        var v=new Vector3d(left,up,forward);
        if(v.lengthSquared()>1) v.normalize();
        return q.transform(v);
    }
    public static double yaw(Quaterniondc q, double fallback) {
        var v=q.transform(new Vector3d(0,0,1));
        return v.x*v.x+v.z*v.z<1e-12?fallback:Math.toDegrees(Math.atan2(-v.x,v.z));
    }
    public static double pitch(Quaterniondc q) {
        return -Math.toDegrees(Math.asin(Math.clamp(q.transform(new Vector3d(0,0,1)).y,-1,1)));
    }
    /** Near ground, remove roll without changing the forward ray. */
    public static Quaterniond landing(Quaterniondc local, double freedom, double fallbackYaw) {
        var upright=upright(yaw(local,fallbackYaw),pitch(local));
        return upright.slerp(local,Math.clamp(freedom,0,1)).normalize();
    }
}
