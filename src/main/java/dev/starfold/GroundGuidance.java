package dev.starfold;

import dev.starfold.BentStripGeometry.Point;

/** Optional velocity assistance only: never owns position, camera or collision geometry. */
public final class GroundGuidance {
    public record Options(int target,boolean relativeBrake,boolean landing){
        public static final Options OFF=new Options(1,false,false);
        public Options{GroundCosmos.valid(target);}
        public Options manual(){return relativeBrake?new Options(target,false,landing):this;}
    }
    /** Velocities in blocks/tick; ETA in simulation seconds, NaN means unavailable. */
    public record Reading(double altitude,double relativeSpeed,double closing,double etaSeconds,double descent,double clearance,boolean danger){}
    public static Point frameVelocity(GroundEphemeris.Model model,int target,Point position,double tick){var f=model.at(target,tick);return f.velocity().add(f.angular().cross(position.sub(f.center())));}
    public static double descentLimit(double gap){if(gap>=2048)return Double.POSITIVE_INFINITY;if(gap<=50)return .15+.35*GroundGap.smooth(0,50,Math.max(0,gap));return Math.min(.5+11.5*GroundGap.smooth(50,512,gap),Math.sqrt(GroundTransit.ACCEL*Math.max(0,gap-2)));}
    public static Point limitVelocity(Point desired,Point up,double gap){double cap=descentLimit(gap),down=-desired.dot(up);return down>cap?desired.add(up.scale(down-cap)):desired;}
    private GroundGuidance(){}
}
