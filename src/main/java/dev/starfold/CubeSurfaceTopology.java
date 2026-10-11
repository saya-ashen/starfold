package dev.starfold;

import dev.starfold.BentStripGeometry.Point;

/** Six upright voxel charts, with transported horizontal directions.
 * No ghost blocks are stored. Corner queries choose the greatest overflow first (U on ties).
 * This defines a deterministic query, NOT commuting flat-grid diagonal paths at cube vertices.
 * This pure kernel deliberately has no Minecraft storage or client dependencies. */
public final class CubeSurfaceTopology {
    public enum Face {
        TOP(new Point(0,1,0),new Point(1,0,0),new Point(0,0,1)),
        EAST(new Point(1,0,0),new Point(0,-1,0),new Point(0,0,1)),
        WEST(new Point(-1,0,0),new Point(0,1,0),new Point(0,0,1)),
        NORTH(new Point(0,0,-1),new Point(1,0,0),new Point(0,1,0)),
        SOUTH(new Point(0,0,1),new Point(1,0,0),new Point(0,-1,0)),
        BOTTOM(new Point(0,-1,0),new Point(1,0,0),new Point(0,0,-1));
        public final Point normal,u,v;
        Face(Point normal,Point u,Point v) { this.normal=normal; this.u=u; this.v=v; }
    }
    public record Cell(Face face,int u,int h,int v) {
        public Cell { if(face==null) throw new IllegalArgumentException("Missing face"); }
        public Cell add(int du,int dh,int dv) { return new Cell(face,u+du,h+dh,v+dv); }
    }
    public record Delta(int u,int h,int v) {}
    /** Quarter turns transport queried-chart vectors into the real owner's chart. */
    public record Route(Cell cell,int turns) {
        public Route { turns=Math.floorMod(turns,4); }
        public Delta transport(Delta delta) { return rotate(delta,turns); }
        public Delta untransport(Delta delta) { return rotate(delta,-turns); }
    }
    public record Notification(Cell target,Cell source,int turns) {}
    private final int size;
    public CubeSurfaceTopology(int size) {
        if(size<2 || size>2048) throw new IllegalArgumentException("Face size outside 2..2048");
        this.size=size;
    }
    public boolean owned(Cell p) { return p.u>=0 && p.u<size && p.v>=0 && p.v<size; }
    public static Delta rotate(Delta d,int turns) {
        return switch(Math.floorMod(turns,4)) {
            case 0 -> d;
            case 1 -> new Delta(-d.v,d.h,d.u);
            case 2 -> new Delta(-d.u,d.h,-d.v);
            default -> new Delta(d.v,d.h,-d.u);
        };
    }
    /** The face and its one-face halo; upper edges are excluded, including for interpolated eyes. */
    public boolean inHalo(double u,double v) { return u>=-size && u<2*size && v>=-size && v<2*size; }
    public Route canonical(Cell query) {
        // Halo-sized local queries only. Prevent malformed inputs from taking unbounded walks.
        if(!inHalo(query.u,query.v))
            throw new IllegalArgumentException("Query exceeds one-face halo");
        Cell current=query; int turns=0;
        for(int i=0;i<8;i++) {
            if(owned(current)) return new Route(current,turns);
            int ou=overflow(current.u),ov=overflow(current.v);
            boolean alongU=ou>=ov;
            int coordinate=alongU?current.u:current.v;
            int sign=coordinate<0?-1:1;
            Face old=current.face;
            Point normal=(alongU?old.u:old.v).scale(sign);
            Face next=faceForNormal(normal);
            double r=size/2.0;
            double distance=sign>0?coordinate+.5-size:-coordinate-.5;
            Point q=normal.scale(r).add(old.normal.scale(r-distance))
                    .add((alongU?old.v:old.u).scale((alongU?current.v:current.u)+.5-r));
            Point transportedU=alongU?old.normal.scale(-sign):old.u;
            int turn=quarterTurn(transportedU.dot(next.u),transportedU.dot(next.v));
            current=new Cell(next,(int)Math.floor(r+q.dot(next.u)),current.h,
                    (int)Math.floor(r+q.dot(next.v)));
            turns+=turn;
        }
        throw new IllegalStateException("Corner route failed to converge");
    }
    /** Relative source remains a cardinal neighbour, including queued notifications into ghosts.
     * The returned source may itself be a ghost in the receiver's chart: do not canonicalize it
     * independently before calling vanilla code, which needs the relative direction. */
    public Notification notification(Cell target,Cell source) {
        if(target.face!=source.face) throw new IllegalArgumentException("Expected one query chart");
        Route route=canonical(target);
        Delta d=route.transport(new Delta(source.u-target.u,source.h-target.h,source.v-target.v));
        return new Notification(route.cell,route.cell.add(d.u,d.h,d.v),route.turns);
    }
    private int overflow(int coordinate) { return coordinate<0?-coordinate:Math.max(0,coordinate-size+1); }
    private static int quarterTurn(double u,double v) {
        if(u==1 && v==0) return 0;
        if(u==0 && v==1) return 1;
        if(u==-1 && v==0) return 2;
        if(u==0 && v==-1) return 3;
        throw new IllegalStateException("Non-cardinal chart basis");
    }
    public static Face faceForNormal(Point normal) {
        for(Face f:Face.values()) if(f.normal.dot(normal)==1) return f;
        throw new IllegalArgumentException("Not a cube normal");
    }
}
