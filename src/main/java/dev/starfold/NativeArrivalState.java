package dev.starfold;

/** Pure tick state. Identity is the nearest planet and persistent lead-body id, never the lock. */
record NativeArrivalState(Phase phase,int ticks,int cooldown,int leaving,double entryHeight,int planet,String lead,Departure departure) {
    /** Flight-long identity: neither elapsed time, altitude nor another nearest planet clears it. */
    record Departure(int planet,String lead) {}
    NativeArrivalState(Phase phase,int ticks,int cooldown,int leaving,double entryHeight,int planet,String lead){this(phase,ticks,cooldown,leaving,entryHeight,planet,lead,null);}
    private NativeArrivalState withDeparture(Departure d){return new NativeArrivalState(phase,ticks,cooldown,leaving,entryHeight,planet,lead,d);}
    static NativeArrivalState departed(int planet,String lead){return cruise(planet,lead,0).withDeparture(new Departure(planet,lead));}
    NativeArrivalState lock(int planet,String lead){return departure!=null&&departure.planet==planet&&departure.lead.equals(lead)?withDeparture(null):this;}
    enum Phase { CRUISE,NOTICE,TURN_IN,ARRIVAL }
    static final int ARRIVE_NOTICE=80,BLEND_TICKS=20,RETRY=60;
    static NativeArrivalState cruise(int planet,String lead,int cooldown){return new NativeArrivalState(Phase.CRUISE,0,cooldown,0,0,planet,lead);}
    static NativeArrivalState empty(){return cruise(-1,"",0);}
    boolean latched(){return phase==Phase.TURN_IN||phase==Phase.ARRIVAL;}
    double blend(){return phase==Phase.ARRIVAL?1:phase==Phase.TURN_IN?Math.clamp(ticks/(double)BLEND_TICKS,0,1):0;}
    int seconds(){return phase==Phase.NOTICE?(ARRIVE_NOTICE-ticks+19)/20:0;}
    NativeArrivalState restored(){return (latched()?new NativeArrivalState(Phase.ARRIVAL,BLEND_TICKS,0,0,entryHeight,planet,lead):cruise(planet,lead,0)).withDeparture(departure);}
    NativeArrivalState step(int planet,String lead,double height,double closing,boolean start,boolean backstop,boolean abort,boolean chart,boolean aligned){
        return step(planet,lead,height,closing,start,backstop,abort,chart,aligned,-1);
    }
    NativeArrivalState step(int planet,String lead,double height,double closing,boolean start,boolean backstop,boolean abort,boolean chart,boolean aligned,int lockedPlanet){
        var d=departure;
        if(d!=null&&(chart||!d.lead.equals(lead)))d=null;
        boolean intended=lockedPlanet>=0?lockedPlanet==planet:d==null||d.planet!=planet;
        // Only notice admission is gated; backstop, latch, timers and abort remain authoritative.
        return advance(planet,lead,height,closing,start&&intended,backstop,abort,chart,aligned).withDeparture(d);
    }
    private NativeArrivalState advance(int planet,String lead,double height,double closing,boolean start,boolean backstop,boolean abort,boolean chart,boolean aligned){
        if(chart)return empty();
        if(this.planet!=planet||!this.lead.equals(lead))return cruise(planet,lead,0).step(planet,lead,height,closing,start,backstop,abort,false,aligned);
        if(abort)return cruise(planet,lead,RETRY);
        if(latched()){
            int away=closing<0?leaving+1:0;if(away>=RETRY||height>entryHeight+500)return cruise(planet,lead,RETRY);
            int age=Math.min(BLEND_TICKS,ticks+1);return new NativeArrivalState(age>=BLEND_TICKS&&aligned?Phase.ARRIVAL:phase,age,0,away,entryHeight,planet,lead);
        }
        if(cooldown>0)return cruise(planet,lead,cooldown-1);
        if(phase==Phase.NOTICE){
            if(backstop)return new NativeArrivalState(Phase.TURN_IN,0,0,0,height,planet,lead);
            if(!start)return cruise(planet,lead,RETRY);
            if(ticks+1>=ARRIVE_NOTICE)return new NativeArrivalState(Phase.TURN_IN,0,0,0,height,planet,lead);
            return new NativeArrivalState(Phase.NOTICE,ticks+1,0,0,0,planet,lead);
        }
        if(backstop)return new NativeArrivalState(Phase.TURN_IN,0,0,0,height,planet,lead);
        return start?new NativeArrivalState(Phase.NOTICE,0,0,0,0,planet,lead):this;
    }
    /** Smooth acceleration during the first second; proportional ease over the final ten degrees. */
    static double turnScale(double blend,double remainingDegrees){double t=Math.clamp(blend,0,1);return t*t*(3-2*t)*Math.clamp(remainingDegrees/10,0,1);}
}
