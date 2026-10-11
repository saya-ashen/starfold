package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import java.util.List;

/** Native-only display closure/range; accepted Ground geometry and physics stay untouched. */
public final class NativeGroundTransition {
    /** Vanilla fog hands over to the far terrain here; matches the shader's smoothstep(128,160). */
    public static double fade(double height){return GroundGap.smooth(128,160,height);}
    /** The visible range grows with altitude over a long climb rather than unfolding the whole planet in 32 blocks. */
    public static double extension(double height){return GroundGap.smooth(128,320,height);}
    public static double clip(double height,double oldClip,int view){if(!Double.isFinite(oldClip))return 1e30;double needed=Math.max(oldClip,8*Math.max(0,height)+2*view+32);return oldClip+(needed-oldClip)*extension(height);}
    /** Wall/bridge between two top edges. Without it, unequal heights leave an open strip. */
    public static List<Point> bridge(double x,double z,int dx,int dz,double nearHeight,double farA,double farB){
        if(Math.abs(nearHeight-farA)<1e-6&&Math.abs(nearHeight-farB)<1e-6)return List.of();
        return List.of(new Point(x,nearHeight,z),new Point(x+dx,nearHeight,z+dz),new Point(x+dx,farB,z+dz),new Point(x,farA,z));
    }
    /** Linear source height of the same two baseline triangles; water owns negative heights. */
    public static double generated(NativePlanetHeightCache cache,CubeSurfaceTopology.Face face,double x,double z){
        if(cache==null)return 0;int last=cache.shape().size()-16,a=Math.min(last,(int)x/16*16),b=Math.min(last,(int)z/16*16);double u=(x-a)/16,v=(z-b)/16;
        double p=cache.node(face,a,b),q=cache.node(face,a+16,b),r=cache.node(face,a,b+16),s=cache.node(face,a+16,b+16);
        return cache.surfaceHeight(v>=u?p*(1-v)+r*(v-u)+s*u:p*(1-u)+s*v+q*(u-v));
    }
    private NativeGroundTransition(){}
}
