package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Second, higher display stage. The accepted GroundAir algorithm is deliberately untouched.
 * A variable-curvature stereographic inverse joins its plane to a complete six-face sphere.
 * Only derived positions change; all faces, edits and masks retain their GroundSpace keys.
 * CPU counterpart of ground_orbit.vsh. Keep flight policy separate in GroundFlight. */
public final class GroundOrbit {
    public static final double START=160,END=1024,RADIUS=GroundAir.RADIUS,LIMIT=32768;
    public final GroundSpace.Pose pose;
    public final Point eye,normal,basisU,basisV,offset;
    public final Frame observerFrame;
    public final double weight,clip,minimumFacing;
    public record Frame(Point u,Point up,Point v) {
        public Point world(Point p){return u.scale(p.x()).add(up.scale(p.y())).add(v.scale(p.z()));}
        public Point local(Point p){return new Point(p.dot(u),p.dot(up),p.dot(v));}
        public Quaterniond rotation(){return new Quaterniond().setFromNormalized(new Matrix3d().setColumn(0,vec(u)).setColumn(1,vec(up)).setColumn(2,vec(v)));}
    }
    private static Vector3d vec(Point p){return new Vector3d(p.x(),p.y(),p.z());}
    public static double weight(double eyeHeight){return GroundGap.smooth(START,END,eyeHeight);}
    public static Point direction(Face f,double u,double v){
        if(u<0||u>GroundSpace.SIZE||v<0||v>GroundSpace.SIZE){var p=GroundSpace.canonical(f,new Point(u,0,v)).pose();f=p.face();u=p.point().x();v=p.point().z();}
        return PlanetSpace.unit(f.normal.scale(GroundAir.HALF).add(f.u.scale(u-GroundAir.HALF)).add(f.v.scale(v-GroundAir.HALF)));
    }
    private static Point global(Face f,Point p){return f.u.scale(p.x()).add(f.normal.scale(p.y())).add(f.v.scale(p.z()));}
    public static Point position(GroundSpace.Pose p){return direction(p.face(),p.point().x(),p.point().z()).scale(RADIUS+p.point().y());}
    public static GroundSpace.Pose locate(Point p){
        if(p==null||!p.finite()||p.length()<RADIUS+GroundSpace.MIN||p.length()>RADIUS+LIMIT)throw new IllegalArgumentException("Outside flight shell");
        Face f=Face.TOP;for(var a:Face.values())if(p.dot(a.normal)>p.dot(f.normal))f=a;
        double scale=GroundAir.HALF/p.dot(f.normal);
        return new GroundSpace.Pose(f,new Point(Math.clamp(GroundAir.HALF+p.dot(f.u)*scale,0,Math.nextDown((double)GroundSpace.SIZE)),p.length()-RADIUS,Math.clamp(GroundAir.HALF+p.dot(f.v)*scale,0,Math.nextDown((double)GroundSpace.SIZE))));
    }
    public static Frame frame(GroundSpace.Pose pose){
        var air=new GroundAir(pose.face(),pose.point(),GroundAir.DEFAULT,true);
        return frame(pose.face(),air);
    }
    private static Frame frame(Face face,GroundAir air){
        var n=global(face,air.normal);var a=global(face,air.basisU);var b=global(face,air.basisV);
        // Polar orthogonalization treats the two axes symmetrically across rotated seams.
        var u=PlanetSpace.unit(a.add(n.cross(b)));return new Frame(u,n,u.cross(n));
    }
    public GroundOrbit(GroundSpace.Pose pose,Point eye,int view){
        this.pose=pose;this.eye=eye;weight=weight(eye.y());
        var air=new GroundAir(pose.face(),eye,GroundAir.DEFAULT,true);var f=frame(pose.face(),air);observerFrame=f;normal=f.up;
        basisU=global(pose.face(),air.basisU).scale(1-weight).add(f.u.scale(weight));
        basisV=global(pose.face(),air.basisV).scale(1-weight).add(f.v.scale(weight));
        clip=weight==1?Double.POSITIVE_INFINITY:view+24+4*RADIUS*weight;
        // The old planar pole starts outside its permitted view disk. As curvature grows
        // the back hemisphere enters behind the opaque planet, never as a second image.
        minimumFacing=-GroundGap.smooth(.1,.5,weight);
        offset=new Point(eye.x(),0,eye.z()).sub(grid(pose.face(),new Point(eye.x(),0,eye.z())));
    }
    public Point raw(Face face,Point p){
        var s=direction(face,p.x(),p.z());double c=s.dot(normal),k=weight;
        double d=Math.max(1e-9,1+c+k*k*(1-c));
        var tangent=s.sub(normal.scale(c));double scale=2*(RADIUS+k*p.y())/d;
        return new Point(eye.x()+tangent.dot(basisU)*scale,p.y()*(1-2*k*k*(1-c)/d)-2*RADIUS*k*(1-c)/d,eye.z()+tangent.dot(basisV)*scale);
    }
    private Point grid(Face f,Point p){
        double x=Math.floor(p.x()/16)*16,z=Math.floor(p.z()/16)*16,u=(p.x()-x)/16,v=(p.z()-z)/16;
        var a=raw(f,new Point(x,p.y(),z));var b=raw(f,new Point(x+16,p.y(),z));var c=raw(f,new Point(x,p.y(),z+16));var d=raw(f,new Point(x+16,p.y(),z+16));
        return a.scale((1-u)*(1-v)).add(b.scale(u*(1-v))).add(c.scale((1-u)*v)).add(d.scale(u*v));
    }
    public Point point(Face f,Point p){return grid(f,p).add(offset);}
    public boolean visible(Face f,Point p){var q=point(f,p);return direction(f,p.x(),p.z()).dot(normal)>=minimumFacing&&Math.hypot(q.x()-eye.x(),q.z()-eye.z())<=clip;}
    private GroundOrbit(){throw new AssertionError();}
}
