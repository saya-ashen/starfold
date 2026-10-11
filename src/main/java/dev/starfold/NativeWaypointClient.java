package dev.starfold;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Vector3d;

import java.util.Locale;

/** Read-only target HUD. Uses the moving planet frame, not stored universe coordinates. */
public final class NativeWaypointClient {
    private static NativeWaypoint marker;
    private static long session;
    private static NativeSpaceNavigation.Marker projected;
    record Guidance(double distance,double angle){}
    static NativeWaypoint active(){long current=NativeSpaceClient.present()?NativeSpaceClient.session():NativeGroundFlightClient.session();return current!=0&&session==current?marker:null;}
    private static String name(NativeWaypoint point){return point.name().equals(NativeWaypoint.LAUNCH)?net.minecraft.client.resources.language.I18n.get("hud.starfold.launch_site"):point.name();}
    private static BentStripGeometry.Point bearing(){
        var hull=NativeHelmClient.body();if(hull==null||active()==null||marker.planet()!=NativeGroundClient.planet())return null;
        var at=hull.logicalPose().position();var frame=NativeGroundClient.frame();var storage=frame.toStorage(new BentStripGeometry.Point(at.x,at.y,at.z));var access=NativeGroundClient.access();
        var from=NativeGroundTopology.canonical(NativeGroundClient.shape(),CubeSurfaceTopology.Face.values()[frame.root()],access.chart(storage));
        var route=NativeWaypointNavigation.route(NativeGroundClient.shape(),from.pose(),marker.pose());
        return route==null?null:frame.vectorToClient(ChartLabSpace.rotate(route.direction(),-from.turns()));
    }
    static Guidance guidance(){
        if(!NativeLandingClient.active())return null;var direction=bearing();if(direction==null)return null;
        var nose=NativeHelmClient.forward(NativeHelmClient.body());double facing=Math.toDegrees(Math.atan2(-nose.x,nose.z));double angle=NativeWaypointNavigation.angle(facing,direction);
        return new Guidance(direction.length(),angle);
    }
    static void capture(LocalRender context){
        projected=null;if(!NativeLandingClient.active()||Minecraft.getInstance().options.hideGui)return;var direction=bearing();if(direction==null)return;
        // Guidance follows the surface; the projected point uses the real camera.
        var hull=NativeHelmClient.body().logicalPose().position();var eye=context.camera().getPosition();
        var camera=new org.joml.Matrix4d(context.positionMatrix()).transformDirection(new Vector3d(hull.x+direction.x()-eye.x,NativeGroundCoordinates.FLOOR+marker.pose().point().y()-eye.y,hull.z+direction.z()-eye.z));var p=context.projectionMatrix();var mc=Minecraft.getInstance();
        projected=NativeSpaceNavigation.project(new BentStripGeometry.Point(camera.x,camera.y,camera.z),2*Math.atan(1/p.m11()),p.m11()/p.m00(),12.0/mc.getWindow().getGuiScaledWidth(),36.0/mc.getWindow().getGuiScaledHeight());
    }
    static NativeFlightSnapshot.Home home(){var read=guidance();var point=active();return point==null||read==null?null:new NativeFlightSnapshot.Home(name(point),point.planet(),read.distance(),read.angle(),projected);}

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        LocalNet.clientReceiver(NativeWaypointPackets.Target.TYPE, (packet, context) -> {
            if (!NativeWaypointNavigation.accepts(packet.session(),NativeGroundFlightClient.retainedSession(),NativeSpaceClient.retainedSession())) return;
            marker = packet.marker();
            session = packet.session();
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event)-> {
            marker = null;
            session = 0;
        });
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event)->event.registerAboveAll(net.minecraft.resources.ResourceLocation.parse("starfold:native_waypoint_client_hud"),(graphics, timer) ->{com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();try{render(graphics);}finally{com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();}}));
    }

    private static void render(GuiGraphics graphics) {
        var client = Minecraft.getInstance();
        if (NativeLandingClient.active() || NativeSpaceClient.present()) return;
        if (client.options.hideGui || !NativeGroundClient.ready() || !NativeGroundFlightClient.ready() || marker == null
                || session != NativeGroundFlightClient.session()) return;
        var model = NativeGroundFlightClient.model();
        if (marker.planet() >= model.count()) return;
        var state = NativeGroundFlightClient.state();
        double tick = state.tick();
        var target = marker.world(model, tick).sub(NativeGroundDynamics.position(model, state, tick));
        var orientation = NativeGroundFlightClient.controlled()
                ? NativeGroundDynamics.orientation(model, state, tick)
                : model.at(state.planet(), tick).rotation()
                        .mul(NativeOrbit.frame(NativeGroundClient.shape(),NativeGroundClient.pose()).rotation())
                        .mul(NativeGroundCamera.capture(NativeGroundClient.frame().yawToStorage(client.player.getYRot()), client.player.getXRot()))
                        .normalize();
        var direction = orientation.conjugate().transform(new Vector3d(target.x(), target.y(), target.z()));
        double yaw = Math.toDegrees(Math.atan2(direction.x, direction.z));
        double pitch = Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
        // Under a vessel's own lines in flight; at the top of the screen on foot.
        int top = NativeGroundClient.DEBUG_HUD ? 114 : NativeGroundFlightClient.controlled() ? 56 : 8;
        graphics.drawString(client.font, net.minecraft.client.resources.language.I18n.get("hud.starfold.marker", name(marker),
                NativePlanetSystem.name(marker.planet()), NativeGroundFlightClient.distance(target.length())), 8, top, 0x88ffff, true);
        graphics.drawString(client.font, net.minecraft.client.resources.language.I18n.get("hud.starfold.bearing",
                net.minecraft.client.resources.language.I18n.get(yaw >= 0 ? "hud.starfold.left" : "hud.starfold.right"), String.format(Locale.ROOT, "%.0f", Math.abs(yaw)),
                net.minecraft.client.resources.language.I18n.get(pitch >= 0 ? "hud.starfold.up" : "hud.starfold.down"), String.format(Locale.ROOT, "%.0f", Math.abs(pitch)))
                + (direction.z < 0 ? net.minecraft.client.resources.language.I18n.get("hud.starfold.behind") : ""), 8, top + 11, 0x88ffff, true);
    }

    private NativeWaypointClient() {}
}
