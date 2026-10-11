package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import java.util.List;

/** One immutable read-only sample per rendered world frame. Positions/vectors are immutable Points. */
record NativeFlightSnapshot(int tier,Mode mode,int planet,double speed,int relativeTo,double cruiseCap,double boostCap,
        boolean boosted,double closing,Target target,List<Target> planets,Point velocityDirection,
        NativeSpaceNavigation.Marker velocityMarker,boolean retrograde,Point referenceDirection,int referencePlanet,
        Thrust thrust,double pointerX,double pointerY,double clearance,double vertical,double horizontal,int launchPlanet,
        Arrival arrival,Landing landing,Home home,NativeArrivalState.Phase phase,double blend,int notice) {
    enum Mode { FREE,ARRIVAL,CHART }
    double activeCap(){return boosted?boostCap:cruiseCap;}
    record Target(int planet,NativeSpaceNavigation.Marker marker,double distance,double closing,double angle,int rank,int count,int blocker){
        Target(int planet,NativeSpaceNavigation.Marker marker,double distance,double closing,double angle,int rank,int count){this(planet,marker,distance,closing,angle,rank,count,-1);}
        Target(int planet,NativeSpaceNavigation.Marker marker,double distance,double closing,double angle){this(planet,marker,distance,closing,angle,0,0,-1);}
        String eta(){return NativeHudLayout.eta(distance,closing);}}
    record Lift(double up,boolean insufficient){}
    record Thrust(boolean forward,boolean back,boolean left,boolean right,boolean up,boolean down,Lift lift){
        Thrust(boolean forward,boolean back,boolean left,boolean right,boolean up,boolean down){this(forward,back,left,right,up,down,null);}
    }
    record Arrival(double height,double closing,double limit,double totalLimit,NativeSpaceNavigation.Marker entry,double alignment,Thrust disabled){}
    record Landing(double limit,Point drift,double roll,double pitch,double width,double length,NativeLanding.Site site){
        Landing(double limit,Point drift,double roll,double pitch,double width,double length){this(limit,drift,roll,pitch,width,length,NativeLanding.Site.UNKNOWN);}
    }
    record Home(String name,int planet,double distance,double angle,NativeSpaceNavigation.Marker marker){}
    NativeFlightSnapshot { planets=List.copyOf(planets); }
}
