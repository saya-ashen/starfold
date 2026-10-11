package dev.starfold;

import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import dev.starfold.BentStripGeometry.Point;

/** Chart measurements only. Rendering belongs to snapshot-driven flight widgets. */
final class NativeLandingClient {
    private static Object body,level;private static NativeFrame frame;private static Vector3d previous;
    private static double vertical,horizontal;private static Point motion=GroundEphemeris.ZERO;
    static boolean active(){if(!NativeGroundClient.active()||NativeSpaceClient.present())return false;var hull=NativeHelmClient.body();if(hull==null)return false;var at=hull.logicalPose().position();var storage=NativeGroundClient.storage(at.x,NativeGroundCoordinates.FLOOR,at.z);return NativeGroundClient.access().inChart(net.minecraft.core.BlockPos.containing(storage.x(),storage.y(),storage.z()));}
    static void tick(){
        var mc=Minecraft.getInstance();if(!active()){body=null;level=null;frame=null;previous=null;vertical=horizontal=0;motion=GroundEphemeris.ZERO;return;}
        var hull=NativeHelmClient.body();var at=hull.logicalPose().position();var now=NativeGroundClient.frame();
        if(body==hull&&level==mc.level&&now.equals(frame)&&previous!=null){vertical=(at.y-previous.y)*20;motion=new Point((at.x-previous.x)*20,0,(at.z-previous.z)*20);horizontal=motion.length();}else{vertical=horizontal=0;motion=GroundEphemeris.ZERO;}
        body=hull;level=mc.level;frame=now;previous=new Vector3d(at);
    }
    static NativeLanding.Ground ground(){var mc=Minecraft.getInstance();var hull=NativeHelmClient.body();if(!active())return new NativeLanding.Ground(Double.NaN,false);var box=NativeLanding.bounds(hull);return NativeLanding.ground(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxZ(),(x,z)->NativeLanding.column(mc.level,x,z));}
    static NativeLanding.Site site(){var hull=NativeHelmClient.body();var state=hull==null?null:NativeHelmInputClient.state(hull.getUniqueId());return state!=null&&state.pilot()?state.landing():NativeLanding.Site.UNKNOWN;}
    static double clearance(){return ground().clearance();}
    static double vertical(){return vertical;}
    static double horizontal(){return horizontal;}
    static Point motion(){return motion;}
    private NativeLandingClient(){}
}
