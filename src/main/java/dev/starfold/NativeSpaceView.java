package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import java.util.*;

/** Pure passive-observer maths. No chart, home radius, orbit offset or camera ownership. */
public final class NativeSpaceView {
    /** Complete bodies beyond this distance are omitted rather than clamped to a false position. */
    public static final float FAR=1_000_000;
    /** Orbital chart observers see the same distant celestial bodies as passive region observers. */
    public static float chartFar(double altitude,int view){return altitude>GroundOrbit.START?FAR:view+64f;}
    public record Placement(Point center,Point u,Point up,Point v){}
    /** Subtract universe positions in doubles before rotating or uploading any float. */
    public static Point relative(NativeSpace.Frame frame,Point eye,Point point){return frame.levelVector(point.sub(eye));}
    /** Rows of R*Q, for the exterior shader's dot-product basis. */
    public static Placement place(NativeSpace.Frame frame,Point eye,GroundEphemeris.Frame target){return new Placement(relative(frame,eye,target.center()),target.inverseVector(frame.universeVector(new Point(1,0,0))),target.inverseVector(frame.universeVector(new Point(0,1,0))),target.inverseVector(frame.universeVector(new Point(0,0,1))));}
    /** Lowest id wins ties; a one-planet universe still returns its planet. */
    public static List<Integer> nearest(GroundEphemeris.Model model,Point eye,double tick,int limit){var ids=new ArrayList<Integer>();var distance=new double[model.count()];for(int id=0;id<model.count();id++){ids.add(id);distance[id]=model.at(id,tick).center().sub(eye).length();}ids.sort(Comparator.comparingDouble(id->distance[id]));return List.copyOf(ids.subList(0,Math.min(Math.max(0,limit),ids.size())));}
    public static boolean visible(double distance,double radius){return Double.isFinite(distance)&&Double.isFinite(radius)&&distance>=0&&radius>=0&&distance+radius<FAR-64;}
    /** The nearest eight physical occluders, independent of whether their meshes have finished building. */
    public static double sunlight(GroundEphemeris.Model model,Point eye,double tick){var sun=model.star(eye).position();double light=1;for(int id:nearest(model,eye,tick,8))light=Math.min(light,NativeGroundDaylight.sunlight(eye,model.at(id,tick).center(),sun,NativePlanetShape.of(id).radius()));return light;}
    /** One-tick blend for continuous frame changes; carries and guard shifts install discrete labels. */
    static final class Frames {
        private NativeSpace.Frame previous,current;private long epoch;private double currentTick;
        void install(NativeSpacePackets.Frame packet,Point eye,double clientTick,GroundEphemeris.Model model,double tick){
            var next=packet.frame();
            if(current!=null&&current.equals(next)&&epoch==packet.tick())return;
            boolean blend=current!=null&&next!=null&&model!=null&&current.planet()<model.count()&&next.planet()<model.count()&&current.universe(eye,model,tick).sub(next.universe(eye,model,tick)).length()<NativeSpace.ANCHOR_REACH;
            previous=blend?sample(eye,clientTick,model,tick):next;currentTick=clientTick;current=next;epoch=packet.tick();
        }
        NativeSpace.Frame current(){return current;}
        NativeSpace.Frame sample(Point eye,double clientTick,GroundEphemeris.Model model,double tick){return current==null?null:previous==null||previous==current||model==null||previous.planet()>=model.count()||current.planet()>=model.count()?current:NativeSpace.Frame.interpolate(previous,current,eye,Math.max(0,clientTick-currentTick),model,tick);}
        void clear(){previous=current=null;epoch=0;currentTick=0;}
    }
    private NativeSpaceView(){}
}
