package dev.starfold;

import dev.starfold.BentStripGeometry.Point;

/** Three Euclidean quadrants glued into a 270-degree cone; NOT a Euclidean globe.
 * Crossing z=0 rotates local XZ by +90 degrees and advances the chart; x=0 is inverse.
 * A lifted sheet records transport, while floorMod(sheet,3) owns all block state. */
public final class ChartLabSpace {
    public static final int SIZE=32,CHARTS=3,CORE=4,MIN=-2,MAX=6,LAYERS=MAX-MIN,VOLUME=CHARTS*SIZE*SIZE*LAYERS;
    public static final double REACH=5,VIEW=48,GUARD_START=115,GUARD_END=125,MAX_HEIGHT=10;
    public static final int WALL_TOP=11; // Covers the entire permitted body envelope, not an infinite sky wall.
    public static final int CORE_MATERIAL=21,BORDER_MATERIAL=22,LIMIT_MATERIAL=23,GUARD_MATERIAL=24;
    private ChartLabSpace() {}
    public record Cell(int chart,int x,int y,int z) {}
    public record Pose(int sheet,Point point) {
        public Pose {
            if(Math.abs((long)sheet)>1000000||!point.finite()||point.x()<0||point.x()>=SIZE||point.z()<0||point.z()>=SIZE
                    ||point.y()<MIN-2||point.y()>MAX_HEIGHT+.01) throw new IllegalArgumentException("Invalid chart pose");
        }
        public int chart() { return Math.floorMod(sheet,CHARTS); }
    }
    public static Point rotate(Point p,int turns) {
        return switch(Math.floorMod(turns,4)) {
            case 0 -> p; case 1 -> new Point(-p.z(),p.y(),p.x());
            case 2 -> new Point(-p.x(),p.y(),-p.z()); default -> new Point(p.z(),p.y(),-p.x());
        };
    }
    public static Pose canonical(int sheet,Point p) {
        for(int i=0;i<8;i++) {
            if(p.x()>=0&&p.z()>=0) return new Pose(sheet,p);
            if(p.x()<0) { p=rotate(p,-1); sheet--; } else { p=rotate(p,1); sheet++; }
        }
        throw new IllegalArgumentException("Unbounded chart route");
    }
    public static Cell owner(int chart,int x,int y,int z) {
        for(int i=0;i<8;i++) {
            if(x>=0&&z>=0) return new Cell(Math.floorMod(chart,CHARTS),x,y,z);
            if(x<0) { int old=x; x=z; z=-old-1; chart--; }
            else { int old=x; x=-z-1; z=old; chart++; }
        }
        throw new IllegalArgumentException("Unbounded cell route");
    }
    public static boolean stored(Cell c) { return c.chart>=0&&c.chart<CHARTS&&c.x>=0&&c.x<SIZE&&c.z>=0&&c.z<SIZE&&c.y>=MIN&&c.y<MAX; }
    /** Texture-compatible unique index: texture width=3*SIZE, height=LAYERS*SIZE. */
    public static int index(Cell c) {
        if(!stored(c)) throw new IllegalArgumentException("Not a stored owner");
        return c.x+SIZE*c.chart+CHARTS*SIZE*(c.z+SIZE*(c.y-MIN));
    }
    public static Cell cell(int i) {
        if(i<0||i>=VOLUME) throw new IllegalArgumentException("Invalid owner index");
        int x=i%SIZE,chart=i/SIZE%CHARTS; i/=SIZE*CHARTS;
        return new Cell(chart,x,MIN+i/SIZE,i%SIZE);
    }
    /** For each owner center only ONE lifted image can lie inside this <270-degree window.
     * This is an explicit sight restriction, not a claim that all optics fit in the 4x4 core. */
    public static double angle(Point eye,int x,int z,int turns) {
        return Math.toDegrees(Math.atan2(z+.5,x+.5)-Math.atan2(eye.z(),eye.x()))-90*turns;
    }
    public static double guard(double angle) { return Math.clamp((Math.abs(angle)-GUARD_START)/(GUARD_END-GUARD_START),0,1); }
    public static Pose interpolate(Pose a,Pose b,double t) {
        Point old=rotate(a.point,b.sheet-a.sheet);
        return canonical(b.sheet,old.scale(1-t).add(b.point.scale(t)));
    }
}
