package dev.starfold.space;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.flight.ArrivalState;
import dev.starfold.flight.FlightMode;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.flight.HudLayout;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetIdentity;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.probe.ProbeState;
import dev.starfold.ship.HelmClient;
import dev.starfold.ship.HelmInputClient;
import dev.starfold.ship.HullLandingClient;
import dev.starfold.ship.SpacePackets;
import dev.starfold.ship.Thrusters;
import dev.starfold.sky.Space;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.*;

/** Projection capture and input adapter. Widgets consume one immutable frame, never query flight state. */
public final class SpaceNavigationClient {
    private static FlightSnapshot snapshot;
    private static net.minecraft.client.multiplayer.ClientLevel level;
    private static String receipt = "", motionReceipt = "";
    private static long drawnTick;
    private static net.minecraft.client.KeyMapping lockKey, nextKey;
    private static int locked = -1;
    private static Object lockBody, lockLevel, hintedBody;
    private static List<SpaceNavigation.Candidate> candidates = List.of();

    private static void validateLock() {
        var mc = Minecraft.getInstance();
        var body = HelmClient.body();
        if (body != hintedBody && body == null) hintedBody = null;
        if (!SpaceClient.active()
                || body == null
                || HelmClient.tier() < 2
                || body != lockBody
                || mc.level != lockLevel
                || locked >= SpaceClientHost.get().ephemeris().count()) lock(-1);
        lockBody = body;
        lockLevel = mc.level;
    }

    private static void lock(int planet) {
        if (locked == planet) return;
        locked = planet;
        var mc = Minecraft.getInstance();
        if (mc.getConnection() != null)
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new SpacePackets.Target(planet));
        if (planet >= 0 && mc.player != null && HelmClient.body() != null) {
            var ids = cycle();
            String text =
                    caption(
                            planet,
                            ids.indexOf(planet) + 1,
                            ids.size(),
                            mc.getWindow().getGuiScaledWidth() - 32);
            mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(text), true);
        }
    }

    private static List<Integer> cycle() {
        var frame = SpaceClient.frame();
        var model = SpaceClientHost.get().ephemeris();
        double tick = SpaceClient.time();
        var at = HelmClient.body().logicalPose().position();
        return SpaceNavigation.cycle(
                model,
                frame.universe(new Point(at.x, at.y, at.z), model, tick),
                tick,
                frame.planet());
    }

    public static String caption(int planet, int rank, int count, int width) {
        return PlanetIdentity.caption(
                planet,
                PlanetSystem.identity(planet)
                        .label(net.minecraft.client.resources.language.I18n::get),
                rank,
                count,
                width,
                Minecraft.getInstance().font::width);
    }

    /** Before vanilla queues an offhand click (Key input events are too late to cancel the key). */
    public static boolean key(long window, int key, int scan, int action) {
        var mc = Minecraft.getInstance();
        if (window != mc.getWindow().getWindow()
                || mc.screen != null
                || lockKey == null
                || nextKey == null
                || (!lockKey.matches(key, scan) && !nextKey.matches(key, scan))
                || HelmClient.body() == null) return false;
        validateLock();
        if (HelmClient.tier() < 2) {
            if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS && hintedBody != HelmClient.body()) {
                hintedBody = HelmClient.body();
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "hud.starfold.instrument_scope_required"),
                        true);
            }
            return true;
        }
        if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS && visible() && level == mc.level) {
            if (nextKey.matches(key, scan)) {
                var frame = SpaceClient.frame();
                var model = SpaceClientHost.get().ephemeris();
                double tick = SpaceClient.time();
                var at = HelmClient.body().logicalPose().position();
                lock(
                        SpaceNavigation.next(
                                model,
                                frame.universe(new Point(at.x, at.y, at.z), model, tick),
                                tick,
                                locked,
                                frame.planet()));
                return true;
            }
            int chosen =
                    SpaceNavigation.choose(
                            candidates,
                            mc.getWindow().getGuiScaledWidth(),
                            mc.getWindow().getGuiScaledHeight());
            boolean empty = chosen < 0 && locked < 0;
            lock(chosen == locked ? -1 : chosen);
            if (empty)
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "hud.starfold.space_no_target"),
                        true);
        }
        return true;
    }

    public static int locked() {
        validateLock();
        return locked;
    }

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        lockKey =
                new net.minecraft.client.KeyMapping(
                        "key.starfold.lock_planet",
                        com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                        org.lwjgl.glfw.GLFW.GLFW_KEY_F,
                        "key.categories.starfold");
        nextKey =
                new net.minecraft.client.KeyMapping(
                        "key.starfold.next_planet",
                        com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                        org.lwjgl.glfw.GLFW.GLFW_KEY_G,
                        "key.categories.starfold");
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) -> {
                    event.register(lockKey);
                    event.register(nextKey);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> {
                    validateLock();
                    HullLandingClient.tick();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (RenderLevelStageEvent event) -> {
                    if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL)
                        capture(new RenderFrame(event));
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderGuiLayerEvent.Pre event) -> {
                    if (HelmClient.body() != null
                            && (event.getName()
                                            .equals(
                                                    net.neoforged.neoforge.client.gui
                                                            .VanillaGuiLayers.HOTBAR)
                                    || event.getName()
                                            .equals(
                                                    net.neoforged.neoforge.client.gui
                                                            .VanillaGuiLayers.CROSSHAIR)))
                        event.setCanceled(true);
                });
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) ->
                        event.registerAboveAll(
                                net.minecraft.resources.ResourceLocation.parse(
                                        "starfold:space_navigation"),
                                (g, t) -> render(g)));
    }

    private static boolean visible() {
        var mc = Minecraft.getInstance();
        return SpaceClient.active()
                && !GroundFlightClient.controlled()
                && !mc.options.hideGui
                && mc.screen == null;
    }

    /** Capture the world projection before vanilla replaces it with the GUI orthographic projection. */
    private static Point hullBearing(
            Space.Frame frame,
            GroundEphemeris.Model model,
            int bearingPlanet,
            double tick,
            Point pivot,
            dev.ryanhcode.sable.sublevel.ClientSubLevel helm) {
        var direction = frame.levelVector(model.at(bearingPlanet, tick).center().sub(pivot));
        var hullDirection =
                helm.logicalPose()
                        .orientation()
                        .conjugate(new org.joml.Quaterniond())
                        .transform(
                                new org.joml.Vector3d(direction.x(), direction.y(), direction.z()));
        var facing = HelmInputClient.facing(helm);
        var forward = new org.joml.Vector3d(facing.getStepX(), 0, facing.getStepZ());
        var right = new org.joml.Vector3d(forward).cross(0, 1, 0);
        return new Point(hullDirection.dot(right), hullDirection.y, hullDirection.dot(forward));
    }

    private static void captureMotionReceipt(
            GroundEphemeris.Model model,
            int relativeTo,
            double tick,
            Point eye,
            Point velocity,
            int width,
            int height,
            Space.Frame frame,
            org.joml.Vector3d cameraVelocity) {
        motionReceipt = "";
        if (ProbeState.active()) {
            var toward = model.at(relativeTo, tick).center().sub(eye);
            double separation =
                    Math.toDegrees(
                            Math.acos(
                                    Math.clamp(
                                            velocity.dot(toward)
                                                    / Math.max(
                                                            1e-12,
                                                            velocity.length() * toward.length()),
                                            -1,
                                            1)));
            motionReceipt =
                    " | viewport "
                            + width
                            + "x"
                            + height
                            + " | reference "
                            + frame.planet()
                            + " drift "
                            + frame.drift()
                            + " | relativeVelocity "
                            + velocity
                            + " cameraVelocity "
                            + cameraVelocity
                            + " separation "
                            + separation;
        }
    }

    private static void capture(RenderFrame context) {
        validateLock();
        snapshot = null;
        candidates = List.of();
        receipt = "";
        level = null;
        var mc = Minecraft.getInstance();
        var helm = HelmClient.body();
        int tier = HelmClient.tier();
        if (helm == null || tier == 0 || mc.options.hideGui || mc.screen != null) return;
        SpaceClientHost.get().captureTarget(context);
        boolean chart = HullLandingClient.active();
        if (!chart && !visible()) return;
        var pointer =
                SpaceAttitudeClient.drawnPointer(mc.getTimer().getGameTimeDeltaPartialTick(false));
        double pointerScale = mc.getWindow().getGuiScaledHeight() / 3.0 / SpaceAttitudeClient.AIM;
        var thrust = thrust(helm);
        var flight = HelmInputClient.state(helm.getUniqueId());
        if (flight == null) return;
        boolean boost = flight.boosted();
        int launchPlanet = SpaceClientHost.get().launchPlanet();
        if (chart) {
            captureChart(mc, helm, tier, thrust, flight, boost, launchPlanet);
            return;
        }
        captureSpace(
                context,
                mc,
                helm,
                tier,
                pointer,
                pointerScale,
                thrust,
                flight,
                boost,
                launchPlanet);
    }

    private static void captureChart(
            Minecraft mc,
            dev.ryanhcode.sable.sublevel.ClientSubLevel helm,
            int tier,
            FlightSnapshot.Thrust thrust,
            dev.starfold.ship.HelmPackets.State flight,
            boolean boost,
            int launchPlanet) {
        int planet = SpaceClientHost.get().planet();
        var ground = HullLandingClient.ground();
        var motion = hullVector(helm, HullLandingClient.motion());
        var up = hullVector(helm, new Point(0, 1, 0));
        var nose = HelmClient.forward(helm);
        var bounds = helm.getPlot().getBoundingBox();
        var lift = HelmInputClient.lift(helm);
        var landing =
                new FlightSnapshot.Landing(
                        lift != null ? lift.limit() : Double.NaN,
                        motion,
                        Math.atan2(-up.x(), up.y()),
                        Math.asin(Math.clamp(nose.y, -1, 1)),
                        bounds.maxX() - bounds.minX() + 1,
                        bounds.maxZ() - bounds.minZ() + 1,
                        HullLandingClient.site());
        snapshot =
                new FlightSnapshot(
                        tier,
                        FlightMode.CHART,
                        planet,
                        Math.hypot(HullLandingClient.horizontal(), HullLandingClient.vertical()),
                        planet,
                        flight.cruiseCap(),
                        flight.boostCap(),
                        boost,
                        0,
                        null,
                        List.of(),
                        GroundEphemeris.ZERO,
                        null,
                        false,
                        GroundEphemeris.ZERO,
                        planet,
                        thrust,
                        0,
                        0,
                        ground.clearance(),
                        HullLandingClient.vertical(),
                        HullLandingClient.horizontal(),
                        launchPlanet,
                        null,
                        landing,
                        SpaceClientHost.get().home(),
                        ArrivalState.Phase.CRUISE,
                        0,
                        0);
        level = mc.level;
    }

    private static void captureSpace(
            RenderFrame context,
            Minecraft mc,
            dev.ryanhcode.sable.sublevel.ClientSubLevel helm,
            int tier,
            dev.starfold.space.SpaceAttitudeClient.Pointer pointer,
            double pointerScale,
            FlightSnapshot.Thrust thrust,
            dev.starfold.ship.HelmPackets.State flight,
            boolean boost,
            int launchPlanet) {
        var frame = SpaceClient.frame();
        var model = SpaceClientHost.get().ephemeris();
        double tick = SpaceClient.time();
        var eye =
                SpaceClient.universeEye(
                        mc.player.getEyePosition(mc.getTimer().getGameTimeDeltaPartialTick(false)));
        var viewEye = SpaceClient.universeEye(context.camera().getPosition());
        var projection = context.projectionMatrix();
        double fov = 2 * Math.atan(1 / projection.m11()),
                aspect = projection.m11() / projection.m00();
        int width = mc.getWindow().getGuiScaledWidth(),
                height = mc.getWindow().getGuiScaledHeight();
        var list = new ArrayList<FlightSnapshot.Target>();
        var choices = new ArrayList<SpaceNavigation.Candidate>();
        var nearest = SpaceView.nearest(model, eye, tick, 4);
        var nose = HelmClient.forward(helm);
        for (int id = 0; id < model.count(); id++) {
            var relative = SpaceView.relative(frame, viewEye, model.at(id, tick).center());
            var marker = projectTarget(context, relative, fov, aspect, width, height);
            var toward = frame.levelVector(model.at(id, tick).center().sub(eye));
            double angle = targetAngle(toward, nose);
            if (nearest.contains(id) || id == locked) {
                var read = SpaceNavigation.read(model, frame, eye, id, tick);
                list.add(
                        new FlightSnapshot.Target(
                                id, marker, read.distance(), read.closing(), angle));
            }
            choices.add(new SpaceNavigation.Candidate(id, marker, angle));
        }
        var at = helm.logicalPose().position();
        var pivot = frame.universe(new Point(at.x, at.y, at.z), model, tick);
        int planet = Space.nearest(model, pivot, tick);
        var velocity = SpaceNavigation.velocity(model, frame, locked, tick);
        var local = frame.levelVector(velocity);
        var cameraVelocity =
                new org.joml.Matrix4d(context.positionMatrix())
                        .transformDirection(new org.joml.Vector3d(local.x(), local.y(), local.z()));
        var shown =
                SpaceNavigation.velocityMarker(
                        new Point(cameraVelocity.x, cameraVelocity.y, cameraVelocity.z));
        var velocityMarker =
                shown == null
                        ? null
                        : SpaceNavigation.project(
                                shown.camera(),
                                fov,
                                aspect,
                                Math.min(.2, 12.0 / width),
                                Math.min(.2, 24.0 / height));
        int relativeTo = locked >= 0 ? locked : frame.planet();
        captureMotionReceipt(
                model, relativeTo, tick, eye, velocity, width, height, frame, cameraVelocity);
        int bearingPlanet = SpaceClient.arriving() ? planet : frame.planet();
        var bearing = hullBearing(frame, model, bearingPlanet, tick, pivot, helm);
        var cycle = SpaceNavigation.cycle(model, pivot, tick, frame.planet());
        list.replaceAll(
                t ->
                        new FlightSnapshot.Target(
                                t.planet(),
                                t.marker(),
                                t.distance(),
                                t.closing(),
                                t.angle(),
                                cycle.indexOf(t.planet()) + 1,
                                cycle.size(),
                                tier >= 2 && t.planet() == locked
                                        ? SpaceNavigation.blocker(model, pivot, locked, tick)
                                        : -1));
        sortNearestTargets(list, nearest);
        var target = list.stream().filter(t -> t.planet() == locked).findFirst().orElse(null);
        var arrival =
                SpaceClient.phase() != ArrivalState.Phase.CRUISE
                        ? arrival(context, helm, frame, model, pivot, planet, tick)
                        : null;
        snapshot =
                new FlightSnapshot(
                        tier,
                        SpaceClient.arriving() ? FlightMode.ARRIVAL : FlightMode.FREE,
                        planet,
                        velocity.length() * 20,
                        relativeTo,
                        flight.cruiseCap(),
                        flight.boostCap(),
                        boost,
                        target == null ? 0 : target.closing(),
                        target,
                        list,
                        velocity,
                        velocityMarker,
                        shown != null && shown.retrograde(),
                        bearing,
                        bearingPlanet,
                        thrust,
                        pointer.x() * pointerScale,
                        pointer.y() * pointerScale,
                        Double.NaN,
                        0,
                        0,
                        launchPlanet,
                        arrival,
                        null,
                        null,
                        SpaceClient.phase(),
                        SpaceClient.blend(),
                        SpaceClient.notice());
        candidates = List.copyOf(choices);
        level = mc.level;
    }

    private static void sortNearestTargets(
            List<FlightSnapshot.Target> list, List<Integer> nearest) {
        list.sort(
                Comparator.comparingInt(
                        t ->
                                nearest.contains(t.planet())
                                        ? nearest.indexOf(t.planet())
                                        : nearest.size()));
    }

    private static SpaceNavigation.Marker projectTarget(
            RenderFrame context, Point relative, double fov, double aspect, int width, int height) {
        var camera =
                new org.joml.Matrix4d(context.positionMatrix())
                        .transformPosition(
                                new org.joml.Vector3d(relative.x(), relative.y(), relative.z()));
        return SpaceNavigation.project(
                new Point(camera.x, camera.y, camera.z),
                fov,
                aspect,
                Math.min(.2, 12.0 / width),
                Math.min(.2, 24.0 / height));
    }

    private static double targetAngle(Point toward, org.joml.Vector3d nose) {
        return Math.toDegrees(
                Math.acos(
                        Math.clamp(
                                (toward.x() * nose.x + toward.y() * nose.y + toward.z() * nose.z)
                                        / Math.max(1e-12, toward.length()),
                                -1,
                                1)));
    }

    private static Point hullVector(
            dev.ryanhcode.sable.sublevel.ClientSubLevel hull, Point vector) {
        var v =
                hull.logicalPose()
                        .orientation()
                        .conjugate(new org.joml.Quaterniond())
                        .transform(new org.joml.Vector3d(vector.x(), vector.y(), vector.z()));
        var facing = HelmInputClient.facing(hull);
        var forward = new org.joml.Vector3d(facing.getStepX(), 0, facing.getStepZ());
        return new Point(v.dot(new org.joml.Vector3d(forward).cross(0, 1, 0)), v.y, v.dot(forward));
    }

    private static FlightSnapshot.Arrival arrival(
            RenderFrame context,
            dev.ryanhcode.sable.sublevel.ClientSubLevel hull,
            Space.Frame frame,
            GroundEphemeris.Model model,
            Point pivot,
            int planet,
            double tick) {
        double height = Space.height(model, planet, pivot, tick);
        var toward = model.at(planet, tick).center().sub(pivot);
        var radial = toward.scale(1 / Math.max(1e-12, toward.length()));
        var relative =
                frame.universeVelocity(GroundEphemeris.ZERO, model, tick)
                        .sub(model.at(planet, tick).velocity());
        var d = hullVector(hull, frame.levelVector(radial));
        var point = pivot.add(radial.scale(Math.max(0, height - Space.IN)));
        var viewEye = SpaceClient.universeEye(context.camera().getPosition());
        var local = SpaceView.relative(frame, viewEye, point);
        var camera =
                new org.joml.Matrix4d(context.positionMatrix())
                        .transformPosition(new org.joml.Vector3d(local.x(), local.y(), local.z()));
        var p = context.projectionMatrix();
        var marker =
                SpaceNavigation.project(
                        new Point(camera.x, camera.y, camera.z),
                        2 * Math.atan(1 / p.m11()),
                        p.m11() / p.m00(),
                        .05,
                        .1);
        return new FlightSnapshot.Arrival(
                Math.max(0, height - Space.IN),
                relative.dot(radial) * 20,
                SpaceServer.arrivalLimit(height),
                SpaceServer.nearLimit(height),
                marker,
                1 - Math.acos(Math.clamp(-d.y(), -1, 1)) / Math.PI,
                new FlightSnapshot.Thrust(
                        Math.abs(d.z()) < 1e-3,
                        Math.abs(d.z()) < 1e-3,
                        Math.abs(d.x()) < 1e-3,
                        Math.abs(d.x()) < 1e-3,
                        false,
                        Math.abs(d.y()) < 1e-3));
    }

    private static FlightSnapshot.Thrust thrust(dev.ryanhcode.sable.sublevel.ClientSubLevel hull) {
        boolean f = false, b = false, l = false, r = false, u = false, d = false;
        var facing = HelmInputClient.facing(hull);
        var nose = new org.joml.Vector3d(facing.getStepX(), 0, facing.getStepZ());
        var right = new org.joml.Vector3d(nose).cross(0, 1, 0);
        for (var actor : hull.getPlot().getBlockEntityActors())
            if (actor instanceof Thrusters.Thruster jet
                    && !jet.isRemoved()
                    && HelmInputClient.power(jet.getLevel(), jet.getBlockPos(), jet.getBlockState())
                            > 0) {
                var push =
                        jet.getBlockState()
                                .getValue(
                                        net.minecraft.world.level.block.state.properties
                                                .BlockStateProperties.FACING)
                                .getOpposite();
                var v = new org.joml.Vector3d(push.getStepX(), push.getStepY(), push.getStepZ());
                f |= v.dot(nose) > .5;
                b |= v.dot(nose) < -.5;
                l |= v.dot(right) < -.5;
                r |= v.dot(right) > .5;
                u |= v.y > .5;
                d |= v.y < -.5;
            }
        var lift = HelmInputClient.lift(hull);
        return new FlightSnapshot.Thrust(
                f,
                b,
                l,
                r,
                u,
                d,
                lift == null ? null : new FlightSnapshot.Lift(lift.up(), lift.insufficient()));
    }

    private static void render(GuiGraphics g) {
        receipt = "";
        var mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null || HelmClient.body() == null) return;
        FlightWidgets.view(g, HelmClient.viewLabel(), HelmClient.viewFlash());
        if (snapshot == null || level != mc.level) return;
        RenderSystem.disableDepthTest();
        try {
            FlightWidgets.render(g, snapshot);
            receipt =
                    " | instruments tier "
                            + snapshot.tier()
                            + " mode "
                            + snapshot.mode()
                            + " speed "
                            + snapshot.speed()
                            + " boost "
                            + snapshot.boosted()
                            + " closing "
                            + snapshot.closing()
                            + " | lock "
                            + locked
                            + " | velocity "
                            + snapshot.velocityMarker()
                            + " retro "
                            + snapshot.retrograde()
                            + " | layout "
                            + HudLayout.layout(g.guiWidth(), g.guiHeight());
            for (var t : snapshot.planets())
                receipt +=
                        " | planet "
                                + t.planet()
                                + " marker "
                                + t.marker()
                                + " angle "
                                + t.angle()
                                + " eta "
                                + t.eta();
            if (ProbeState.active())
                receipt +=
                        " | identities "
                                + PlanetSystem.identities()
                                + " | caption "
                                + (snapshot.target() == null
                                        ? "—"
                                        : caption(
                                                snapshot.target().planet(),
                                                snapshot.target().rank(),
                                                snapshot.target().count(),
                                                HudLayout.layout(g.guiWidth(), g.guiHeight())
                                                        .mode()
                                                        .width()));
            receipt +=
                    " | blocker " + (snapshot.target() == null ? -1 : snapshot.target().blocker());
            receipt +=
                    " | clearance "
                            + snapshot.clearance()
                            + " vertical "
                            + snapshot.vertical()
                            + " horizontal "
                            + snapshot.horizontal()
                            + " arrival "
                            + snapshot.arrival()
                            + " landing "
                            + snapshot.landing()
                            + " home "
                            + snapshot.home()
                            + " launchPlanet "
                            + snapshot.launchPlanet();
            receipt += motionReceipt;
            drawnTick = mc.level.getGameTime();
        } finally {
            RenderSystem.enableDepthTest();
        }
    }

    public static void arrow(GuiGraphics g, int x, int y, double angle, int color) {
        double dx = Math.cos(angle), dy = Math.sin(angle);
        for (int i = 0; i <= 6; i++)
            for (int sign : new int[] {-1, 1}) {
                int px = (int) Math.round(x - dx * i - dy * i * .6 * sign),
                        py = (int) Math.round(y - dy * i + dx * i * .6 * sign);
                g.fill(px, py, px + 2, py + 2, color | 0xff000000);
            }
    }

    public static String probeReading() {
        var mc = Minecraft.getInstance();
        return snapshot != null && level == mc.level && mc.level.getGameTime() - drawnTick <= 2
                ? receipt
                : "";
    }

    private SpaceNavigationClient() {}
}
