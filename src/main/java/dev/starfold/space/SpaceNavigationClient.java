package dev.starfold.space;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.starfold.chart.CelestialTarget;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.flight.ArrivalState;
import dev.starfold.flight.FlightMode;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.flight.HudLayout;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetIdentity;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.DevelopmentHooks;
import dev.starfold.ship.HelmClient;
import dev.starfold.ship.HelmInputClient;
import dev.starfold.ship.HullLandingClient;
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
    private static CelestialTarget selection;
    private static java.util.UUID shipSelection;
    private static boolean mapSelection, lockSpace;
    private static RemoteReading remote;
    private record RemoteReading(String name, SpaceNavigation.Marker marker, double distance, double closing) {}
    private static Object lockBody, lockLevel, hintedBody;
    private static List<CelestialNavigation.Candidate> candidates = List.of();

    public static void resetWorld() {
        snapshot = null;
        level = null;
        remote = null;
        candidates = List.of();
        receipt = motionReceipt = "";
        selection = null;
        shipSelection = null;
        lockBody = lockLevel = hintedBody = null;
        mapSelection = lockSpace = false;
        drawnTick = 0;
    }

    private static void validateLock() {
        var mc = Minecraft.getInstance();
        var body = HelmClient.body();
        if (body != hintedBody && body == null) hintedBody = null;
        boolean space = SpaceClient.active();
        if (mapSelection && mc.level == lockLevel) {
            if (selection != null && !CelestialNavigation.knownTarget(SpaceClientHost.get().ephemeris(), selection.bodyIndex())
                    || shipSelection != null && dev.starfold.ship.ShipMapClient.find(shipSelection) == null) { lock(null); return; }
            if (body != lockBody || space != lockSpace) {
                if (space && body != null) {
                    if (shipSelection != null) net.neoforged.neoforge.network.PacketDistributor.sendToServer(new dev.starfold.ship.ShipMapPackets.Target(-1, shipSelection));
                    else if (selection != null) net.neoforged.neoforge.network.PacketDistributor.sendToServer(new SpacePackets.Target(selection.bodyIndex()));
                }
            }
            lockBody = body; lockSpace = space;
            return;
        }
        if (!space
                || body == null
                || HelmClient.tier() < 2
                || body != lockBody
                || mc.level != lockLevel
                || selection != null
                        && !CelestialNavigation.knownTarget(
                                SpaceClientHost.get().ephemeris(), selection.bodyIndex()))
            lock(null);
        lockBody = body;
        lockLevel = mc.level;
    }

    /** External instruments use the same local selection and server target intent as the helm. */
    public static void target(int bodyIndex) {
        lock(CelestialTarget.resolve(SpaceClientHost.get().ephemeris(), bodyIndex));
        mapSelection = true;
        lockBody = HelmClient.body(); lockLevel = Minecraft.getInstance().level; lockSpace = SpaceClient.active();
    }

    public static void targetShip(java.util.UUID ship) {
        lock(null);
        shipSelection = ship; mapSelection = true;
        lockBody = HelmClient.body(); lockLevel = Minecraft.getInstance().level; lockSpace = SpaceClient.active();
    }

    private static void lock(CelestialTarget target) {
        if (Objects.equals(selection, target) && shipSelection == null && !mapSelection) return;
        shipSelection = null; mapSelection = false;
        selection = target;
        var mc = Minecraft.getInstance();
        if (mc.getConnection() != null)
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new SpacePackets.Target(target == null ? -1 : target.bodyIndex()));
        if (target != null && mc.player != null && HelmClient.body() != null && SpaceClient.active()) {
            var ids = cycle();
            var model = SpaceClientHost.get().ephemeris();
            var body = target.body(model);
            String text = body.light() ? body.id().region() + "-" + body.id().index() :
                    body.gas().isPresent()
                            ? net.minecraft.client.resources.language.I18n.get(
                                            body.gas().orElseThrow().name())
                                    + " · "
                                    + net.minecraft.client.resources.language.I18n.get(
                                            "hud.starfold.instrument_gas_nonlandable")
                            : caption(
                                    target.requireSurfaceSlot(model),
                                    ids.indexOf(target) + 1,
                                    ids.size(),
                                    mc.getWindow().getGuiScaledWidth() - 32);
            mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(text), true);
        }
    }

    private static int surfaceLocked() {
        return selection == null
                ? -1
                : selection.surfaceSlot(SpaceClientHost.get().ephemeris()).orElse(-1);
    }

    private static List<CelestialTarget> cycle() {
        var frame = SpaceClient.frame();
        var model = SpaceClientHost.get().ephemeris();
        double tick = SpaceClient.time();
        var at = HelmClient.body().logicalPose().position();
        return CelestialNavigation.cycle(
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
                        CelestialNavigation.next(
                                        model,
                                        frame.universe(new Point(at.x, at.y, at.z), model, tick),
                                        tick,
                                        selection,
                                        frame.planet())
                                .orElse(null));
                return true;
            }
            var chosen =
                    CelestialNavigation.choose(
                                    candidates,
                                    mc.getWindow().getGuiScaledWidth(),
                                    mc.getWindow().getGuiScaledHeight())
                            .orElse(null);
            boolean empty = chosen == null && selection == null;
            lock(Objects.equals(chosen, selection) ? null : chosen);
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
        return surfaceLocked();
    }

    public static int selectedBodyIndex() {
        validateLock();
        return selection == null ? -1 : selection.bodyIndex();
    }

    public static UUID selectedShipId() {
        validateLock();
        return shipSelection;
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
        if (DevelopmentHooks.driving.getAsBoolean()) {
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
        remote = null;
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
                        flight.deltaV(),
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
        var choices = new ArrayList<CelestialNavigation.Candidate>();
        int locked = surfaceLocked();
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
            choices.add(
                    new CelestialNavigation.Candidate(
                            CelestialTarget.resolve(model, model.index(id)), marker, angle));
        }
        var at = helm.logicalPose().position();
        var pivot = frame.universe(new Point(at.x, at.y, at.z), model, tick);
        int planet = Space.nearest(model, pivot, tick);
        var shipTarget = shipSelection == null ? null : dev.starfold.ship.ShipMapClient.find(shipSelection);
        var velocity = shipTarget == null ? CelestialNavigation.velocity(model, frame, selection, tick)
                : frame.universeVelocity(GroundEphemeris.ZERO, model, tick).sub(MapNavigation.velocity(model, shipTarget, tick));
        Point destination = shipTarget == null ? null : MapNavigation.position(model, shipTarget, tick);
        if (selection != null && selection.body(model).light()) destination = selection.frame(model, tick).center();
        if (destination != null && tier >= 2) {
            var marker = projectTarget(context, SpaceView.relative(frame, viewEye, destination), fov, aspect, width, height);
            String name = shipTarget == null ? bodyName(model, selection)
                    : shipTarget.name().orElse("SHIP-" + shipTarget.id().region() + "-" + shipTarget.id().index());
            remote = new RemoteReading(name, marker, destination.sub(pivot).length(),
                    velocity.dot(destination.sub(pivot)) / Math.max(1e-12, destination.sub(pivot).length()) * 20);
        }
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
        if (selection != null && selection.body(model).gas().isPresent())
            motionReceipt = " | gasRelativeBody " + selection.bodyIndex() + " velocity " + velocity;
        else
            captureMotionReceipt(
                    model, relativeTo, tick, eye, velocity, width, height, frame, cameraVelocity);
        int bearingPlanet = SpaceClient.arriving() ? planet : frame.planet();
        var bearing = hullBearing(frame, model, bearingPlanet, tick, pivot, helm);
        var cycle = CelestialNavigation.cycle(model, pivot, tick, frame.planet());
        FlightSnapshot.GasTarget gasTarget = null;
        for (var celestial : CelestialNavigation.targets(model)) {
            var body = celestial.body(model);
            if (body.gas().isEmpty()) continue;
            var placed = celestial.frame(model, tick);
            var marker =
                    projectTarget(
                            context,
                            SpaceView.relative(frame, viewEye, placed.center()),
                            fov,
                            aspect,
                            width,
                            height);
            double angle = targetAngle(frame.levelVector(placed.center().sub(eye)), nose);
            choices.add(new CelestialNavigation.Candidate(celestial, marker, angle));
            if (celestial.equals(selection)) {
                // Geometric distance/closing remain local; pressure/depth are server hull samples.
                var read = CelestialNavigation.read(model, frame, pivot, celestial, tick, 0);
                gasTarget =
                        new FlightSnapshot.GasTarget(
                                celestial,
                                net.minecraft.client.resources.language.I18n.get(
                                        body.gas().orElseThrow().name()),
                                marker,
                                read.distance(),
                                read.closing(),
                                GasPressureClient.selected(celestial.bodyIndex()),
                                cycle.indexOf(celestial) + 1,
                                cycle.size(),
                                CelestialNavigation.blocker(model, pivot, celestial, tick)
                                        .map(b -> bodyName(model, b))
                                        .orElse(null));
            }
        }
        list.replaceAll(
                t -> {
                    var celestial = CelestialTarget.resolve(model, model.index(t.planet()));
                    var blocker =
                            tier >= 2 && t.planet() == locked
                                    ? CelestialNavigation.blocker(model, pivot, celestial, tick)
                                            .orElse(null)
                                    : null;
                    int surfaceBlocker =
                            blocker == null ? -1 : blocker.surfaceSlot(model).orElse(-1);
                    String gasBlocker =
                            blocker != null && blocker.surfaceSlot(model).isEmpty()
                                    ? bodyName(model, blocker)
                                    : null;
                    return new FlightSnapshot.Target(
                            t.planet(),
                            t.marker(),
                            t.distance(),
                            t.closing(),
                            t.angle(),
                            cycle.indexOf(celestial) + 1,
                            cycle.size(),
                            surfaceBlocker,
                            gasBlocker);
                });
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
                        flight.deltaV(),
                        boost,
                        remote != null ? remote.closing() : gasTarget != null
                                ? gasTarget.closing()
                                : target == null ? 0 : target.closing(),
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
                        SpaceClient.notice(),
                        gasTarget);
        candidates = List.copyOf(choices);
        level = mc.level;
    }

    private static String bodyName(GroundEphemeris.Model model, CelestialTarget body) {
        if (body.body(model).light()) return body.body(model).id().region() + "-" + body.body(model).id().index();
        return body.body(model)
                .gas()
                .map(g -> net.minecraft.client.resources.language.I18n.get(g.name()))
                .orElseGet(() -> PlanetSystem.label(body.requireSurfaceSlot(model)).getString());
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
        var point = pivot.add(radial.scale(Math.max(0, height - model.body(planet).hullIn())));
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
                Math.max(0, height - model.body(planet).hullIn()),
                relative.dot(radial) * 20,
                SpaceFlightMath.arrivalLimit(model.body(planet), height),
                SpaceFlightMath.nearLimit(model.body(planet), height),
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

    private static void renderGroundTarget(GuiGraphics g) {
        if (!mapSelection || SpaceClient.present() || !GroundFlightClient.ready()) return;
        var model = SpaceClientHost.get().ephemeris();
        double tick = SpaceClient.time();
        var ship = shipSelection == null ? null : dev.starfold.ship.ShipMapClient.find(shipSelection);
        int slot = ship != null && ship.state() instanceof dev.starfold.ship.ShipRecord.OnSlot place ? place.slot()
                : selection == null ? -1 : selection.surfaceSlot(model).orElse(-1);
        if (slot >= 0 && slot == SpaceClientHost.get().planet()) return;
        var destination = ship != null ? MapNavigation.position(model, ship, tick) : selection == null ? null : selection.frame(model, tick).center();
        if (destination == null) return;
        String name = ship == null ? bodyName(model, selection)
                : ship.name().orElse("SHIP-" + ship.id().region() + "-" + ship.id().index());
        SpaceClientHost.get().groundTarget(g, name, destination);
    }

    private static void render(GuiGraphics g) {
        receipt = "";
        var mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null) return;
        renderGroundTarget(g);
        if (HelmClient.body() == null) return;
        FlightWidgets.view(g, HelmClient.viewLabel(), HelmClient.viewFlash());
        var helm = HelmInputClient.state(HelmClient.body().getUniqueId());
        if (helm != null) FlightWidgets.fuel(g, helm.fuel(), helm.deltaV());
        if (!HullLandingClient.active() && visible()) {
            var frame = SpaceClient.frame();
            var model = SpaceClientHost.get().ephemeris();
            double tick = SpaceClient.time();
            var target = shipSelection == null ? null : dev.starfold.ship.ShipMapClient.find(shipSelection);
            var velocity = target == null ? CelestialNavigation.velocity(model, frame, selection, tick)
                    : frame.universeVelocity(GroundEphemeris.ZERO, model, tick)
                            .sub(MapNavigation.velocity(model, target, tick));
            FlightWidgets.stopping(g, velocity.length() * 20, helm == null ? -1 : helm.deltaV());
        }
        if (snapshot == null || level != mc.level) return;
        RenderSystem.disableDepthTest();
        try {
            FlightWidgets.render(g, snapshot);
            if (remote != null) FlightWidgets.remote(g, remote.name(), remote.marker(), remote.distance(), remote.closing());
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
                            + surfaceLocked()
                            + " | selectedBody "
                            + (selection == null ? -1 : selection.bodyIndex())
                            + " | gas "
                            + snapshot.gas()
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
            if (DevelopmentHooks.driving.getAsBoolean())
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
