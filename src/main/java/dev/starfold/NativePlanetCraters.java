package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import java.util.*;

/** rocky_craters v1. Seeded spherical impact sites: no face identity or chunk order. */
public final class NativePlanetCraters {
    public record Impact(Point center,double radius,double depth){}
    private final List<Impact> impacts;
    public NativePlanetCraters(long seed){
        var random=new Random(seed^0x4f19b236de819aL);var sites=new ArrayList<Impact>();
        for(int i=0;i<80;i++){double y=random.nextDouble()*2-1,angle=random.nextDouble()*Math.PI*2,r=Math.sqrt(1-y*y);sites.add(new Impact(new Point(r*Math.cos(angle),y,r*Math.sin(angle)),.035+random.nextDouble()*.085,14+random.nextDouble()*14));}
        impacts=List.copyOf(sites);
    }
    public List<Impact> impacts(){return impacts;}
    public static double profile(double distance){
        if(distance>=1.3)return 0;
        if(distance<.85){double t=distance/.85;return -Math.pow(1-t*t,2);}
        double t=(distance-.85)/.45;return .35*Math.pow(Math.sin(Math.PI*t),2);
    }
    public double elevation(Point unit){double value=0;for(var site:impacts){double squared=2*Math.max(0,1-unit.dot(site.center()));double reach=site.radius()*1.3;if(squared<reach*reach)value+=site.depth()*profile(Math.sqrt(squared)/site.radius());}return value;}
}
