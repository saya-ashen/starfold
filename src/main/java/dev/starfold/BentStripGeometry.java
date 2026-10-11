package dev.starfold;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Non-rigid geometry for the opt-in folded version of the accepted unfolded strip.
 * Coordinates: u is the unified strip X, h is height relative to floor top, z is along the crease.
 * The map is injective for h > -radius. Deeper crust needs a different design, never silent clamping.
 * Both storage owners must use this SAME map (right owner adds StripTopology.WIDTH to local X).
 * Meshes are tessellated approximations. Drawing/picking share triangles; walking uses the
 * projected upright logical chart, NOT the separated Sable rigid collision shapes. */
public final class BentStripGeometry {
    public record Point(double x,double y,double z) {
        public Point add(Point b) { return new Point(x+b.x,y+b.y,z+b.z); }
        public Point sub(Point b) { return new Point(x-b.x,y-b.y,z-b.z); }
        public Point scale(double s) { return new Point(x*s,y*s,z*s); }
        public double dot(Point b) { return x*b.x+y*b.y+z*b.z; }
        public Point cross(Point b) { return new Point(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x); }
        public double length() { return Math.sqrt(dot(this)); }
        public boolean finite() { return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z); }
    }
    /** Original logical cell identity, not a projected/duplicate block. h is integer floor-relative. */
    public record Cell(int u,int h,int z) {
        public boolean rightOwner() { return u>=StripTopology.WIDTH; }
        public int ownerLocalX() { return rightOwner()?u-StripTopology.WIDTH:u; }
    }
    public enum Face { DOWN, UP, NORTH, SOUTH, WEST, EAST }
    public record Triangle(Cell owner,Point a,Point b,Point c,Face face) {}
    public record Hit(Cell owner,Point position,double distance,Face face) {}
    private final double radius,start,end;
    public BentStripGeometry(double seam,double radius) {
        if(!Double.isFinite(seam)||!Double.isFinite(radius)||radius<=0) throw new IllegalArgumentException("Invalid bend");
        this.radius=radius;
        double halfLength=Math.PI*radius/4;
        start=seam-halfLength; end=seam+halfLength;
        if(!Double.isFinite(start)||!Double.isFinite(end)) throw new IllegalArgumentException("Invalid extent");
    }
    public double start() { return start; }
    public double end() { return end; }
    private void check(Point p) {
        if(!p.finite() || p.y<=-radius) throw new IllegalArgumentException("Outside injective thickness domain");
    }
    public Point project(Point logical) {
        check(logical);
        double u=logical.x,h=logical.y,z=logical.z;
        if(u<=start) return logical;
        if(u>=end) return new Point(start+radius+h,-radius-(u-end),z);
        double theta=(u-start)/radius;
        return new Point(start+(radius+h)*Math.sin(theta),(radius+h)*Math.cos(theta)-radius,z);
    }
    /** Position on the SAME piecewise-linear surface as mesh(), for projected feet and camera. */
    public Point facetedProject(Point logical,int subdivisions) {
        if(subdivisions<1 || subdivisions>128) throw new IllegalArgumentException("Invalid tessellation");
        check(logical);
        double a=Math.floor(logical.x*subdivisions)/subdivisions,b=a+1.0/subdivisions;
        for(double cut:new double[]{start,end}) if(cut>a && cut<b) {
            if(logical.x<cut) b=cut; else a=cut;
        }
        double t=(logical.x-a)/(b-a);
        return project(new Point(a,logical.y,logical.z)).scale(1-t)
                .add(project(new Point(b,logical.y,logical.z)).scale(t));
    }
    public Point inverse(Point world) {
        if(!world.finite()) throw new IllegalArgumentException("Non-finite point");
        Point result;
        if(world.x<=start) result=world;
        else if(world.y<=-radius) result=new Point(end-(world.y+radius),world.x-start-radius,world.z);
        else {
            double dx=world.x-start,dy=world.y+radius;
            result=new Point(start+radius*Math.atan2(dx,dy),Math.hypot(dx,dy)-radius,world.z);
        }
        check(result);
        return result;
    }
    /** Local volume scale inside the bend; >0 is the no-inversion requirement. */
    public double jacobian(Point logical) {
        check(logical);
        return logical.x>start && logical.x<end ? 1+logical.y/radius : 1;
    }
    public Point up(double u) {
        if(!Double.isFinite(u)) throw new IllegalArgumentException("Invalid u");
        double angle=Math.clamp((u-start)/radius,0,Math.PI/2);
        return new Point(Math.sin(angle),Math.cos(angle),0);
    }
    /** One closed block surface, deterministic subdivisions shared by neighbouring blocks.
     * This does not cull internal faces or implement Minecraft fluids; caller supplies actual shapes later. */
    public List<Triangle> mesh(Cell cell,int subdivisions) {
        if(subdivisions<1 || subdivisions>128) throw new IllegalArgumentException("Invalid tessellation");
        return mesh(cell,subdivisions,1,63);
    }
    /** Face mask uses Face.ordinal; fluid height comes from real FluidState, not copied sources. */
    public List<Triangle> mesh(Cell cell,int subdivisions,double height,int faces) {
        if(subdivisions<1 || subdivisions>128 || !Double.isFinite(height) || height<=0 || height>1)
            throw new IllegalArgumentException("Invalid mesh shape");
        check(new Point(cell.u,cell.h,cell.z));
        double u=cell.u,h=cell.h,z=cell.z;
        var cuts=new TreeSet<Double>();
        for(int i=0;i<=subdivisions;i++) cuts.add(u+(double)i/subdivisions);
        // Include piece boundaries explicitly, otherwise a facet could bridge across two formulas.
        if(start>u && start<u+1) cuts.add(start);
        if(end>u && end<u+1) cuts.add(end);
        var xs=new ArrayList<>(cuts);
        var triangles=new ArrayList<Triangle>();
        for(int i=0;i<xs.size()-1;i++) {
            double a=xs.get(i),b=xs.get(i+1);
            if((faces&1)!=0) quad(triangles,cell,Face.DOWN,new Point(a,h,z),new Point(b,h,z),new Point(b,h,z+1),new Point(a,h,z+1));
            if((faces&2)!=0) quad(triangles,cell,Face.UP,new Point(a,h+height,z+1),new Point(b,h+height,z+1),new Point(b,h+height,z),new Point(a,h+height,z));
            if((faces&4)!=0) quad(triangles,cell,Face.NORTH,new Point(a,h,z),new Point(a,h+height,z),new Point(b,h+height,z),new Point(b,h,z));
            if((faces&8)!=0) quad(triangles,cell,Face.SOUTH,new Point(a,h,z+1),new Point(b,h,z+1),new Point(b,h+height,z+1),new Point(a,h+height,z+1));
        }
        if((faces&16)!=0) quad(triangles,cell,Face.WEST,new Point(u,h,z+1),new Point(u,h+height,z+1),new Point(u,h+height,z),new Point(u,h,z));
        if((faces&32)!=0) quad(triangles,cell,Face.EAST,new Point(u+1,h,z),new Point(u+1,h+height,z),new Point(u+1,h+height,z+1),new Point(u+1,h,z+1));
        return List.copyOf(triangles);
    }
    /** A source-owned partial box; shape and camera share the same global tessellation grid. */
    public List<Triangle> meshBox(Cell cell,int subdivisions,Point min,Point max) {
        if(subdivisions<1 || subdivisions>128 || !min.finite() || !max.finite()
                || min.x<0 || min.y<0 || min.z<0 || max.x>1 || max.y>1.5 || max.z>1
                || min.x>=max.x || min.y>=max.y || min.z>=max.z) throw new IllegalArgumentException("Invalid box");
        double x=cell.u+min.x,X=cell.u+max.x,y=cell.h+min.y,Y=cell.h+max.y,z=cell.z+min.z,Z=cell.z+max.z;
        var cuts=new TreeSet<Double>(); cuts.add(x); cuts.add(X);
        if(X>start && x<end) {
            for(int i=(int)Math.floor(x*subdivisions)+1;i<X*subdivisions;i++) cuts.add(i/(double)subdivisions);
            if(start>x && start<X) cuts.add(start); if(end>x && end<X) cuts.add(end);
        }
        var xs=new ArrayList<>(cuts); var out=new ArrayList<Triangle>();
        for(int i=1;i<xs.size();i++) {
            double a=xs.get(i-1),b=xs.get(i);
            quad(out,cell,Face.DOWN,new Point(a,y,z),new Point(b,y,z),new Point(b,y,Z),new Point(a,y,Z));
            quad(out,cell,Face.UP,new Point(a,Y,Z),new Point(b,Y,Z),new Point(b,Y,z),new Point(a,Y,z));
            quad(out,cell,Face.NORTH,new Point(a,y,z),new Point(a,Y,z),new Point(b,Y,z),new Point(b,y,z));
            quad(out,cell,Face.SOUTH,new Point(a,y,Z),new Point(b,y,Z),new Point(b,Y,Z),new Point(a,Y,Z));
        }
        quad(out,cell,Face.WEST,new Point(x,y,Z),new Point(x,Y,Z),new Point(x,Y,z),new Point(x,y,z));
        quad(out,cell,Face.EAST,new Point(X,y,z),new Point(X,Y,z),new Point(X,Y,Z),new Point(X,y,Z));
        return List.copyOf(out);
    }
    private void quad(List<Triangle> out,Cell owner,Face face,Point a,Point b,Point c,Point d) {
        a=project(a); b=project(b); c=project(c); d=project(d);
        out.add(new Triangle(owner,a,b,c,face)); out.add(new Triangle(owner,a,c,d,face));
    }
    /** Double-sided ray test over the exact supplied mesh, preserving the source cell identity.
     * Ties on an exact shared edge use input order; this is NOT yet a game interaction policy. */
    public static Hit pick(List<Triangle> mesh,Point origin,Point direction,double reach) {
        if(!origin.finite()||!direction.finite()||!Double.isFinite(reach)||reach<0)
            throw new IllegalArgumentException("Invalid ray");
        double length=direction.length();
        if(length<1e-12 || !Double.isFinite(length)) throw new IllegalArgumentException("Invalid ray direction");
        var ray=direction.scale(1/length);
        Hit closest=null;
        for(var t:mesh) {
            var ab=t.b.sub(t.a); var ac=t.c.sub(t.a);
            var p=ray.cross(ac); double determinant=ab.dot(p);
            if(Math.abs(determinant)<1e-12) continue;
            var offset=origin.sub(t.a);
            double u=offset.dot(p)/determinant;
            if(u < -1e-10 || u > 1+1e-10) continue;
            var q=offset.cross(ab);
            double v=ray.dot(q)/determinant;
            if(v < -1e-10 || u+v > 1+1e-10) continue;
            double distance=ac.dot(q)/determinant;
            if(distance<0 || distance>reach || (closest!=null && distance>=closest.distance)) continue;
            closest=new Hit(t.owner,origin.add(ray.scale(distance)),distance,t.face);
        }
        return closest;
    }
}
