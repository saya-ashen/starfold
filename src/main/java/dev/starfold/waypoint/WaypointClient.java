package dev.starfold.waypoint;

import dev.starfold.chart.GroundCamera;
import dev.starfold.chart.PlanetOrbit;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.GroundDynamics;
import dev.starfold.planet.GroundTopology;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.ship.HelmClient;
import dev.starfold.ship.HullLandingClient;
import dev.starfold.space.FlightSnapshot;
import dev.starfold.space.RenderFrame;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceNavigation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import org.joml.Vector3d;

import java.util.Locale;

/** Read-only target HUD. Uses the moving planet frame, not stored universe coordinates. */
public final class WaypointClient {
    private static Waypoint marker;
    private static long session;
    private static SpaceNavigation.Marker projected;

    public record Guidance(double distance, double angle) {}

    public static Waypoint active() {
        long current = SpaceClient.present() ? SpaceClient.session() : GroundFlightClient.session();
        return current != 0 && session == current ? marker : null;
    }

    private static String name(Waypoint point) {
        return point.name().equals(Waypoint.LAUNCH)
                ? net.minecraft.client.resources.language.I18n.get("hud.starfold.launch_site")
                : point.name();
    }

    private static BentStripGeometry.Point bearing() {
        var hull = HelmClient.body();
        if (hull == null
                || active() == null
                || marker.planet() != WaypointClientHost.get().planet()) return null;
        var at = hull.logicalPose().position();
        var frame = WaypointClientHost.get().frame();
        var storage = frame.toStorage(new BentStripGeometry.Point(at.x, at.y, at.z));
        var access = WaypointClientHost.get().access();
        var from =
                GroundTopology.canonical(
                        WaypointClientHost.get().shape(),
                        CubeSurfaceTopology.Face.values()[frame.root()],
                        access.chart(storage));
        var route =
                WaypointNavigation.route(
                        WaypointClientHost.get().shape(), from.pose(), marker.pose());
        return route == null
                ? null
                : frame.vectorToClient(ChartLabSpace.rotate(route.direction(), -from.turns()));
    }

    public static Guidance guidance() {
        if (!HullLandingClient.active()) return null;
        var direction = bearing();
        if (direction == null) return null;
        var nose = HelmClient.forward(HelmClient.body());
        double facing = Math.toDegrees(Math.atan2(-nose.x, nose.z));
        double angle = WaypointNavigation.angle(facing, direction);
        return new Guidance(direction.length(), angle);
    }

    public static void capture(RenderFrame context) {
        projected = null;
        if (!HullLandingClient.active() || Minecraft.getInstance().options.hideGui) return;
        var direction = bearing();
        if (direction == null) return;
        // Guidance follows the surface; the projected point uses the real camera.
        var hull = HelmClient.body().logicalPose().position();
        var eye = context.camera().getPosition();
        var camera =
                new org.joml.Matrix4d(context.positionMatrix())
                        .transformDirection(
                                new Vector3d(
                                        hull.x + direction.x() - eye.x,
                                        GroundCoordinates.FLOOR + marker.pose().point().y() - eye.y,
                                        hull.z + direction.z() - eye.z));
        var p = context.projectionMatrix();
        var mc = Minecraft.getInstance();
        projected =
                SpaceNavigation.project(
                        new BentStripGeometry.Point(camera.x, camera.y, camera.z),
                        2 * Math.atan(1 / p.m11()),
                        p.m11() / p.m00(),
                        12.0 / mc.getWindow().getGuiScaledWidth(),
                        36.0 / mc.getWindow().getGuiScaledHeight());
    }

    public static FlightSnapshot.Home home() {
        var read = guidance();
        var point = active();
        return point == null || read == null
                ? null
                : new FlightSnapshot.Home(
                        name(point), point.planet(), read.distance(), read.angle(), projected);
    }

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        StarfoldNetwork.clientReceiver(
                WaypointPackets.Target.TYPE,
                (packet, context) -> {
                    if (!WaypointNavigation.accepts(
                            packet.session(),
                            GroundFlightClient.retainedSession(),
                            SpaceClient.retainedSession())) return;
                    marker = packet.marker();
                    session = packet.session();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) -> {
                    marker = null;
                    session = 0;
                });
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) ->
                        event.registerAboveAll(
                                net.minecraft.resources.ResourceLocation.parse(
                                        "starfold:native_waypoint_client_hud"),
                                (graphics, timer) -> {
                                    com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
                                    try {
                                        render(graphics);
                                    } finally {
                                        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
                                    }
                                }));
    }

    private static void render(GuiGraphics graphics) {
        var client = Minecraft.getInstance();
        if (HullLandingClient.active() || SpaceClient.present()) return;
        if (client.options.hideGui
                || !WaypointClientHost.get().ready()
                || !GroundFlightClient.ready()
                || marker == null
                || session != GroundFlightClient.session()) return;
        var model = GroundFlightClient.model();
        if (marker.planet() >= model.count()) return;
        var state = GroundFlightClient.state();
        double tick = state.tick();
        var target = marker.world(model, tick).sub(GroundDynamics.position(model, state, tick));
        var orientation =
                GroundFlightClient.controlled()
                        ? GroundDynamics.orientation(model, state, tick)
                        : model.at(state.planet(), tick)
                                .rotation()
                                .mul(
                                        PlanetOrbit.frame(
                                                        WaypointClientHost.get().shape(),
                                                        WaypointClientHost.get().pose())
                                                .rotation())
                                .mul(
                                        GroundCamera.capture(
                                                WaypointClientHost.get()
                                                        .frame()
                                                        .yawToStorage(client.player.getYRot()),
                                                client.player.getXRot()))
                                .normalize();
        var direction =
                orientation.conjugate().transform(new Vector3d(target.x(), target.y(), target.z()));
        double yaw = Math.toDegrees(Math.atan2(direction.x, direction.z));
        double pitch =
                Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
        // Under a vessel's own lines in flight; at the top of the screen on foot.
        int top =
                WaypointClientHost.get().diagnosticHud()
                        ? 114
                        : GroundFlightClient.controlled() ? 56 : 8;
        graphics.drawString(
                client.font,
                net.minecraft.client.resources.language.I18n.get(
                        "hud.starfold.marker",
                        name(marker),
                        PlanetSystem.name(marker.planet()),
                        GroundFlightClient.distance(target.length())),
                8,
                top,
                0x88ffff,
                true);
        graphics.drawString(
                client.font,
                net.minecraft.client.resources.language.I18n.get(
                                "hud.starfold.bearing",
                                net.minecraft.client.resources.language.I18n.get(
                                        yaw >= 0 ? "hud.starfold.left" : "hud.starfold.right"),
                                String.format(Locale.ROOT, "%.0f", Math.abs(yaw)),
                                net.minecraft.client.resources.language.I18n.get(
                                        pitch >= 0 ? "hud.starfold.up" : "hud.starfold.down"),
                                String.format(Locale.ROOT, "%.0f", Math.abs(pitch)))
                        + (direction.z < 0
                                ? net.minecraft.client.resources.language.I18n.get(
                                        "hud.starfold.behind")
                                : ""),
                8,
                top + 11,
                0x88ffff,
                true);
    }

    private WaypointClient() {}
}
