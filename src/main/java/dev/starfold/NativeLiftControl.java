package dev.starfold;

/** Pure force-limited vertical autopilot. Units: blocks, seconds, Sable mass and force. */
final class NativeLiftControl {
    static final double HULL_GRAVITY=.75,OMEGA=2,VELOCITY_GAIN=4,TELEPORT_RESET=64;
    static final double DESCENT=47,BOOST_DESCENT=110,GOVERNOR_HEIGHT=200;
    static final double CLIMB=160,BOOST_CLIMB=640;
    static final double CONTACT=.15,SETTLED_SPEED=.8,TOUCHDOWN=1.2;
    static final double BRAKE_RESERVE=.9,REACTION=.05,ENVELOPE_SAMPLE=.25,CHART_YAW=.55,CHART_YAW_DAMPING=.5;
    record Output(double lift,double down,boolean insufficient,double limit,double stoppingDistance){}
    static boolean landed(double clearance,double velocity){return Double.isFinite(clearance)&&clearance<=CONTACT&&Math.abs(velocity)<=SETTLED_SPEED;}
    /** Capture a reachable critically-damped stopping altitude, never promise instant braking. */
    static double capture(double y,double velocity,double gravity,double margin){
        double brake=velocity>0?gravity:margin,speed=Math.abs(velocity);
        if(brake<=0)return y;
        double distance=speed<=brake/OMEGA?speed/OMEGA:speed*speed/(2*brake)+brake/(2*OMEGA*OMEGA);
        return y+Math.copySign(distance,velocity);
    }
    static double stoppingDistance(double speed,double margin){return margin>0?speed*speed/(2*margin)+speed*REACTION:Double.POSITIVE_INFINITY;}
    /** Envelope anticipates every point of the existing low-altitude governor, with real lift margin. */
    static double descentLimit(double clearance,double margin,boolean boost){
        double limit=boost?BOOST_DESCENT:DESCENT;
        if(!Double.isFinite(clearance))return limit;
        double h=Math.max(0,clearance),a=Math.max(0,margin)*BRAKE_RESERVE;
        limit=Math.min(limit,NativeLanding.descent(h,Double.POSITIVE_INFINITY,false));
        // Reserve reaction distance; the 1.2 touchdown replaces the old 4 b/s impact floor.
        double distance=Math.max(0,h-CONTACT);
        limit=Math.min(limit,Math.sqrt(TOUCHDOWN*TOUCHDOWN+2*a*distance));
        // For v(h)=4+.75h, braking demand is v*dv/dh. Its tangent point is a/.75.
        double tangent=Math.clamp((a/NativeLanding.LAND_SLOPE-NativeLanding.LAND_MIN)/NativeLanding.LAND_SLOPE,0,h);
        double target=NativeLanding.LAND_MIN+NativeLanding.LAND_SLOPE*tangent;
        limit=Math.min(limit,Math.sqrt(target*target+2*a*(h-tangent)));
        return Math.max(0,limit-a*REACTION);
    }
    static Output step(double mass,double gravity,double maxLift,double maxDown,double y,double target,double velocity,double clearance,int bits,boolean boost,boolean pilot){
        double weight=Math.max(0,mass*gravity),margin=mass>0?(maxLift-weight)/mass:0;
        boolean insufficient=maxLift<weight,up=(bits&(NativeHelmCommand.UP|NativeHelmCommand.DOWN))==NativeHelmCommand.UP;
        boolean down=(bits&(NativeHelmCommand.UP|NativeHelmCommand.DOWN))==NativeHelmCommand.DOWN;
        double limit=descentLimit(clearance,margin,boost),stop=stoppingDistance(Math.max(0,-velocity),margin);
        if(mass<=0||landed(clearance,velocity)&&!up)return new Output(0,0,insufficient,limit,stop);
        double acceleration=up?VELOCITY_GAIN*((boost?BOOST_CLIMB:CLIMB)-velocity):down?VELOCITY_GAIN*(-limit-velocity):OMEGA*OMEGA*(target-y)-2*OMEGA*velocity;
        // Saturated capture segment joins the critically damped trajectory at |v|=brake/omega.
        // Without this segment a distant ballistic stopping target would request MORE lift on release.
        if(!up&&!down){
            if(velocity>gravity/OMEGA)acceleration=Math.min(acceleration,-gravity);
            if(margin>0&&velocity< -margin/OMEGA)acceleration=Math.max(acceleration,margin);
        }
        if(down&&velocity<0&&Double.isFinite(clearance)){
            double slope=(descentLimit(clearance+ENVELOPE_SAMPLE,margin,boost)-descentLimit(clearance-ENVELOPE_SAMPLE,margin,boost))/(2*ENVELOPE_SAMPLE);
            acceleration+=Math.max(0,-velocity*slope);
        }
        double requested=weight+mass*acceleration;
        // Down nozzles are optional acceleration only, never hover or braking authority.
        boolean fast=pilot&&down&&boost&&clearance>GOVERNOR_HEIGHT&&clearance>stop;
        double downForce=fast?Math.clamp(-requested,0,maxDown):0;
        return new Output(maxLift>0?Math.clamp(requested/maxLift,0,1):0,maxDown>0?downForce/maxDown:0,insufficient,limit,stop);
    }
    private NativeLiftControl(){}
}
