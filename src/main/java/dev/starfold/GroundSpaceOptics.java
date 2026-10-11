package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import org.joml.Matrix4f;

/** Screen projection only: stereographic rays preserve circular sphere silhouettes.
 * Both planets and the world-oriented stars use the same lens. Ground remains
 * rectilinear; transition is by altitude, never by target or mouse direction. */
public final class GroundSpaceOptics {
    public record Lens(double weight,double x,double y){}
    public static Lens lens(Matrix4f projection,double altitude){
        double sy=projection.m11(),sx=projection.m00();
        double scale=(Math.sqrt(1+sy*sy)+sy)*.5;
        return new Lens(GroundOrbit.weight(altitude),scale*sx/sy,scale);
    }
    /** CPU counterpart of the complete space lens, in physical screen coordinates. */
    public static Point stereo(Point view,double scale){
        double r=view.length(),d=Math.max(1e-6,r-view.z());
        return new Point(2*scale*view.x()/d,2*scale*view.y()/d,r);
    }
    private GroundSpaceOptics(){}
}
