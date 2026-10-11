package dev.starfold;

import dev.starfold.BentStripGeometry.Point;

/** Local, static outward tips over the exact dev.5 direction map. No rounded-box blend. */
public final class TipGeometry {
    public static final double WIDTH=22,AMPLITUDE=3.2,SQUEEZE=-.153;
    public static final String PROFILE="dev5_tips_w22_a3.2_s-0.153_v1";
    public final double radius,range,amplitude;
    public TipGeometry(int size){radius=2*size/Math.PI;range=WIDTH*(size/64.0)/radius;amplitude=AMPLITUDE*(size/64.0);}
    private Point corner(Point n){double a=1/Math.sqrt(3);return new Point(Math.copySign(a,n.x()),Math.copySign(a,n.y()),Math.copySign(a,n.z()));}
    private double angle(Point n,Point c){return Math.atan2(n.sub(c.scale(n.dot(c))).length(),n.dot(c));}
    private double weight(double theta){double t=Math.max(0,1-theta/range);return t*t;}
    private double warped(double theta){return theta*(1+SQUEEZE*weight(theta));}
    private Point rotate(Point n,Point c,double theta,double next){if(theta<1e-12)return n;var tangent=PlanetSpace.unit(n.sub(c.scale(n.dot(c))));return c.scale(Math.cos(next)).add(tangent.scale(Math.sin(next)));}
    public Point point(Point cube,double height){var n=PlanetBalancedGeometry.direction(cube);var c=corner(n);double theta=angle(n,c);if(theta>=range)return n.scale(radius+height);return rotate(n,c,theta,warped(theta)).scale(radius+height+amplitude*weight(theta));}
    /** Monotone meridian redistribution; it keeps seams and chart ordering, without a 2D inverse solve. */
    private double originalAngle(double displayed){if(displayed>=range)return displayed;double lo=0,hi=range;for(int i=0;i<44;i++){double mid=(lo+hi)*.5;if(warped(mid)<displayed)lo=mid;else hi=mid;}return (lo+hi)*.5;}
    public Point baseDirection(Point p){var n=PlanetSpace.unit(p);var c=corner(n);double displayed=angle(n,c);if(displayed>=range)return n;return rotate(n,c,displayed,originalAngle(displayed));}
    public double surfaceRadius(Point direction){var n=PlanetSpace.unit(direction);return radius+amplitude*weight(originalAngle(angle(n,corner(n))));}
    /** Heights retain dev.5's radial shell meaning; the body-up remains radial, not a new normal-offset shell. */
    public double altitude(Point p){return p.length()-surfaceRadius(p);}
    public Point onRay(Point p,double h){return PlanetSpace.unit(p).scale(Math.max(PlanetMotion.MIN_DISTANCE,surfaceRadius(p)+h));}
}
