package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Cell;
import dev.starfold.CubeSurfaceTopology.Face;

/** One planet's size, and everything that follows from it. A planet is six square faces of size blocks; its
 *  radius, its topology and the mapping between a face and a direction on the sphere are all derived here, so
 *  that a planet of another size is another instance and not another set of constants. Storage charts are laid
 *  out for faces of up to MAX blocks, which a smaller planet simply does not fill. */
public record NativePlanetShape(int size) {
    public static final int MIN=256,MAX=GroundSpace.SIZE;
    private static final java.util.Map<Integer,CubeSurfaceTopology> TOPOLOGIES=new java.util.concurrent.ConcurrentHashMap<>();
    /** The size every planet had before sizes were saved with a planet's generation. Code that still assumes one
     *  size for all planets says so by naming this. */
    public static final NativePlanetShape STANDARD=new NativePlanetShape(2048);
    /** The shapes of the planets of the system now running: set from the saved generator when its level loads, and on
     *  a client from the server. Addressing that only knows which planet a position belongs to reads them here. */
    private static volatile NativePlanetShape[] SYSTEM={STANDARD,STANDARD};
    public static NativePlanetShape of(int planet){return SYSTEM[valid(planet)];}
    /** How many planets the running system has. */
    public static int count(){return SYSTEM.length;}
    public static int valid(int planet){if(planet<0||planet>=SYSTEM.length)throw new IllegalArgumentException("Planet");return planet;}
    /** Replaces the whole table: one shape and one depth for each planet of a system, in storage order. */
    public static void system(int[] sizes,int[] depths){
        if(sizes.length<1||sizes.length>NativePlanetStorage.MAX_PLANETS||depths.length!=sizes.length)throw new IllegalArgumentException("Planet count "+sizes.length);
        var shapes=new NativePlanetShape[sizes.length];for(int id=0;id<sizes.length;id++){shapes[id]=new NativePlanetShape(sizes[id]);if(depths[id]<NativePlanetGeneration.STANDARD_DEPTH||depths[id]>=shapes[id].radius())throw new IllegalArgumentException("Planet depth "+depths[id]);}
        DEPTHS=depths.clone();SYSTEM=shapes;
    }
    /** How deep each of those planets goes below its nominal surface, set along with its shape. */
    private static volatile int[] DEPTHS={NativePlanetGeneration.STANDARD_DEPTH,NativePlanetGeneration.STANDARD_DEPTH};
    public static void assign(int planet,NativePlanetShape shape){assign(planet,shape,NativePlanetGeneration.STANDARD_DEPTH);}
    public static void assign(int planet,NativePlanetShape shape,int depth){if(depth<NativePlanetGeneration.STANDARD_DEPTH||depth>=shape.radius())throw new IllegalArgumentException("Planet depth "+depth);SYSTEM[valid(planet)]=shape;DEPTHS[planet]=depth;}
    public static void reset(){NativePlanetSystem.identities(java.util.List.of());NativePlanetSystem.names(java.util.List.of());SYSTEM=new NativePlanetShape[]{STANDARD,STANDARD};DEPTHS=new int[]{NativePlanetGeneration.STANDARD_DEPTH,NativePlanetGeneration.STANDARD_DEPTH};}
    public static int depth(int planet){return DEPTHS[valid(planet)];}
    /** Height of a planet's floor, measured like a pose's from the sphere of its radius. */
    public static double floorHeight(int planet){return -depth(planet)-1;}
    /** Everything within this distance of a planet's centre lies below its floor: solid, whatever was dug above. */
    public static double coreRadius(int planet){return of(planet).radius()+floorHeight(planet)-.01;}
    /** A chart shows the face and one face's width of each neighbour around it. */
    public boolean inChart(int u,int v){return topology().inHalo(u,v);}
    public NativePlanetShape{if(size<MIN||size>MAX||size%16!=0)throw new IllegalArgumentException("Planet size "+size);}
    public double half(){return size/2.0;}
    /** A face's edge is a quarter of a great circle. */
    public double radius(){return size*2/Math.PI;}
    /** Chunks along a face's edge. */
    public int chunks(){return size/16;}
    public CubeSurfaceTopology topology(){return TOPOLOGIES.computeIfAbsent(size,CubeSurfaceTopology::new);}
    public boolean owned(double u,double v){return u>=0&&u<size&&v>=0&&v<size;}
    /** A point of any face's chart, on or beyond the face, as the face and point that own it. */
    public NativeGroundTopology.Transport canonical(Face root,Point point){
        if(root==null||point==null||!point.finite())throw new IllegalArgumentException("Native point");
        int u=(int)Math.floor(point.x()),v=(int)Math.floor(point.z());var route=topology().canonical(new Cell(root,u,0,v));
        var d=ChartLabSpace.rotate(new Point(point.x()-u-.5,0,point.z()-v-.5),route.turns());double last=Math.nextDown((double)size);
        return new NativeGroundTopology.Transport(new NativeGroundTopology.Pose(route.cell().face(),new Point(Math.clamp(route.cell().u()+.5+d.x(),0,last),point.y(),Math.clamp(route.cell().v()+.5+d.z(),0,last))),route.turns());
    }
    /** Unit direction from the planet's centre through a point of a face. */
    public Point direction(Face face,double u,double v){
        if(u<0||u>size||v<0||v>size){var p=canonical(face,new Point(u,0,v)).pose();face=p.face();u=p.point().x();v=p.point().z();}
        return PlanetSpace.unit(face.normal.scale(half()).add(face.u.scale(u-half())).add(face.v.scale(v-half())));
    }
    /** Planet-centred position of a pose: its height is measured from the sphere of this planet's radius. */
    public Point position(NativeGroundTopology.Pose pose){return direction(pose.face(),pose.point().x(),pose.point().z()).scale(radius()+pose.point().y());}
    public NativeGroundTopology.Pose locate(Point planet){
        if(planet==null||!planet.finite()||planet.length()<radius()+NativeGroundTopology.LOWEST||planet.length()>radius()+NativeGroundTopology.MAX_HEIGHT)throw new IllegalArgumentException("Outside planet shell");
        Face face=Face.TOP;for(var other:Face.values())if(planet.dot(other.normal)>planet.dot(face.normal))face=other;
        double scale=half()/planet.dot(face.normal),last=Math.nextDown((double)size);
        return new NativeGroundTopology.Pose(face,new Point(Math.clamp(half()+planet.dot(face.u)*scale,0,last),planet.length()-radius(),Math.clamp(half()+planet.dot(face.v)*scale,0,last)));
    }
}
