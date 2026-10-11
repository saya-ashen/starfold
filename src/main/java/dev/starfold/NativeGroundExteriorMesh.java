package dev.starfold;

import java.util.ArrayList;
import java.util.List;
import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;

/** Observer-independent derived geometry. Height is displacement, never just surface paint. */
public final class NativeGroundExteriorMesh {
    public interface Heights { double node(Face face,int x,int z); Double edited(Face face,int x,int z); }
    /** The standard size's topology. */
    public static final CubeSurfaceTopology TOPOLOGY=NativePlanetShape.STANDARD.topology();
    public static Point position(Face face,double x,double z,double height){return position(NativePlanetShape.STANDARD,face,x,z,height);}
    public static Point position(NativePlanetShape shape,Face face,double x,double z,double height){return shape.direction(face,x,z).scale(shape.radius()+height);}
    /** This face's quarter of each anomaly spire whose vertex lies on the patch: two lit triangles to the tip. */
    public static List<Point[]> anomalyBeams(Face face,int x0,int z0,int foot){return anomalyBeams(NativePlanetShape.STANDARD,face,x0,z0,foot);}
    public static List<Point[]> anomalyBeams(NativePlanetShape shape,Face face,int x0,int z0,int foot){var out=new ArrayList<Point[]>();
        for(int cx:new int[]{0,shape.size()})for(int cz:new int[]{0,shape.size()}){if(cx<x0||cx>x0+256||cz<z0||cz>z0+256)continue;int w=NativeAnomaly.BEAM_WIDTH,su=cx==0?w:-w,sv=cz==0?w:-w;
            var tip=position(shape,face,cx,cz,foot+NativeAnomaly.BEAM_HEIGHT);var a=position(shape,face,cx+su,cz,foot);var b=position(shape,face,cx+su,cz+sv,foot);var c=position(shape,face,cx,cz+sv,foot);
            out.add(new Point[]{a,b,tip});out.add(new Point[]{b,c,tip});}
        return out;}
    /** Fine boundary vertices lie on coarse chords until an actual edit displaces them.
     * This prevents T-junction gaps between one-block edited tiles and 16-block baseline tiles. */
    public static Point baseline(Heights source,Face face,int x,int z){return baseline(NativePlanetShape.STANDARD,source,face,x,z);}
    public static Point baseline(NativePlanetShape shape,Heights source,Face face,int x,int z){
        int last=shape.size()-16,a=Math.min(last,x/16*16),b=Math.min(last,z/16*16);double u=(x-a)/16.0,v=(z-b)/16.0;
        var p=position(shape,face,a,b,source.node(face,a,b));var q=position(shape,face,a+16,b,source.node(face,a+16,b));
        var r=position(shape,face,a,b+16,source.node(face,a,b+16));var s=position(shape,face,a+16,b+16,source.node(face,a+16,b+16));
        // Same diagonal as the baseline quad's two triangles, not a curved bilinear patch.
        return v>=u?p.scale(1-v).add(r.scale(v-u)).add(s.scale(u)):p.scale(1-u).add(s.scale(v)).add(q.scale(u-v));
    }
    public static Point node(Heights source,Face face,int x,int z){return node(NativePlanetShape.STANDARD,source,face,x,z);}
    public static Point node(NativePlanetShape shape,Heights source,Face face,int x,int z){
        var base=baseline(shape,source,face,x,z);double shift=0;int count=0,half=shape.size()/2;
        var cube=face.normal.scale(half).add(face.u.scale(x-half)).add(face.v.scale(z-half));
        // Enumerate unique incident columns globally: 4 normally, 3 at cube corners.
        for(var f:Face.values())if(cube.dot(f.normal)==half){int u=(int)(half+cube.dot(f.u)),v=(int)(half+cube.dot(f.v));
            for(int dx=-1;dx<=0;dx++)for(int dz=-1;dz<=0;dz++){int a=u+dx,b=v+dz;if(!shape.owned(a,b))continue;count++;var h=source.edited(f,a,b);if(h!=null)shift+=h-baselineHeight(shape,source,f,a+.5,b+.5);}
        }
        return base.add(shape.direction(face,x,z).scale(count==0?0:shift/count));
    }
    public static double baselineHeight(Heights source,Face face,double x,double z){return baselineHeight(NativePlanetShape.STANDARD,source,face,x,z);}
    public static double baselineHeight(NativePlanetShape shape,Heights source,Face face,double x,double z){
        int last=shape.size()-16,a=Math.min(last,(int)x/16*16),b=Math.min(last,(int)z/16*16);double u=(x-a)/16,v=(z-b)/16;
        double p=source.node(face,a,b),q=source.node(face,a+16,b),r=source.node(face,a,b+16),s=source.node(face,a+16,b+16);
        return v>=u?p*(1-v)+r*(v-u)+s*u:p*(1-u)+s*v+q*(u-v);
    }
    /** Cell centre keeps even a one-column mountain or pit instead of averaging it away. */
    public static Point centre(Heights source,Face face,int x,int z){return centre(NativePlanetShape.STANDARD,source,face,x,z);}
    public static Point centre(NativePlanetShape shape,Heights source,Face face,int x,int z){
        var h=source.edited(face,x,z);double height=h==null?baselineHeight(shape,source,face,x+.5,z+.5):h;
        if(h==null)return node(shape,source,face,x,z).add(node(shape,source,face,x+1,z)).add(node(shape,source,face,x,z+1)).add(node(shape,source,face,x+1,z+1)).scale(.25);
        return position(shape,face,x+.5,z+.5,height);
    }
    /** Hysteresis entirely within full curvature; never keeps an exterior in the blend. */
    public static boolean select(boolean previous,boolean eligible,double eyeHeight){return eligible&&eyeHeight>=(previous?GroundOrbit.END:GroundOrbit.END+64);}
    private NativeGroundExteriorMesh(){}
}
