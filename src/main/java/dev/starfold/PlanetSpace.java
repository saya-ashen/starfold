package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.*;
import java.util.*;

/** Fixed planet-space kernel. No observer, Minecraft world, aliases or mutable projection. */
public final class PlanetSpace {
    public enum Shape { EQUIANGULAR, BALANCED, ROUNDED, TIPPED }
    public static final PlanetSpace STANDARD=rounded(64,-4,4);
    public static PlanetSpace tipped() { return new PlanetSpace(64,-4,4,Shape.TIPPED); }
    public static PlanetSpace rounded(int size,int min,int max) { return new PlanetSpace(size,min,max,Shape.ROUNDED); }
    /** Historical dev.1/dev.2 geometry for explicit regression comparisons only. */
    public static PlanetSpace equiangular(int size,int min,int max) { return new PlanetSpace(size,min,max,Shape.EQUIANGULAR); }
    public enum Side {
        DOWN(0,-1,0), UP(0,1,0), NORTH(0,0,-1), SOUTH(0,0,1), WEST(-1,0,0), EAST(1,0,0);
        public final Delta delta;
        Side(int u,int h,int v) { delta=new Delta(u,h,v); }
        public Side opposite() { return values()[ordinal()^1]; }
        static Side of(Delta d) { for(var s:values()) if(s.delta.equals(d)) return s; throw new IllegalArgumentException("Not cardinal"); }
    }
    public record Contact(Cell target,Side incoming,int turns) {}
    /** Integer coordinates on a cube boundary: shared across all incident charts. */
    public record Vertex(int x,int y,int z,int h) implements Comparable<Vertex> {
        @Override public int compareTo(Vertex b) {
            int c=Integer.compare(x,b.x); if(c==0) c=Integer.compare(y,b.y);
            if(c==0) c=Integer.compare(z,b.z); if(c==0) c=Integer.compare(h,b.h); return c;
        }
    }
    public record Triangle(Cell owner,Side side,Point a,Point b,Point c) {
        public Point normal() { return unit(b.sub(a).cross(c.sub(a))); }
    }
    public record Hit(Triangle triangle,Point position,double distance) {}
    public final int size,minHeight,maxHeight;
    /** Reference axis extent. Only spherical historical shapes have a universal radius. */
    public final double radius;
    public final PlanetRoundedGeometry rounded;
    public final TipGeometry tip;
    public final CubeSurfaceTopology topology;
    public final Shape shape;
    public PlanetSpace(int size,int minHeight,int maxHeight) { this(size,minHeight,maxHeight,Shape.BALANCED); }
    private PlanetSpace(int size,int minHeight,int maxHeight,Shape shape) {
        this.shape=shape; topology=new CubeSurfaceTopology(size);
        rounded=shape==Shape.ROUNDED?new PlanetRoundedGeometry(size):null;
        tip=shape==Shape.TIPPED?new TipGeometry(size):null;
        radius=rounded==null?2*size/Math.PI:rounded.flat+rounded.roundRadius;
        if(minHeight>=maxHeight || (rounded==null?radius:rounded.roundRadius)+minHeight<=2 || maxHeight-minHeight>32) throw new IllegalArgumentException("Invalid shell");
        this.size=size; this.minHeight=minHeight; this.maxHeight=maxHeight;
    }
    public int volume() { return 6*size*size*(maxHeight-minHeight); }
    /** Storage ownership, unchanged for saved arrays and oriented adjacency. */
    public boolean contains(Cell c) { return topology.owned(c)&&c.h()>=minHeight&&c.h()<maxHeight; }
    /** Exactly three columns per true vertex, not a wider seam band. Height-independent. */
    public boolean voidColumn(Cell c) {
        return shape!=Shape.EQUIANGULAR&&topology.owned(c)
                &&(c.u()==0||c.u()==size-1)&&(c.v()==0||c.v()==size-1);
    }
    /** Shared admission for geometry and building; raw saved material is kept separately. */
    public boolean hasVolume(Cell c) { return contains(c)&&!voidColumn(c); }
    public int index(Cell c) {
        if(!contains(c)) throw new IllegalArgumentException("Non-owner/outside finite shell");
        return c.u()+size*(c.v()+size*(c.h()-minHeight+(maxHeight-minHeight)*c.face().ordinal()));
    }
    public Cell cell(int index) {
        if(index<0||index>=volume()) throw new IllegalArgumentException("Invalid owner index");
        int u=index%size; index/=size; int v=index%size; index/=size;
        return new Cell(Face.values()[index/(maxHeight-minHeight)],u,minHeight+index%(maxHeight-minHeight),v);
    }
    public Contact neighbor(Cell c,Side side) {
        if(!contains(c)) throw new IllegalArgumentException("Expected canonical owner");
        var d=side.delta; var r=topology.canonical(c.add(d.u(),d.h(),d.v()));
        return new Contact(r.cell(),Side.of(r.transport(side.opposite().delta)),r.turns());
    }
    public Vertex vertex(Face f,int u,int h,int v) {
        if(u<0||u>size||v<0||v>size||h<minHeight||h>maxHeight) throw new IllegalArgumentException("Invalid vertex");
        var p=f.normal.scale(size).add(f.u.scale(2*u-size)).add(f.v.scale(2*v-size));
        return new Vertex((int)p.x(),(int)p.y(),(int)p.z(),h);
    }
    public Point point(Vertex v) {
        if(tip!=null) return tip.point(new Point((double)v.x/size,(double)v.y/size,(double)v.z/size),v.h);
        if(rounded!=null) return rounded.point(new Point((double)v.x/size,(double)v.y/size,(double)v.z/size),v.h);
        if(shape==Shape.BALANCED) return PlanetBalancedGeometry.direction(new Point((double)v.x/size,(double)v.y/size,(double)v.z/size)).scale(radius+v.h);
        return unit(new Point(tan(v.x),tan(v.y),tan(v.z))).scale(radius+v.h);
    }
    private double tan(int i) { return i==size?1:i==-size?-1:Math.tan(Math.PI*i/(4*size)); }
    public Point project(Face f,double u,double h,double v) {
        if(tip!=null) return tip.point(f.normal.add(f.u.scale((2*u-size)/size)).add(f.v.scale((2*v-size)/size)),h);
        if(rounded!=null) return rounded.point(f.normal.add(f.u.scale((2*u-size)/size)).add(f.v.scale((2*v-size)/size)),h);
        if(shape==Shape.BALANCED) return PlanetBalancedGeometry.direction(f.normal.add(f.u.scale((2*u-size)/size)).add(f.v.scale((2*v-size)/size))).scale(radius+h);
        return unit(f.normal.add(f.u.scale(Math.tan((u/size-.5)*Math.PI/2)))
                .add(f.v.scale(Math.tan((v/size-.5)*Math.PI/2)))).scale(radius+h);
    }
    public record Coordinates(double u,double h,double v) {}
    public Coordinates coordinates(Face face,Point p) {
        if(tip!=null) {var n=tip.baseDirection(p);var pair=PlanetBalancedGeometry.inverse(n.dot(face.u)/n.dot(face.normal),n.dot(face.v)/n.dot(face.normal));return new Coordinates(size*(.5+2/Math.PI*Math.atan(pair.a())),tip.altitude(p),size*(.5+2/Math.PI*Math.atan(pair.b())));}
        if(rounded!=null) return rounded.coordinates(face,p);
        double normal=p.dot(face.normal),a=p.dot(face.u)/normal,b=p.dot(face.v)/normal;
        if(shape==Shape.BALANCED) { var pair=PlanetBalancedGeometry.inverse(a,b); a=pair.a(); b=pair.b(); }
        return new Coordinates(size*(.5+2/Math.PI*Math.atan(a)),p.length()-radius,size*(.5+2/Math.PI*Math.atan(b)));
    }
    public double altitude(Point p) { return tip!=null?tip.altitude(p):rounded==null?p.length()-radius:rounded.altitude(p); }
    public Point up(Point p) { return rounded==null?unit(p):rounded.up(p); }
    public Point onRay(Point p,double h) { return tip!=null?tip.onRay(p,h):rounded==null?unit(p).scale(radius+h):rounded.onRay(p,h); }
    /** Chart lookup is diagnostic/broadphase only, NOT a raycast or faceted collision. */
    public Cell locate(Point p) {
        if(!p.finite() || p.length()<1e-9) throw new IllegalArgumentException("Invalid position");
        Face face=Face.TOP; double best=-Double.MAX_VALUE;
        for(var f:Face.values()) { double d=p.dot(f.normal); if(d>best) { best=d; face=f; } }
        var chart=coordinates(face,p);
        int u=(int)Math.floor(chart.u());
        int v=(int)Math.floor(chart.v());
        return new Cell(face,Math.clamp(u,0,size-1),(int)Math.floor(chart.h()),Math.clamp(v,0,size-1));
    }
    public List<Triangle> face(Cell c,Side side) { return face(c,side,this::point); }
    public List<Triangle> face(Cell c,Side side,java.util.function.Function<Vertex,Point> mapping) {
        if(!contains(c)) throw new IllegalArgumentException("Not a shell block");
        // Omit before evaluating any vertex, winding, normal or collision hull. Core geometry
        // must never leak out merely because an old save still contains a solid material there.
        if(!hasVolume(c)) return List.of();
        int u=c.u(),h=c.h(),v=c.v();
        int[][] offsets=switch(side) {
            case DOWN -> new int[][]{{0,0,0},{1,0,0},{1,0,1},{0,0,1}};
            case UP -> new int[][]{{0,1,0},{1,1,0},{1,1,1},{0,1,1}};
            case NORTH -> new int[][]{{0,0,0},{1,0,0},{1,1,0},{0,1,0}};
            case SOUTH -> new int[][]{{0,0,1},{1,0,1},{1,1,1},{0,1,1}};
            case WEST -> new int[][]{{0,0,0},{0,0,1},{0,1,1},{0,1,0}};
            case EAST -> new int[][]{{1,0,0},{1,0,1},{1,1,1},{1,1,0}};
        };
        Vertex[] keys=new Vertex[4]; Point[] points=new Point[4]; int first=0;
        for(int i=0;i<4;i++) {
            var o=offsets[i]; keys[i]=vertex(c.face(),u+o[0],h+o[1],v+o[2]); points[i]=mapping.apply(keys[i]);
            if(keys[i].compareTo(keys[first])<0) first=i;
        }
        // The least vertex always starts the diagonal; opposite owners choose identical triangles.
        Point a=points[first],b=points[(first+1)%4],d=points[(first+2)%4],e=points[(first+3)%4];
        // Winding is inherited from the authoritative source mesh, NOT repaired after a fold.
        Point inside=project(c.face(),u+.5,h+.5,v+.5);
        Point sa=point(keys[first]),sb=point(keys[(first+1)%4]),sd=point(keys[(first+2)%4]),se=point(keys[(first+3)%4]);
        boolean firstOut=sb.sub(sa).cross(sd.sub(sa)).dot(sa.sub(inside))>=0;
        boolean secondOut=sd.sub(sa).cross(se.sub(sa)).dot(sa.sub(inside))>=0;
        return List.of(firstOut?new Triangle(c,side,a,b,d):new Triangle(c,side,a,d,b),
                secondOut?new Triangle(c,side,a,d,e):new Triangle(c,side,a,e,d));
    }
    public static Point unit(Point p) { double n=p.length(); return n<1e-15?new Point(0,1,0):p.scale(1/n); }
    public static double ray(Triangle t,Point origin,Point direction,double reach) {
        var e1=t.b.sub(t.a); var e2=t.c.sub(t.a); var p=direction.cross(e2); double det=e1.dot(p);
        if(Math.abs(det)<1e-12) return Double.POSITIVE_INFINITY;
        double inv=1/det; var s=origin.sub(t.a); double u=s.dot(p)*inv;
        if(u < -1e-9 || u>1+1e-9) return Double.POSITIVE_INFINITY;
        var q=s.cross(e1); double v=direction.dot(q)*inv;
        if(v < -1e-9 || u+v>1+1e-9) return Double.POSITIVE_INFINITY;
        double d=e2.dot(q)*inv; return d>=0&&d<=reach?d:Double.POSITIVE_INFINITY;
    }
}
