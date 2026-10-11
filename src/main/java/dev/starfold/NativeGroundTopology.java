package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;

/** Addresses, not copied blocks. Native vertical storage extends below the old proxy ledger. */
public final class NativeGroundTopology {
    /** A vessel in deep space is still placed over the planet it belongs to, however far that is: suns stand up to
     *  a million blocks apart, several cells away. */
    public static final double MIN_HEIGHT=-192,MAX_HEIGHT=1.0e7;
    /** No planet's floor lies lower than this: a pose is only checked against the range any planet may have. */
    public static final double LOWEST=-1024;
    /** Only the region below native storage is an unconditional solid solar occluder.
     * Terrain/roofs above it are depth-tested from actual chunk-derived geometry. */
    public static final double SOLAR_CORE_RADIUS=GroundOrbit.RADIUS+MIN_HEIGHT-.01;
    public record Pose(Face face,Point point){
        public Pose{if(face==null||point==null||!point.finite()||point.x()<0||point.x()>=GroundSpace.SIZE||point.z()<0||point.z()>=GroundSpace.SIZE||point.y()<LOWEST||point.y()>MAX_HEIGHT)throw new IllegalArgumentException("Native pose");}
        /** The pose the planet under a vessel is drawn from. Deep between suns the vessel is further from its planet than
         *  that view reaches; the planet is then drawn from the edge of its reach, a speck either way. */
        public GroundSpace.Pose renderPose(){return new GroundSpace.Pose(face,new Point(point.x(),Math.clamp(point.y(),GroundSpace.MIN,GroundSpace.CEILING),point.z()));}
    }
    public record Transport(Pose pose,int turns){
        public Transport{turns=Math.floorMod(turns,4);}
        public Point vector(Point chart){return ChartLabSpace.rotate(chart,turns);}
        public Quaterniond aim(Quaterniond chart){return new Quaterniond().rotationY(-turns*Math.PI/2).mul(chart).normalize();}
    }
    public record Address(BlockPos owner,int turns){
        public Address{owner=owner.immutable();turns=Math.floorMod(turns,4);}
        public BlockState read(BlockState canonical){return ChartOrientation.state(canonical,-turns);}
        public BlockState write(BlockState query){return ChartOrientation.state(query,turns);}
    }
    public record Notification(BlockPos target,BlockPos source,int turns){}
    // Each function below exists for a planet of a given shape; the form without one is the standard size, which is
    // every planet of a world saved before sizes existed.
    public static Transport canonical(Face root,Point point){return canonical(NativePlanetShape.STANDARD,root,point);}
    public static Transport canonical(NativePlanetShape shape,Face root,Point point){return shape.canonical(root,point);}
    public static boolean ownedSection(net.minecraft.world.phys.AABB box,int observer){return ownedSection(NativePlanetShape.STANDARD,box,observer);}
    public static boolean ownedSection(NativePlanetShape shape,net.minecraft.world.phys.AABB box,int observer){var p=box.getCenter();return NativeGroundCoordinates.face(p.x)==observer&&NativeGroundCoordinates.owned(shape,new Point(p.x,p.y,p.z));}
    public static Point world(Pose pose){return NativeGroundCoordinates.world(pose.face(),pose.point());}
    public static Pose body(Point feet){return body(NativePlanetShape.STANDARD,feet);}
    public static Pose body(NativePlanetShape shape,Point feet){int face=NativeGroundCoordinates.face(feet.x());return canonical(shape,Face.values()[face],feet.sub(NativeGroundCoordinates.origin(face)).add(new Point(0,.9,0))).pose();}
    public static Point feet(Pose pose){return world(pose).sub(new Point(0,.9,0));}
    public static Cell cell(BlockPos position){int face=NativeGroundCoordinates.face(position.getX());return new Cell(Face.values()[face],position.getX()-face*NativeGroundCoordinates.STRIDE,position.getY()-NativeGroundCoordinates.FLOOR,position.getZ());}
    public static BlockPos block(Cell cell){return block(NativePlanetShape.STANDARD,cell);}
    public static BlockPos block(NativePlanetShape shape,Cell cell){if(!shape.topology().owned(cell))throw new IllegalArgumentException("Unowned native address");return new BlockPos(cell.face().ordinal()*NativeGroundCoordinates.STRIDE+cell.u(),NativeGroundCoordinates.FLOOR+cell.h(),cell.v());}
    public static Address address(BlockPos query){return address(NativePlanetShape.STANDARD,query);}
    public static Address address(NativePlanetShape shape,BlockPos query){var r=shape.topology().canonical(cell(query));return new Address(block(shape,r.cell()),r.turns());}
    /** Source stays relative in the TARGET chart, including a ghost source at an edge. */
    public static Notification notification(BlockPos target,BlockPos source){return notification(NativePlanetShape.STANDARD,target,source);}
    public static Notification notification(NativePlanetShape shape,BlockPos target,BlockPos source){
        var t=cell(target);var delta=source.subtract(target);var n=shape.topology().notification(t,t.add(delta.getX(),delta.getY(),delta.getZ()));
        var real=block(shape,n.target());var d=new BlockPos(n.source().u()-n.target().u(),n.source().h()-n.target().h(),n.source().v()-n.target().v());
        return new Notification(real,real.offset(d),n.turns());
    }
    public static Point planet(Pose pose){return GroundOrbit.direction(pose.face(),pose.point().x(),pose.point().z()).scale(GroundOrbit.RADIUS+pose.point().y());}
    public static Point planet(NativePlanetShape shape,Pose pose){return shape.position(pose);}
    public static Pose locate(Point planet){var p=GroundOrbit.locate(planet);return new Pose(p.face(),p.point());}
    public static Pose locate(NativePlanetShape shape,Point planet){return shape.locate(planet);}
    /** Exact differential: chart units are not assumed to equal radial-body units. */
    private static Point[] jacobian(NativePlanetShape shape,Pose pose){
        var f=pose.face();var p=pose.point();var cube=f.normal.scale(shape.half()).add(f.u.scale(p.x()-shape.half())).add(f.v.scale(p.z()-shape.half()));
        var n=PlanetSpace.unit(cube);double scale=(shape.radius()+p.y())/cube.length();
        return new Point[]{f.u.sub(n.scale(f.u.dot(n))).scale(scale),n,f.v.sub(n.scale(f.v.dot(n))).scale(scale)};
    }
    public static Point planetVelocity(Pose pose,Point chartVelocity){return planetVelocity(NativePlanetShape.STANDARD,pose,chartVelocity);}
    public static Point planetVelocity(NativePlanetShape shape,Pose pose,Point chartVelocity){var j=jacobian(shape,pose);return j[0].scale(chartVelocity.x()).add(j[1].scale(chartVelocity.y())).add(j[2].scale(chartVelocity.z()));}
    public static Point chartVelocity(Pose pose,Point planetVelocity){return chartVelocity(NativePlanetShape.STANDARD,pose,planetVelocity);}
    public static Point chartVelocity(NativePlanetShape shape,Pose pose,Point planetVelocity){var j=jacobian(shape,pose);double a=j[0].dot(j[0]),b=j[0].dot(j[2]),c=j[2].dot(j[2]),det=a*c-b*b;if(!(det>1e-12))throw new IllegalArgumentException("Degenerate native tangent");double u=planetVelocity.dot(j[0]),v=planetVelocity.dot(j[2]);return new Point((c*u-b*v)/det,planetVelocity.dot(j[1]),(a*v-b*u)/det);}
    private NativeGroundTopology(){}
}
