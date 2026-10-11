package dev.starfold;

import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;

/** Chart-local, plumb geometry shared by authoritative terrain planning and both collision sides. */
final class NativeAirstairGeometry {
    static final double MAX_DROP=10,STEP=.5;
    interface Terrain {double height(double x,double z);}
    record Flight(Vector3d hinge,Vector3d out,int width,double drop) {
        int steps(){return (int)Math.ceil(drop/STEP-1e-8);}
        double height(int step){return hinge.y-Math.min(drop,(step+1)*STEP)+(step==steps()-1?.0125:0);}
        List<AABB> boxes(){
            var result=new ArrayList<AABB>();var right=new Vector3d(out.z,0,-out.x);
            for(int i=-1;i<steps();i++){
                double[][] corners=new double[4][2];int n=0;
                double start=i<0?0:i*STEP,endRun=i<0?.125:(i+1)*STEP;
                for(double[] p:new double[][]{{-width*.5,start},{width*.5,start},{width*.5,endRun},{-width*.5,endRun}})corners[n++]=new double[]{hinge.x+right.x*p[0]+out.x*p[1],hinge.z+right.z*p[0]+out.z*p[1]};
                double minX=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY;for(var p:corners){minX=Math.min(minX,p[0]);maxX=Math.max(maxX,p[0]);}
                // Axis-aligned flights are exact; yawed edges use bounded 1/32-block strips, not a giant enclosing box.
                double slice=Math.abs(out.x*out.z)<1e-8?maxX-minX:1d/32;
                for(double x=minX;x<maxX-1e-9;x+=slice){double end=Math.min(maxX,x+slice),lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
                    for(int c=0;c<4;c++){var a=corners[c];var b=corners[(c+1)%4];if(a[0]>=x-1e-9&&a[0]<=end+1e-9){lo=Math.min(lo,a[1]);hi=Math.max(hi,a[1]);}for(double edge:new double[]{x,end})if(Math.abs(b[0]-a[0])>1e-9){double t=(edge-a[0])/(b[0]-a[0]);if(t>=0&&t<=1){double z=a[1]+t*(b[1]-a[1]);lo=Math.min(lo,z);hi=Math.max(hi,z);}}}
                    // Treads are solid down to the foot. As plates one could walk in under them from the side, and there the
                    // low ceiling forces a crouch or a crawl: slow, and no jump. Only the sill bridge stays a plate.
                    if(hi>lo)result.add(new AABB(x,i<0?height(i)-.125:hinge.y-drop,lo,end,height(i),hi));
                }
            }return List.copyOf(result);
        }
    }
    static int pairedWidth(int preceding,boolean next){return preceding%2==1?0:next?2:1;}
    static Flight flight(Vector3d hinge,Vector3d facing,Vector3d gravity,int width,Terrain terrain){
        if(!hinge.isFinite()||!facing.isFinite()||gravity.y>=0||Math.abs(gravity.x)+Math.abs(gravity.z)>1e-8||width<1||width>2)return null;
        var out=new Vector3d(facing.x,0,facing.z);if(out.lengthSquared()<1e-8)return null;out.normalize();var right=new Vector3d(out.z,0,-out.x);
        double door=terrain.height(hinge.x+out.x*.001,hinge.z+out.z*.001);if(Double.isFinite(door)&&door>=hinge.y-.001)return null;
        for(int step=0;step<20;step++){
            double high=Double.NEGATIVE_INFINITY;
            for(double along=step*STEP+.001;along<(step+1)*STEP;along+=.1245)for(double side=-width*.5+.001;side<width*.5;side+=.1245){double h=terrain.height(hinge.x+out.x*along+right.x*side,hinge.z+out.z*along+right.z*side);if(Double.isFinite(h))high=Math.max(high,h);}
            double previous=hinge.y-step*STEP,next=previous-STEP;
            // Use the same boundary tolerance as the landing test below. A wider wall tolerance leaves a
            // 0.001-block dead band just beyond every half tread: a resting hull's tiny settling then folds it.
            if(high>=previous-1e-8)return null; // wall/rising terrain cannot be mistaken for a landing tread
            if(high>=next-1e-8)return new Flight(new Vector3d(hinge),out,width,hinge.y-high);
        }return null;
    }
    private NativeAirstairGeometry(){}
}
