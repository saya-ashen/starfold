package dev.starfold.dev;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundSolar;
import dev.starfold.chart.PlanetOrbit;
import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.ArrivalState;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.flight.HudLayout;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.GroundSeams;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.probe.ProbeState;
import dev.starfold.render.GroundClient;
import dev.starfold.render.GroundRenderer;
import dev.starfold.render.PlanetRemoteRenderer;
import dev.starfold.ship.Airstair;
import dev.starfold.ship.HelmClient;
import dev.starfold.ship.HelmInputClient;
import dev.starfold.ship.HullLanding;
import dev.starfold.ship.HullLandingClient;
import dev.starfold.ship.SpacePackets;
import dev.starfold.ship.ThrusterRenderer;
import dev.starfold.ship.ThrusterSoundClient;
import dev.starfold.ship.Thrusters;
import dev.starfold.sky.Space;
import dev.starfold.sky.WeatherClient;
import dev.starfold.space.SpaceAttitudeClient;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceDustClient;
import dev.starfold.space.SpaceNavigation;
import dev.starfold.space.SpaceNavigationClient;
import dev.starfold.space.SpaceView;
import dev.starfold.waypoint.WaypointClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.file.*;
import java.util.*;

/** Development-only scripted client driver. Inert unless -Dstarfold.probe=<script> is set.
 * Lines: wait N | until alt<|alt>|ground|free|controlled [VALUE] TIMEOUT | aim target|track|free | nav TEXT | waitworld | cmd TEXT | key NAME on|off | look YAW PITCH | fly on|off | abilities TEXT | census RADIUS | shot NAME | log TEXT | quit */
public final class DevProbe {
    private static List<String> script = List.of();
    private static int line, wait;
    private static final Map<String, Boolean> keys = new HashMap<>();
    private static net.minecraft.core.BlockPos lever;
    private static int sampleEvery, sampleCountdown, sampleNumber;
    private static boolean tracing;
    private static int mouseAim = -1, mouseAimLeft;
    private static double mouseMiss = Double.NaN;
    private static boolean mouseArrival;
    private static int shipAim, shipAimLeft;
    private static double shipAimX = 1, shipAimY = 1;
    private static org.joml.Vector3d shipCam;
    private static double shipCamRadius, shipCamUp, shipCamAngle, shipCamRate;
    private static long shipCamStart;
    private static String stairShot;
    private static double walkX, walkZ;
    private static int walkLeft;
    private static boolean walkJump;
    private static int mouseTicks;
    private static long probeTick;
    private static double mouseX, mouseY;
    private static int cornerEvery, cornerCountdown;
    private static int cargoEvery, cargoCountdown;
    private static boolean strict;
    private static String receipt, checkLabel;
    private static int guardLeft, stable;
    private static String motionGuard;
    private static net.minecraft.world.phys.Vec3 walkStart;
    private static org.joml.Vector3d lastHull;
    private static int homeLeft;
    private static double homeStop, homePrevious, homeRate;
    private static int homeStable;
    private static boolean homeTurn;
    private static double turnBase;
    private static Double shipHeading;
    private static int headingLeft, headingKey, headingStable;
    private static double headingPrevious = Double.NaN;
    private static int steerPlanet, steerLeft, steerElapsed;
    private static final Set<Integer> steerKeys = new HashSet<>();
    private static Float yaw, pitch;
    private static String until;
    private static double untilValue;
    private static int untilLeft;
    private static boolean track, finishing;

    /** Scripted sessions have no focused, mouse-grabbed window; flight input must still be read. */
    public static boolean active() {
        return !script.isEmpty();
    }

    public static void initialize() {
        ArrivalClientProbe.initialize();
        String path = System.getProperty("starfold.probe");
        if (path == null) return;
        try {
            script = Files.readAllLines(Path.of(path));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Probe script", error);
        }
        ProbeState.drivenBy(DevProbe::active);
        SpaceClient.frameListener = DevProbe::mouseFrame;
        DevFilm.initialize();
        // "shipcam orbit <degrees per second>" carries the player round the target on an exact
        // circle, placed anew before every rendered frame
        // from the wall clock, the same clock the recorder samples: flying the circle by keys is
        // stepped by ticks and shows as a tremor.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) -> {
                    if (shipCam == null || shipCamRate == 0) return;
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.level == null || mc.player == null) return;
                    var hulls =
                            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                                            mc.level)
                                    .getAllSubLevels()
                                    .iterator();
                    if (!hulls.hasNext()) return;
                    double angle =
                            shipCamAngle
                                    + Math.toRadians(shipCamRate)
                                            * (System.nanoTime() - shipCamStart)
                                            / 1e9;
                    var at =
                            hulls.next()
                                    .logicalPose()
                                    .transformPosition(
                                            new org.joml.Vector3d(shipCam)
                                                    .add(
                                                            Math.cos(angle) * shipCamRadius,
                                                            shipCamUp,
                                                            Math.sin(angle) * shipCamRadius));
                    double y = at.y - mc.player.getEyeHeight();
                    mc.player.setPos(at.x, y, at.z);
                    mc.player.xo = mc.player.xOld = at.x;
                    mc.player.yo = mc.player.yOld = y;
                    mc.player.zo = mc.player.zOld = at.z;
                    mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                });
        // The ship camera aims every rendered frame from where the camera really is: aiming only on
        // ticks makes the picture shake.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) -> {
                    if (shipCam == null) return;
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.level == null) return;
                    var hulls =
                            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                                            mc.level)
                                    .getAllSubLevels()
                                    .iterator();
                    if (!hulls.hasNext()) return;
                    var at =
                            hulls.next()
                                    .logicalPose()
                                    .transformPosition(new org.joml.Vector3d(shipCam));
                    var eye = event.getCamera().getPosition();
                    double dx = at.x - eye.x, dy = at.y - eye.y, dz = at.z - eye.z;
                    event.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    event.setPitch((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post cpEvent) -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    try {
                        DevProbe.tick(mc);
                    } catch (RuntimeException error) {
                        if (!strict) throw error;
                        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                                .error("PROBE exception", error);
                        fail(mc, "probe exception " + error);
                    }
                });
    }

    // Step/await helpers return true when they consume the tick; later work must not run.
    private static void tick(Minecraft mc) {
        updatePresentation(mc);
        DevTripProbe.tick(mc);
        if (DevFeelProbe.tick(mc) || DevGuideProbe.tick(mc) || DevLandingProbe.tick(mc)) return;
        updateSampling(mc);
        if (awaitReceipt(mc)) return;
        if (awaitMotion(mc)) return;
        if (walkStep(mc)) return;
        updateTracking(mc);
        if (shipAimStep(mc)) return;
        if (mouseAimStep(mc)) return;
        if (steerLeft > 0 && mc.player != null) {
            if (steer(mc)) return;
        }
        if (homeStep(mc)) return;
        if (headingStep(mc)) return;
        if (awaitCondition(mc)) return;
        runScript(mc);
    }

    private static void updatePresentation(Minecraft mc) {
        probeTick++;
        mc.options.pauseOnLostFocus = false;
        if (stairShot != null && mc.level != null) {
            var bodies = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
            if (bodies != null)
                outer:
                for (var body : bodies.getAllSubLevels())
                    for (var holder : body.getPlot().getLoadedChunks())
                        for (var be : holder.getChunk().getBlockEntities().values())
                            if (be instanceof Airstair.Stair stair
                                    && stair.visual >= .35
                                    && stair.visual <= .65
                                    && stair.length > 0) {
                                String name = stairShot;
                                stairShot = null;
                                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                                        .info(
                                                "PROBE AIRSTAIR SHUTTER {} fraction {}",
                                                name,
                                                stair.visual);
                                Screenshot.grab(
                                        mc.gameDirectory,
                                        name + ".png",
                                        mc.getMainRenderTarget(),
                                        m -> {});
                                break outer;
                            }
        }
        // An occluded or locked desktop blocks vsync'd buffer swaps; the probe must keep rendering.
        if (mc.options.enableVsync().get()) {
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
        }
        if (mc.player != null) {
            var o = mc.options;
            for (var e : keys.entrySet()) {
                var key =
                        switch (e.getKey()) {
                            case "forward" -> o.keyUp;
                            case "back" -> o.keyDown;
                            case "left" -> o.keyLeft;
                            case "right" -> o.keyRight;
                            case "jump" -> o.keyJump;
                            case "sneak" -> o.keyShift;
                            case "sprint" -> o.keySprint;
                            case "use" -> o.keyUse;
                            case "attack" -> o.keyAttack;
                            default -> throw new IllegalArgumentException(e.getKey());
                        };
                key.setDown(e.getValue());
            }
            if (shipCam != null) {
                var hulls =
                        dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level)
                                .getAllSubLevels()
                                .iterator();
                if (hulls.hasNext()) {
                    var at =
                            hulls.next()
                                    .logicalPose()
                                    .transformPosition(new org.joml.Vector3d(shipCam));
                    var eye = mc.player.getEyePosition();
                    double dx = at.x - eye.x, dy = at.y - eye.y, dz = at.z - eye.z;
                    yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
                    double r = Math.hypot(dx, dz);
                    if (r > 1 && shipCamRate == 0) {
                        double pull = Math.clamp((r - shipCamRadius) * .15, -.4, .4);
                        mc.player.setDeltaMovement(
                                mc.player.getDeltaMovement().add(dx / r * pull, 0, dz / r * pull));
                    }
                }
            }
            if (yaw != null) {
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);
            }
        }
    }

    private static void updateSampling(Minecraft mc) {
        if (mouseTicks > 0 && mc.player != null) {
            mouseTicks--;
            mouse(mc);
        }
        if (Boolean.getBoolean("starfold.rejoinProbe") && mc.player != null && mc.level != null)
            rejoin(mc);
        if (tracing && mc.player != null && mc.level != null) trace(mc);
        if (cornerEvery > 0 && mc.player != null && mc.level != null && --cornerCountdown <= 0) {
            cornerCountdown = cornerEvery;
            corner(mc, "periodic");
        }
        if (cargoEvery > 0 && mc.player != null && mc.level != null && --cargoCountdown <= 0) {
            cargoCountdown = cargoEvery;
            cargo(mc, "periodic", false);
        }
        if (sampleEvery > 0 && mc.player != null && mc.level != null && --sampleCountdown <= 0) {
            sampleCountdown = sampleEvery;
            sample(mc, "trip-" + (sampleNumber++));
        }
    }

    private static boolean awaitReceipt(Minecraft mc) {
        if (receipt != null) {
            boolean ok = false;
            try {
                ok =
                        Files.readString(mc.gameDirectory.toPath().resolve("logs/latest.log"))
                                .lines()
                                .anyMatch(s -> s.contains("[CHAT]") && s.contains(receipt));
            } catch (java.io.IOException ignored) {
            }
            if (ok) {
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info("PROBE CHECK PASS {}", checkLabel);
                receipt = null;
            } else if (--guardLeft <= 0) {
                fail(mc, "CHECK " + checkLabel + " missing positive readback");
                return true;
            } else return true;
        }
        return false;
    }

    private static boolean awaitMotion(Minecraft mc) {
        if (motionGuard != null && mc.player != null) {
            boolean ok = false;
            if (motionGuard.equals("walk")) {
                double d =
                        Math.hypot(mc.player.getX() - walkStart.x, mc.player.getZ() - walkStart.z);
                ok = d >= 20;
                if (ok || guardLeft % 20 == 0)
                    org.slf4j.LoggerFactory.getLogger("starfold-probe")
                            .info("PROBE WALK distance {} pos {}", d, mc.player.position());
            } else {
                var body = dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(mc.player);
                double height = Double.NaN, speed = Double.NaN;
                if (body != null && !SpaceClient.present()) {
                    var at = body.logicalPose().position();
                    int x = (int) Math.floor(at.x()), z = (int) Math.floor(at.z());
                    if (mc.level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
                        int terrain =
                                mc.level.getHeight(
                                        net.minecraft.world.level.levelgen.Heightmap.Types
                                                .WORLD_SURFACE,
                                        x,
                                        z);
                        while (terrain > mc.level.getMinBuildHeight()) {
                            var groundPos = new net.minecraft.core.BlockPos(x, terrain - 1, z);
                            if (!mc.level
                                    .getBlockState(groundPos)
                                    .getCollisionShape(mc.level, groundPos)
                                    .isEmpty()) break;
                            terrain--;
                        }
                        height =
                                terrain <= mc.level.getMinBuildHeight()
                                        ? Double.NaN
                                        : body.boundingBox().minY() - terrain;
                        if (guardLeft % 20 == 0)
                            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                                    .info(
                                            "PROBE LANDRAW body {} bottom {} terrain {} block {}",
                                            at,
                                            body.boundingBox().minY(),
                                            terrain,
                                            mc.level.getBlockState(
                                                    new net.minecraft.core.BlockPos(
                                                            x, terrain - 1, z)));
                        speed = lastHull == null ? Double.NaN : lastHull.distance(at) * 20;
                        lastHull = new org.joml.Vector3d(at);
                    }
                }
                stable =
                        Double.isFinite(height) && height >= -1 && height < 5 && speed < 1
                                ? stable + 1
                                : 0;
                ok = stable >= 40;
                if (ok || guardLeft % 20 == 0)
                    org.slf4j.LoggerFactory.getLogger("starfold-probe")
                            .info("PROBE LAND height {} speed {} stable {}", height, speed, stable);
            }
            if (ok) {
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info("PROBE GUARD PASS {}", motionGuard);
                keys.put("forward", false);
                mc.options.keyUp.setDown(false);
                motionGuard = null;
            } else if (--guardLeft <= 0) {
                fail(mc, "motion " + motionGuard + " timeout");
                return true;
            } else return true;
        }
        return false;
    }

    private static boolean walkStep(Minecraft mc) {
        if (walkLeft > 0 && mc.player != null) {
            var bodies = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
            var b = bodies.getAllSubLevels().iterator().next();
            var bounds = b.getPlot().getBoundingBox();
            var target =
                    b.logicalPose()
                            .transformPosition(
                                    new org.joml.Vector3d(
                                            (bounds.minX() + bounds.maxX() + 1) / 2.0 + walkX,
                                            bounds.minY() + 1,
                                            (bounds.minZ() + bounds.maxZ() + 1) / 2.0 + walkZ));
            double dx = target.x - mc.player.getX(), dz = target.z - mc.player.getZ();
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            pitch = 0f;
            mc.player.setYRot(yaw);
            boolean done = Math.hypot(dx, dz) < .12 || --walkLeft == 0;
            mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), 87, 0, done ? 0 : 1, 0);
            mc.keyboardHandler.keyPress(
                    mc.getWindow().getWindow(), 32, 0, !done && walkJump ? 1 : 0, 0);
            if (walkLeft % 5 == 0) PilotPoseProbe.boarding(mc.player, "walking");
            if (done) {
                walkLeft = 0;
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE WALKLOCAL target {} {} remaining {} distance {}",
                                walkX,
                                walkZ,
                                walkLeft,
                                Math.hypot(dx, dz));
                PilotPoseProbe.boarding(mc.player, "walk-end");
            } else return true;
        }
        return false;
    }

    private static void updateTracking(Minecraft mc) {
        if (track) GroundFlightClient.aimAtTarget();
        if (mouseAimLeft > 0 && mouseArrival && SpaceClient.arriving()) {
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE MOUSEAIM arrival took control remaining {}", mouseAimLeft);
            mouseAimLeft = 0;
        }
    }

    private static boolean shipAimStep(Minecraft mc) {
        // Turn the piloted ship's nose onto a planet by mouse, measuring the error against the
        // hull's own axes (forward is -X, up +Y).
        if (shipAimLeft > 0 && mc.player != null) {
            if (!SpaceAttitudeClient.mouseFlight()) {
                fail(mc, "shipaim not in mouse flight");
                return true;
            }
            var at = SpaceClient.universeEye(mc.player.getEyePosition());
            var d =
                    SpaceClient.frame()
                            .levelVector(
                                    GroundClient.ephemeris()
                                            .at(shipAim, SpaceClient.time())
                                            .center()
                                            .sub(at));
            var local =
                    new org.joml.Quaterniond(HelmClient.body().logicalPose().orientation())
                            .conjugate()
                            .transform(new org.joml.Vector3d(d.x(), d.y(), d.z()));
            double right = shipAimX * Math.toDegrees(Math.atan2(-local.z, -local.x)),
                    up =
                            shipAimY
                                    * Math.toDegrees(
                                            Math.atan2(local.y, Math.hypot(local.x, local.z)));
            var pointer = SpaceAttitudeClient.pointer();
            if (--shipAimLeft == 0
                    || Math.hypot(right, up) < 1 && Math.hypot(pointer.x(), pointer.y()) < .3) {
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE SHIPAIM planet {} error {} {} remaining {}",
                                shipAim,
                                right,
                                up,
                                shipAimLeft);
                shipAimLeft = 0;
            } else {
                if (shipAimLeft % 10 == 0) {
                    double s = mc.options.sensitivity().get() * .6 + .2, f = s * s * s * 8 * .15;
                    mouseX = Math.clamp(right - pointer.x(), -15, 15) / f;
                    mouseY =
                            Math.clamp(-up + pointer.y(), -15, 15)
                                    / f
                                    * (mc.options.invertYMouse().get() ? -1 : 1);
                    mouse(mc);
                }
                if (shipAimLeft % 20 == 0)
                    org.slf4j.LoggerFactory.getLogger("starfold-probe")
                            .info(
                                    "PROBE SHIPAIM step error {} {} pointer {} {}",
                                    right,
                                    up,
                                    pointer.x(),
                                    pointer.y());
                return true;
            }
        }
        return false;
    }

    private static boolean mouseAimStep(Minecraft mc) {
        if (mouseAimLeft > 0 && mc.player != null) {
            if (!SpaceAttitudeClient.mouseFlight()) {
                fail(mc, "mouseaim not in mouse flight");
                return true;
            }
            var at = SpaceClient.universeEye(mc.player.getEyePosition());
            var d =
                    SpaceClient.frame()
                            .levelVector(
                                    GroundClient.ephemeris()
                                            .at(
                                                    mouseAim < 0 ? -mouseAim - 1 : mouseAim,
                                                    SpaceClient.time())
                                            .center()
                                            .sub(at)
                                            .scale(mouseAim < 0 ? -1 : 1));
            if (Double.isFinite(mouseMiss)) {
                double length = d.length(),
                        s =
                                Math.clamp(
                                        (PlanetShape.of(mouseAim).radius() + mouseMiss) / length,
                                        0,
                                        .999999);
                var n = d.scale(1 / length);
                var side = new BentStripGeometry.Point(-n.z(), 0, n.x());
                if (side.length() < 1e-9) side = new BentStripGeometry.Point(1, 0, 0);
                d = n.scale(Math.sqrt(1 - s * s)).add(side.scale(s / side.length()));
            }
            double
                    dy =
                            net.minecraft.util.Mth.wrapDegrees(
                                    Math.toDegrees(Math.atan2(-d.x(), d.z()))
                                            - mc.player.getYRot()),
                    dp = -Math.toDegrees(Math.atan2(d.y(), Math.hypot(d.x(), d.z())));
            var pointer = SpaceAttitudeClient.pointer();
            if (--mouseAimLeft == 0
                    || Math.hypot(dy, dp) < (HelmClient.scopeView() ? .3 : .5)
                            && Math.hypot(pointer.x(), pointer.y()) < .3) {
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE MOUSEAIM planet {} error {} {} remaining {}",
                                mouseAim,
                                dy,
                                dp,
                                mouseAimLeft);
                mouseAimLeft = 0;
            } else {
                if (mouseAimLeft % 10 == 0) {
                    double s = mc.options.sensitivity().get() * .6 + .2, f = s * s * s * 8 * .15;
                    mouseX = Math.clamp(dy - pointer.x(), -15, 15) / f;
                    mouseY =
                            Math.clamp(dp + pointer.y(), -15, 15)
                                    / f
                                    * (mc.options.invertYMouse().get() ? -1 : 1);
                    mouse(mc);
                }
                return true;
            }
        }
        return false;
    }

    private static boolean homeStep(Minecraft mc) {
        if (homeLeft > 0 && mc.player != null) {
            var read = WaypointClient.guidance();
            if (read == null) {
                fail(mc, "home guidance absent");
                return true;
            }
            double delta = net.minecraft.util.Mth.wrapDegrees(read.angle() - homePrevious);
            homePrevious = read.angle();
            homeRate = .5 * homeRate + .5 * delta;
            homeStable = Math.abs(read.angle()) < 3 ? homeStable + 1 : 0;
            boolean done = homeTurn ? homeStable >= 15 : read.distance() < homeStop;
            double predicted = read.angle() + homeRate * 8;
            int key = !done && Math.abs(predicted) > 3 ? (predicted < 0 ? 65 : 68) : 0;
            var next = new HashSet<Integer>();
            if (key != 0) next.add(key);
            boolean forward = !done && !homeTurn && Math.abs(read.angle()) < 5;
            if (forward) {
                next.add(87);
                if (read.distance() > 40) next.add(341);
            }
            pilotKeys(mc, next);
            if (homeLeft % 20 == 0 || done)
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE HOME turnOnly {} remaining {} done {} {}{}",
                                homeTurn,
                                homeLeft,
                                done,
                                read,
                                SpaceNavigationClient.probeReading());
            if (--homeLeft == 0 && !done) {
                fail(mc, "home timeout");
                return true;
            }
            if (done) homeLeft = 0;
            else return true;
        }
        return false;
    }

    private static boolean headingStep(Minecraft mc) {
        if (shipHeading != null && mc.player != null) {
            var body = HelmClient.body();
            double error = Double.NaN;
            if (body != null) {
                var f = body.logicalPose().orientation().transform(new org.joml.Vector3d(-1, 0, 0));
                error =
                        Math.atan2(
                                Math.sin(shipHeading - Math.atan2(f.z, -f.x)),
                                Math.cos(shipHeading - Math.atan2(f.z, -f.x)));
            }
            double rate = Double.isFinite(headingPrevious) ? error - headingPrevious : 0;
            headingPrevious = error;
            headingStable =
                    Math.abs(error) < .008 && Math.abs(rate) < .0004 ? headingStable + 1 : 0;
            boolean done = headingStable >= 12;
            double predicted = error + rate * 14;
            int key =
                    !done && Double.isFinite(predicted) && Math.abs(predicted) > .004
                            ? (predicted > 0 ? 65 : 68)
                            : 0;
            if (key != headingKey) {
                if (headingKey != 0)
                    mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), headingKey, 0, 0, 0);
                if (key != 0) mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), key, 0, 1, 0);
                headingKey = key;
            }
            if (--headingLeft <= 0 && !done) {
                if (headingKey != 0)
                    mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), headingKey, 0, 0, 0);
                headingKey = 0;
                shipHeading = null;
                fail(mc, "heading timeout error=" + error);
                return true;
            }
            if (!done) return true;
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE heading ended error {} remaining {}", error, headingLeft);
            shipHeading = null;
            headingPrevious = Double.NaN;
            headingStable = 0;
        }
        return false;
    }

    private static boolean awaitCondition(Minecraft mc) {
        if (until != null && mc.player != null) {
            double alt = GroundFlightClient.probeAltitude();
            SpaceNavigation.Reading approach = null;
            if ((until.equals("aligning")
                            || until.equals("unaligned")
                            || until.equals("closing<")
                            || until.equals("closing>"))
                    && SpaceClient.active()) {
                var model = GroundClient.ephemeris();
                var at = SpaceClient.universeEye(mc.player.position());
                double tick = SpaceClient.time();
                approach =
                        SpaceNavigation.read(
                                model,
                                SpaceClient.frame(),
                                at,
                                Space.nearest(model, at, tick),
                                tick);
            }
            boolean done = conditionMet(mc, alt, approach);
            if (!done && --untilLeft > 0) return true;
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE until {} {} -> {} | {}",
                            until,
                            untilValue,
                            done ? "met" : "TIMEOUT",
                            GroundFlightClient.probeReading());
            until = null;
            if (strict && !done) {
                fail(mc, "until timeout");
                return true;
            }
        }
        return false;
    }

    private static boolean conditionMet(
            Minecraft mc, double alt, SpaceNavigation.Reading approach) {
        return switch (until) {
            case "landingarrow" ->
                    HullLandingClient.site().aid().status() == HullLanding.AidStatus.FOUND;
            case "landsite" ->
                    (HullLandingClient.site().kind() == HullLanding.Kind.LAND
                                    || HullLandingClient.site().kind() == HullLanding.Kind.PAD)
                            && HullLandingClient.site().deployable();
            case "stair" ->
                    HelmClient.body() != null
                            && HelmClient.body().getPlot().getLoadedChunks().stream()
                                    .anyMatch(
                                            holder ->
                                                    holder
                                                            .getChunk()
                                                            .getBlockEntities()
                                                            .values()
                                                            .stream()
                                                            .anyMatch(
                                                                    be ->
                                                                            be
                                                                                            instanceof
                                                                                            Airstair
                                                                                                            .Stair
                                                                                                    stair
                                                                                    && stair.length
                                                                                            > 0
                                                                                    && stair.visual
                                                                                            >= 1));
            case "notice" -> SpaceClient.phase() == ArrivalState.Phase.NOTICE;
            case "notice1" -> SpaceClient.notice() == 1;
            case "blend" ->
                    SpaceClient.phase() == ArrivalState.Phase.TURN_IN && SpaceClient.blend() >= .4f;
            case "arrived" -> SpaceClient.phase() == ArrivalState.Phase.ARRIVAL;
            case "home>" ->
                    WaypointClient.guidance() != null
                            && WaypointClient.guidance().distance() > untilValue;
            case "horizontal>" -> HullLandingClient.horizontal() > untilValue;
            case "clearance>" -> HullLandingClient.clearance() > untilValue;
            case "clearance<" -> HullLandingClient.clearance() < untilValue;
            case "x<" -> mc.player.getX() < untilValue;
            case "travelled>" ->
                    walkStart != null
                            && Math.hypot(
                                            mc.player.getX() - walkStart.x,
                                            mc.player.getZ() - walkStart.z)
                                    > untilValue;
            case "aligning" -> approach != null && SpaceClient.arriving();
            case "unaligned" -> approach != null && !SpaceClient.arriving();
            case "closing<" -> approach != null && approach.closing() < untilValue;
            case "closing>" -> approach != null && approach.closing() > untilValue;
            case "height<" ->
                    SpaceClient.active()
                            && Space.height(
                                            GroundClient.ephemeris(),
                                            Space.nearest(
                                                    GroundClient.ephemeris(),
                                                    SpaceClient.universeEye(mc.player.position()),
                                                    SpaceClient.time()),
                                            SpaceClient.universeEye(mc.player.position()),
                                            SpaceClient.time())
                                    < untilValue;
            case "height>" ->
                    SpaceClient.active()
                            && Space.height(
                                            GroundClient.ephemeris(),
                                            Space.nearest(
                                                    GroundClient.ephemeris(),
                                                    SpaceClient.universeEye(mc.player.position()),
                                                    SpaceClient.time()),
                                            SpaceClient.universeEye(mc.player.position()),
                                            SpaceClient.time())
                                    > untilValue;
            case "relative>" ->
                    SpaceClient.active()
                            && SpaceNavigation.velocity(
                                                            GroundClient.ephemeris(),
                                                            SpaceClient.frame(),
                                                            SpaceNavigationClient.locked(),
                                                            SpaceClient.time())
                                                    .length()
                                            * 20
                                    > untilValue;
            case "relative<" ->
                    SpaceClient.active()
                            && SpaceNavigation.velocity(
                                                            GroundClient.ephemeris(),
                                                            SpaceClient.frame(),
                                                            SpaceNavigationClient.locked(),
                                                            SpaceClient.time())
                                                    .length()
                                            * 20
                                    < untilValue;
            case "w>" ->
                    SpaceClient.active() && SpaceClient.frame().drift().length() * 20 > untilValue;
            case "w<" ->
                    SpaceClient.active() && SpaceClient.frame().drift().length() * 20 < untilValue;
            case "ref" -> SpaceClient.active() && SpaceClient.frame().planet() == (int) untilValue;
            case "region" -> SpaceClient.active();
            case "chart" ->
                    !SpaceClient.active() && !Space.holds(mc.player.getX(), mc.player.getZ());
            case "sky>" -> GroundClient.time() >= untilValue;
            case "alt<" -> alt < untilValue;
            case "y>" -> mc.player.getY() > untilValue;
            case "y<" -> mc.player.getY() < untilValue;
            case "chart!=" -> GroundClient.frame().planet() != (int) untilValue;
            case "alt>" -> alt > untilValue;
            case "ground" -> !GroundFlightClient.controlled() && mc.player.onGround();
            case "free" -> !GroundFlightClient.controlled();
            case "controlled" -> GroundFlightClient.controlled();
            case "planet!=" -> GroundFlightClient.planet() != (int) untilValue;
            default -> true;
        };
    }

    private static void runScript(Minecraft mc) {
        while (line < script.size()) {
            if (wait > 0) {
                wait--;
                return;
            }
            String[] p = script.get(line).trim().split("\\s+", 2);
            String arg = p.length > 1 ? p[1] : "";
            if (arg.contains("{")) arg = expand(arg);
            if (!command(mc, p[0], arg)) return;
            if (strict && line == script.size() - 2 && !p[0].equals("shot")) {
                fail(mc, "guard " + p[0]);
                return;
            }
            line++;
        }
    }

    /** Returns whether the script loop may advance; false preserves the command's tick boundary. */
    private static boolean command(Minecraft mc, String command, String arg) {
        return switch (command) {
            case "", "#" -> commandComment();
            case "wait" -> commandWait(arg);
            case "port" -> commandPort(arg);
            case "waitworld" -> commandWaitworld(mc);
            case "boardpilot" -> commandBoardpilot(mc, arg);
            case "shipcam" -> commandShipcam(mc, arg);
            case "boardblock" -> commandBoardblock(mc, arg);
            case "record" -> commandRecord(mc, arg);
            case "recordpos" -> commandRecordpos(mc, arg);
            case "focus" -> commandFocus(mc);
            case "probeui" -> commandProbeui(mc);
            case "recordhit" -> commandRecordhit(mc, arg);
            case "console" -> commandConsole(arg);
            case "strict" -> commandStrict();
            case "check" -> commandCheck(mc, arg);
            case "entitybench" -> commandEntitybench(mc, arg);
            case "landguard" -> commandLandguard(arg);
            case "walk20" -> commandWalk20(mc, arg);
            case "solarplan" -> commandSolarplan(mc, arg);
            case "solarjump" -> commandSolarjump(mc, arg);
            case "guide" -> commandGuide(mc, arg);
            case "input" -> commandInput(mc, arg);
            case "solar" -> commandSolar(mc, arg);
            case "cmd" -> commandCmd(mc, arg);
            case "key" -> commandKey(arg);
            case "look" -> commandLook(arg);
            case "fly" -> commandFly(mc, arg);
            case "abilities" -> commandAbilities(mc, arg);
            case "helmstate" -> commandHelmstate(mc, arg);
            case "useifidle" -> commandUseifidle(mc);
            case "burn" -> commandBurn(mc, arg);
            case "requirehelm" -> commandRequirehelm(mc);
            case "requirenohelm" -> commandRequirenohelm(mc);
            case "requireref" -> commandRequireref(arg);
            case "requireplanet" -> commandRequireplanet(arg);
            case "openhitdoor" -> commandOpenhitdoor(mc);
            case "selecthome" -> commandSelecthome(arg);
            case "selectplanet" -> commandSelectplanet(arg);
            case "departdetour" -> commandDepartdetour();
            case "stairwalk" -> commandStairwalk(mc, arg);
            case "followlanding" -> commandFollowlanding(arg);
            case "groundwalk" -> commandGroundwalk(mc, arg);
            case "travelstart" -> commandTravelstart(mc);
            case "home" -> commandHome(arg);
            case "chartheading" -> commandChartheading(arg);
            case "loopstate" -> commandLoopstate(mc, arg);
            case "surfacehere" -> commandSurfacehere(mc);
            case "requirechart" -> commandRequirechart();
            case "aboardelsequit" -> commandAboardelsequit(mc);
            case "cargowatch" -> commandCargowatch(arg);
            case "cargo" -> commandCargo(mc, arg);
            case "cornerwatch" -> commandCornerwatch(arg);
            case "corner" -> commandCorner(mc, arg);
            case "trace" -> commandTrace(arg);
            case "sample" -> commandSample(arg);
            case "skylook" -> commandSkylook(mc, arg);
            case "steer" -> commandSteer(arg);
            case "turnbase" -> commandTurnbase();
            case "turnto" -> commandTurnto(arg);
            case "headingread" -> commandHeadingread(arg);
            case "turn" -> commandTurn(mc, arg);
            case "requirepad" -> commandRequirepad(mc);
            case "heading" -> commandHeading(mc, arg);
            case "airstairshot" -> commandAirstairshot(arg);
            case "photooff" -> commandPhotooff(mc);
            case "photolocal" -> commandPhotolocal(mc, arg);
            case "hud" -> commandHud(mc, arg);
            case "film" -> commandFilm(mc, arg);
            case "view" -> commandView(mc, arg);
            case "hudsize" -> commandHudsize(mc, arg);
            case "scopetier" -> commandScopetier(mc, arg);
            case "removescopes" -> commandRemovescopes(mc);
            case "feeltest" -> commandFeeltest(mc, arg);
            case "pronefps" -> commandPronefps(mc, arg);
            case "language" -> commandLanguage(mc, arg);
            case "feelclick" -> commandFeelclick(mc, arg);
            case "helmview" -> commandHelmview(arg);
            case "f5" -> commandF5(mc);
            case "camera" -> commandCamera(mc, arg);
            case "debug" -> commandDebug(mc);
            case "shot" -> commandShot(mc, arg);
            case "jetvisible" -> commandJetvisible(arg);
            case "jetload" -> commandJetload(mc, arg);
            case "fx" -> commandFx(mc, arg);
            case "particles" -> commandParticles(mc, arg);
            case "log" -> commandLog(mc, arg);
            case "scandown" -> commandScandown(mc, arg);
            case "walklocal" -> commandWalklocal(arg);
            case "boarding" -> commandBoarding(mc, arg);
            case "looklocal" -> commandLooklocal(mc, arg);
            case "hullblocks" -> commandHullblocks(mc, arg);
            case "blockat" -> commandBlockat(mc, arg);
            case "aim" -> commandAim(arg);
            case "sunlook" -> commandSunlook(mc, arg);
            case "system" -> commandSystem(mc, arg);
            case "breaknear" -> commandBreaknear(mc, arg);
            case "nav" -> commandNav(mc, arg);
            case "until" -> commandUntil(arg);
            case "light" -> commandLight(mc, arg);
            case "break" -> commandBreak(mc, arg);
            case "use" -> commandUse(mc, arg);
            case "usehit" -> commandUsehit(mc, arg);
            case "leadsite" -> commandLeadsite(mc, arg);
            case "scroll" -> commandScroll(mc, arg);
            case "weather" -> commandWeather(mc, arg);
            case "mouse" -> commandMouse(mc, arg);
            case "mouseaim" -> commandMouseaim(arg);
            case "shipaim" -> commandShipaim(arg);
            case "navplanets" -> commandNavplanets(arg);
            case "lock" -> commandLock(mc, arg);
            case "press" -> commandPress(mc, arg);
            case "milestone" -> commandMilestone(mc, arg);
            case "find" -> commandFind(mc, arg);
            case "throttle" -> commandThrottle(mc, arg);
            case "leverdo" -> commandLeverdo(mc, arg);
            case "hit" -> commandHit(mc, arg);
            case "usenear" -> commandUsenear(mc, arg);
            case "asleep" -> commandAsleep(mc, arg);
            case "entities" -> commandEntities(mc);
            case "effects" -> commandEffects(mc, arg);
            case "surface" -> commandSurface(mc, arg);
            case "census" -> commandCensus(mc, arg);
            case "exit" -> commandExit(mc);
            case "quit" -> commandQuit(mc);
            default -> commandUnknown();
        };
    }

    private static boolean commandComment() {
        return true;
    }

    private static boolean commandWait(String arg) {
        wait = Integer.parseInt(arg);
        line++;
        return false;
    }

    private static boolean commandPort(String arg) {
        DevPortProbe.command(arg);
        return true;
    }

    private static boolean commandWaitworld(Minecraft mc) {
        if (mc.player == null
                || mc.level == null
                || mc.screen != null
                        && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
            if (mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen)
                mc.player.respawn();
            // Opening a custom-preset world asks for confirmation; a scripted session
            // accepts it.
            else if (mc.screen != null && mc.level == null)
                for (var child : mc.screen.children())
                    if (child instanceof net.minecraft.client.gui.components.Button button) {
                        String label = button.getMessage().getString();
                        if (label.contains("know what")
                                || label.equals("Proceed")
                                || label.equals("Yes")
                                || label.equals("Continue")) {
                            button.onPress();
                            break;
                        }
                    }
            return false;
        }
        return true;
    }

    private static boolean commandBoardpilot(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var bodies = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
        boolean found = false;
        for (var b : bodies.getAllSubLevels())
            if (b.getUniqueId().toString().equals(a[0])) {
                var v =
                        b.logicalPose()
                                .transformPosition(
                                        new org.joml.Vector3d(
                                                Double.parseDouble(a[1]),
                                                Double.parseDouble(a[2]),
                                                Double.parseDouble(a[3])));
                var at = GroundClient.storage(v.x, v.y, v.z);
                String command =
                        String.format(
                                Locale.ROOT, "tp @s %.9f %.9f %.9f", at.x(), at.y() + .1, at.z());
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info("PROBE BOARD PILOT ONLY {} {}", a[0], command);
                mc.player.connection.sendCommand(command);
                found = true;
                break;
            }
        if (!found) {
            fail(mc, "boarding hull absent " + a[0]);
            return false;
        }
        return true;
    }

    // Film the first hull from outside with the player as the camera: "shipcam off",
    // "shipcam center <back> <up> [degrees]" looks at the
    // hull's middle, "shipcam <block> <back> <up> [degrees] [x|-x|y|-y|z|-z]" at the
    // block of that name furthest along an axis. The view keeps following.
    private static boolean commandShipcam(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        if (a[0].equals("off")) {
            shipCam = null;
            shipCamRate = 0;
            yaw = null;
            pitch = null;
        } else if (a[0].equals("orbit")) {
            shipCamRate = Double.parseDouble(a[1]);
            shipCamStart = System.nanoTime();
        } else {
            shipCamRate = 0;
            var b =
                    dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level)
                            .getAllSubLevels()
                            .iterator()
                            .next();
            var bounds = b.getPlot().getBoundingBox();
            var middle =
                    new org.joml.Vector3d(
                            (bounds.minX() + bounds.maxX() + 1) / 2.0,
                            (bounds.minY() + bounds.maxY() + 1) / 2.0,
                            (bounds.minZ() + bounds.maxZ() + 1) / 2.0);
            var target = new org.joml.Vector3d(middle);
            var out = new org.joml.Vector3d(1, 0, 0);
            if (!a[0].equals("center")) {
                String axis = a.length > 4 ? a[4] : "x";
                double best = Double.NEGATIVE_INFINITY;
                boolean found = false;
                for (var at :
                        net.minecraft.core.BlockPos.betweenClosed(
                                bounds.minX(),
                                bounds.minY(),
                                bounds.minZ(),
                                bounds.maxX(),
                                bounds.maxY(),
                                bounds.maxZ())) {
                    if (!mc.level
                            .getChunkAt(at)
                            .getBlockState(at)
                            .getBlock()
                            .toString()
                            .contains(a[0])) continue;
                    double score =
                            switch (axis) {
                                case "-x" -> -at.getX();
                                case "y" -> at.getY();
                                case "-y" -> -at.getY();
                                case "z" -> at.getZ();
                                case "-z" -> -at.getZ();
                                default -> at.getX();
                            };
                    if (score > best) {
                        best = score;
                        target.set(at.getX() + .5, at.getY() + .5, at.getZ() + .5);
                        found = true;
                    }
                }
                if (!found) {
                    fail(mc, "shipcam: no block " + a[0]);
                    return false;
                }
                out.set(target.x - middle.x, 0, target.z - middle.z);
                if (out.lengthSquared() < 1e-6) out.set(1, 0, 0);
                out.normalize();
            }
            out.rotateY(Math.toRadians(a.length > 3 ? Double.parseDouble(a[3]) : 0));
            var eye =
                    new org.joml.Vector3d(target)
                            .add(new org.joml.Vector3d(out).mul(Double.parseDouble(a[1])))
                            .add(0, Double.parseDouble(a[2]), 0);
            var v = b.logicalPose().transformPosition(eye);
            var world = GroundClient.storage(v.x, v.y, v.z);
            mc.player.connection.sendCommand(
                    String.format(
                            Locale.ROOT,
                            "tp @s %.6f %.6f %.6f",
                            world.x(),
                            world.y() - 1.62,
                            world.z()));
            shipCam = target;
            shipCamRadius = Double.parseDouble(a[1]);
            shipCamUp = Double.parseDouble(a[2]);
            shipCamAngle = Math.atan2(out.z, out.x);
        }
        return true;
    }

    // Stand beside the first block of the first hull whose name contains the argument:
    // "boardblock helm [n]" tries the n-th free side.
    private static boolean commandBoardblock(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        int want = a.length > 1 ? Integer.parseInt(a[1]) : 0;
        var bodies = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
        boolean found = false;
        search:
        for (var b : bodies.getAllSubLevels()) {
            var bounds = b.getPlot().getBoundingBox();
            for (var at :
                    net.minecraft.core.BlockPos.betweenClosed(
                            bounds.minX(),
                            bounds.minY(),
                            bounds.minZ(),
                            bounds.maxX(),
                            bounds.maxY(),
                            bounds.maxZ())) {
                if (!mc.level.getChunkAt(at).getBlockState(at).getBlock().toString().contains(a[0]))
                    continue;
                for (var side : net.minecraft.core.Direction.Plane.HORIZONTAL)
                    for (int down = 0; down <= 1; down++) {
                        var feet = at.relative(side).below(down);
                        if (!mc.level.getBlockState(feet).isAir()
                                || !mc.level.getBlockState(feet.above()).isAir()
                                || mc.level.getBlockState(feet.below()).isAir()
                                || want-- > 0) continue;
                        var v =
                                b.logicalPose()
                                        .transformPosition(
                                                new org.joml.Vector3d(
                                                        feet.getX() + .5,
                                                        feet.getY(),
                                                        feet.getZ() + .5));
                        var world = GroundClient.storage(v.x, v.y, v.z);
                        String command =
                                String.format(
                                        Locale.ROOT,
                                        "tp @s %.9f %.9f %.9f",
                                        world.x(),
                                        world.y() + .1,
                                        world.z());
                        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                                .info(
                                        "PROBE BOARD BLOCK {} at {} side {} | {}",
                                        a[0],
                                        at.toShortString(),
                                        side,
                                        command);
                        mc.player.connection.sendCommand(command);
                        found = true;
                        break search;
                    }
            }
        }
        if (!found) {
            fail(mc, "no free place beside " + a[0]);
            return false;
        }
        return true;
    }

    private static boolean commandRecord(Minecraft mc, String arg) {
        return commandRecord(mc, arg, "record");
    }

    private static boolean commandRecordpos(Minecraft mc, String arg) {
        return commandRecord(mc, arg, "recordpos");
    }

    private static boolean commandRecord(Minecraft mc, String arg, String command) {
        var at = GroundClient.storage(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        String value =
                command.equals("record")
                        ? ((int) Math.floor(at.x()))
                                + " "
                                + ((int) Math.floor(at.y()))
                                + " "
                                + ((int) Math.floor(at.z()))
                        : at.x() + " " + at.y() + " " + at.z();
        try {
            Files.writeString(Path.of("../build/space/loop2-" + arg + ".txt"), value);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE ABS {} {}", arg, value);
        return true;
    }

    private static boolean commandFocus(Minecraft mc) {
        org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().getWindow());
        return true;
    }

    private static boolean commandProbeui(Minecraft mc) {
        mc.options.guiScale().set(3);
        mc.resizeDisplay();
        mc.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.FULL);
        mc.options.chatOpacity().set(0d);
        mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        return true;
    }

    private static boolean commandRecordhit(Minecraft mc, String arg) {
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) {
            var at = hit.getBlockPos();
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE RECORDHIT {} {} state {}", arg, at, mc.level.getBlockState(at));
        }
        return true;
    }

    private static boolean commandConsole(String arg) {
        var path = Path.of(arg);
        if (!Files.exists(path)) return false;
        try {
            var commands = Files.readAllLines(path);
            Files.delete(path);
            var updated = new ArrayList<>(script);
            updated.addAll(line, commands);
            script = updated;
            line--;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return true;
    }

    private static boolean commandStrict() {
        strict = true;
        return true;
    }

    private static boolean commandEntitybench(Minecraft mc, String arg) {
        checkLabel = "entitybench " + arg;
        receipt = "PROBE ENTITY_BENCH " + arg + " ticks 600";
        guardLeft = 12000;
        mc.player.connection.sendCommand("entitybench measure " + arg);
        line++;
        return false;
    }

    private static boolean commandCheck(Minecraft mc, String arg) {
        var a = arg.split("\\s+", 2);
        checkLabel = a[0];
        receipt = "CP_CHECK_" + UUID.randomUUID();
        guardLeft = 200;
        mc.player.connection.sendCommand("execute " + a[1] + " run tellraw @s \"" + receipt + "\"");
        line++;
        return false;
    }

    private static boolean commandLandguard(String arg) {
        motionGuard = "land";
        guardLeft = Integer.parseInt(arg);
        stable = 0;
        lastHull = null;
        line++;
        return false;
    }

    private static boolean commandWalk20(Minecraft mc, String arg) {
        motionGuard = "walk";
        guardLeft = Integer.parseInt(arg);
        walkStart = mc.player.position();
        keys.put("forward", true);
        line++;
        return false;
    }

    private static boolean commandSolarplan(Minecraft mc, String arg) {
        return commandSolarplan(mc, arg, "solarplan");
    }

    private static boolean commandSolarjump(Minecraft mc, String arg) {
        return commandSolarplan(mc, arg, "solarjump");
    }

    private static boolean commandSolarplan(Minecraft mc, String arg, String command) {
        if (!GroundClient.ready()) {
            fail(mc, "solar observer not ready");
            return false;
        }
        var model = GroundClient.ephemeris();
        var shape = GroundClient.shape();
        var eye = GroundClient.eye();
        var direction =
                shape.direction(
                        CubeSurfaceTopology.Face.values()[GroundClient.face()], eye.x(), eye.z());
        double now = GroundClient.time(), chosen = now, best = arg.equals("night") ? 2 : -2;
        for (double t = now + 200; t <= now + 48000; t += 20) {
            var f = model.at(GroundClient.planet(), t);
            var up = f.vector(direction);
            var at = f.center().add(up.scale(shape.radius() + eye.y()));
            var sun = model.star(at).position().sub(at);
            double e = up.dot(sun.scale(1 / sun.length()));
            if (arg.equals("night") ? e < best : e > best) {
                best = e;
                chosen = t;
            }
        }
        if (command.equals("solarjump")) {
            mc.player.connection.sendCommand("airsky " + Math.max(0, (long) (chosen - now)));
            wait = 40;
        } else {
            until = "sky>";
            untilValue = chosen;
            untilLeft = 12000;
        }
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE SOLARPLAN {} now {} target {} elevation {}", arg, now, chosen, best);
        line++;
        return false;
    }

    private static boolean commandGuide(Minecraft mc, String arg) {
        yaw = null;
        pitch = null;
        DevGuideProbe.command(mc, arg);
        line++;
        return false;
    }

    private static boolean commandInput(Minecraft mc, String arg) {

        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE INPUT {} attack {} focused {} screen {} target {}",
                        arg,
                        mc.options.keyAttack.isDown(),
                        mc.isWindowActive(),
                        mc.screen,
                        mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                                ? mc.level.getBlockState(hit.getBlockPos())
                                : mc.hitResult);
        return true;
    }

    private static boolean commandSolar(Minecraft mc, String arg) {
        if (GroundClient.ready() && !SpaceClient.present())
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE SOLAR {} game {} sky {} pos {} elevation {} daylight {}",
                            arg,
                            mc.level.getGameTime(),
                            GroundClient.time(),
                            GroundClient.storage(
                                    mc.player.getX(), mc.player.getY(), mc.player.getZ()),
                            GroundClient.sunElevation(),
                            GroundSolar.daylight(GroundClient.sunElevation()));
        else
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE SOLAR {} unavailable outside loaded ground", arg);
        return true;
    }

    private static boolean commandCmd(Minecraft mc, String arg) {
        mc.player.connection.sendCommand(arg);
        if (strict) {
            line++;
            wait = 2;
            return false;
        }
        return true;
    }

    private static boolean commandKey(String arg) {
        var a = arg.split("\\s+");
        keys.put(a[0], a[1].equals("on"));
        return true;
    }

    private static boolean commandLook(String arg) {
        var a = arg.split("\\s+");
        if (a[0].equals("free")) {
            yaw = null;
            pitch = null;
        } else {
            yaw = Float.parseFloat(a[0]);
            pitch = Float.parseFloat(a[1]);
        }
        return true;
    }

    // As a double tap of Space: it only takes off for someone allowed to fly.
    private static boolean commandFly(Minecraft mc, String arg) {
        mc.player.getAbilities().flying = arg.equals("on") && mc.player.getAbilities().mayfly;
        mc.player.onUpdateAbilities();
        return true;
    }

    private static boolean commandAbilities(Minecraft mc, String arg) {

        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE abilities {} | mayfly {} flying {} creative {} ground {} health {} | chest {}",
                        arg,
                        mc.player.getAbilities().mayfly,
                        mc.player.getAbilities().flying,
                        mc.player.isCreative(),
                        mc.player.onGround(),
                        String.format("%.1f", mc.player.getHealth()),
                        mc.player
                                .getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)
                                .getItem());
        return true;
    }

    private static boolean commandHelmstate(Minecraft mc, String arg) {
        DevHelmProbe.state(mc.player, arg);
        mc.player.connection.sendCommand("helmstate " + arg);
        return true;
    }

    private static boolean commandUseifidle(Minecraft mc) {
        if (HelmClient.body() == null
                && mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)
            mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        return true;
    }

    private static boolean commandBurn(Minecraft mc, String arg) {
        if (SpaceClient.active() && lever != null) {
            var at = SpaceClient.universeEye(mc.player.position());
            var d =
                    SpaceClient.frame()
                            .levelVector(
                                    GroundClient.ephemeris()
                                            .at(Integer.parseInt(arg), SpaceClient.time())
                                            .center()
                                            .sub(at));
            double ratio = Math.abs(d.y()) / Math.hypot(d.x(), d.z());
            int horizontal =
                    Math.clamp((int) Math.round((d.y() > 0 ? 3 : 2) / Math.max(.01, ratio)), 1, 3);
            int signal =
                    d.y() > 0 ? (int) Math.clamp(Math.round(5 * horizontal * ratio), 0, 15) : 0;
            try {
                var payload =
                        (net.minecraft.network.protocol.common.custom.CustomPacketPayload)
                                Class.forName(
                                                "dev.simulated_team.simulated.network.packets.ThrottleLeverSignalPacket")
                                        .getConstructor(
                                                net.minecraft.core.BlockPos.class, int.class)
                                        .newInstance(lever, signal);
                mc.player.connection.send(
                        new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                                payload));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            if (horizontal == 1 || horizontal == 3)
                mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), 87, 0, 1, 0);
            if (horizontal >= 2)
                mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), 341, 0, 1, 0);
            if (d.y() < 0) mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), 340, 0, 1, 0);
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE burn planet {} direction {} ratio {} horizontal {} dial {} descend {}",
                            arg,
                            d,
                            ratio,
                            horizontal,
                            signal,
                            d.y() < 0);
        }
        return true;
    }

    private static boolean commandRequirehelm(Minecraft mc) {
        boolean active = HelmClient.body() != null;
        org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE requirehelm {}", active);
        if (!active) {
            fail(mc, "controller pilot missing");
            return false;
        }
        return true;
    }

    private static boolean commandRequirenohelm(Minecraft mc) {
        if (HelmClient.body() != null) {
            fail(mc, "unexpected controller pilot");
            return false;
        }
        return true;
    }

    private static boolean commandRequireref(String arg) {
        if (!SpaceClient.active() || SpaceClient.frame().planet() != Integer.parseInt(arg)) {
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE WRONG REF expected {}", arg);
            line = script.size() - 2;
        }
        return true;
    }

    private static boolean commandRequireplanet(String arg) {
        if (SpaceClient.present() || GroundClient.frame().planet() != Integer.parseInt(arg)) {
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE WRONG PLANET expected {}", arg);
            line = script.size() - 2;
        }
        return true;
    }

    private static boolean commandOpenhitdoor(Minecraft mc) {
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) {
            var state = mc.level.getBlockState(hit.getBlockPos());
            if (state.hasProperty(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties
                                    .OPEN)
                    && !state.getValue(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties
                                    .OPEN))
                mc.gameMode.useItemOn(
                        mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        }
        return true;
    }

    private static boolean commandSelecthome(String arg) {
        return commandSelecthome(arg, "selecthome");
    }

    private static boolean commandSelectplanet(String arg) {
        return commandSelecthome(arg, "selectplanet");
    }

    private static boolean commandSelecthome(String arg, String command) {
        int target = command.equals("selecthome") ? 0 : Integer.parseInt(arg);
        if (SpaceNavigationClient.locked() != target) {
            var updated = new ArrayList<>(script);
            updated.addAll(
                    line + 1,
                    List.of("press 71 1", "press 71 0", "wait 5", "selectplanet " + target));
            script = updated;
        }
        return true;
    }

    private static boolean commandDepartdetour() {
        int planet = SpaceClient.frame().planet();
        var updated = new ArrayList<>(script);
        updated.addAll(
                line + 1,
                List.of(
                        "mouseaim " + (-planet - 1) + " 400 NaN arrival",
                        "press 87 1",
                        "until height> 6500 2000",
                        "milestone outward-clear",
                        "mouseaim " + planet + " 300 6000 arrival",
                        "wait 300",
                        "milestone around"));
        script = updated;
        return true;
    }

    private static boolean commandStairwalk(Minecraft mc, String arg) {
        var updated = new ArrayList<>(script);
        updated.addAll(line + 1, DevLandingProbe.stairs(mc, arg.equals("back")));
        script = updated;
        return true;
    }

    private static boolean commandFollowlanding(String arg) {
        var a = arg.split("\\s+");
        DevLandingProbe.follow(
                Integer.parseInt(a[0]), a.length > 1 && a[1].equals("informational"));
        line++;
        return false;
    }

    private static boolean commandGroundwalk(Minecraft mc, String arg) {
        yaw = null;
        DevLandingProbe.walk(mc, arg.equals("back"));
        line++;
        return false;
    }

    private static boolean commandTravelstart(Minecraft mc) {
        walkStart = mc.player.position();
        return true;
    }

    private static boolean commandHome(String arg) {
        var a = arg.split("\\s+");
        homeTurn = a[0].equals("turn");
        homeStop = homeTurn ? 0 : Double.parseDouble(a[0]);
        homeLeft = Integer.parseInt(a[1]);
        homePrevious = WaypointClient.guidance() == null ? 0 : WaypointClient.guidance().angle();
        homeRate = 0;
        homeStable = 0;
        line++;
        return false;
    }

    private static boolean commandChartheading(String arg) {
        var a = arg.split("\\s+");
        shipHeading = Math.toRadians(Double.parseDouble(a[0]));
        headingLeft = Integer.parseInt(a[1]);
        line++;
        return false;
    }

    private static boolean commandLoopstate(Minecraft mc, String arg) {
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE LOOPSTATE {} game {} day {} sky {} frame {}",
                        arg,
                        mc.level.getGameTime(),
                        mc.level.getDayTime(),
                        GroundClient.time(),
                        GroundClient.frame());
        rejoin(mc);
        sample(mc, arg);
        mc.player.connection.sendCommand("time query daytime");
        mc.player.connection.sendCommand("time query day");
        return true;
    }

    private static boolean commandSurfacehere(Minecraft mc) {
        int x = mc.player.getBlockX() + 3,
                z = mc.player.getBlockZ() + 3,
                y =
                        mc.level.getHeight(
                                        net.minecraft.world.level.levelgen.Heightmap.Types
                                                .WORLD_SURFACE,
                                        x,
                                        z)
                                - 1;
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE surfacehere {} {} {} | {}",
                        x,
                        y,
                        z,
                        mc.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)));
        return true;
    }

    private static boolean commandRequirechart() {
        if (SpaceClient.present()) {
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE NOT IN CHART: skip arrival/landing");
            line = script.size() - 2;
        }
        return true;
    }

    private static boolean commandAboardelsequit(Minecraft mc) {
        if (dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(mc.player) == null) {
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE NOT ABOARD: skip descent/landing");
            line = script.size() - 2;
        }
        return true;
    }

    private static boolean commandCargowatch(String arg) {
        cargoEvery = Integer.parseInt(arg);
        cargoCountdown = 1;
        return true;
    }

    private static boolean commandCargo(Minecraft mc, String arg) {
        cargo(mc, arg, true);
        return true;
    }

    private static boolean commandCornerwatch(String arg) {
        cornerEvery = Integer.parseInt(arg);
        cornerCountdown = 1;
        return true;
    }

    private static boolean commandCorner(Minecraft mc, String arg) {
        corner(mc, arg);
        return true;
    }

    private static boolean commandTrace(String arg) {
        tracing = arg.equals("on");
        return true;
    }

    private static boolean commandSample(String arg) {
        sampleEvery = Integer.parseInt(arg);
        sampleCountdown = 1;
        return true;
    }

    private static boolean commandSkylook(Minecraft mc, String arg) {
        if (SpaceClient.active()) {
            var eye = SpaceClient.universeEye(mc.player.getEyePosition());
            var model = GroundClient.ephemeris();
            var target =
                    arg.equals("sun")
                            ? model.star(eye).position()
                            : model.at(Integer.parseInt(arg), SpaceClient.time()).center();
            var d = SpaceClient.frame().levelVector(target.sub(eye));
            yaw = (float) Math.toDegrees(Math.atan2(-d.x(), d.z()));
            pitch = (float) -Math.toDegrees(Math.atan2(d.y(), Math.hypot(d.x(), d.z())));
        } else if (GroundFlightClient.controlled() && !arg.equals("sun")) {
            yaw = null;
            pitch = null;
            GroundFlightClient.probeAim(
                    GroundClient.ephemeris()
                            .at(Integer.parseInt(arg), GroundClient.time())
                            .center());
        } else if (GroundClient.ready() && !arg.equals("sun")) {
            int target = Integer.parseInt(arg);
            var model = GroundClient.ephemeris();
            var home = GroundClient.planetFrame(GroundClient.planet());
            var frame = PlanetOrbit.frame(GroundClient.shape(), GroundClient.state().pose());
            var toward =
                    model.at(target, GroundClient.time()).center().sub(GroundClient.universeEye());
            var d =
                    ChartLabSpace.rotate(
                            frame.local(home.inverseVector(toward)), GroundClient.frame().turns());
            yaw = (float) Math.toDegrees(Math.atan2(-d.x(), d.z()));
            pitch = (float) -Math.toDegrees(Math.atan2(d.y(), Math.hypot(d.x(), d.z())));
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE SKYLOOK planet {} from {} tick {} distance {} angularRadius {} yaw {} pitch {} shade {}",
                            target,
                            GroundClient.planet(),
                            GroundClient.time(),
                            toward.length(),
                            Math.toDegrees(
                                    Math.asin(
                                            Math.min(
                                                    1,
                                                    PlanetShape.of(target).radius()
                                                            / toward.length()))),
                            yaw,
                            pitch,
                            dev.starfold.sky.GroundDaylight.parentShade(
                                    model,
                                    target,
                                    model.at(target, GroundClient.time()).center(),
                                    GroundClient.time()));
        }
        return true;
    }

    private static boolean commandSteer(String arg) {
        var a = arg.split("\\s+");
        steerPlanet = Integer.parseInt(a[0]);
        steerLeft = Integer.parseInt(a[1]) * 20;
        steerElapsed = 0;
        line++;
        return false;
    }

    private static boolean commandTurnbase() {
        var f =
                HelmClient.body()
                        .logicalPose()
                        .orientation()
                        .transform(new org.joml.Vector3d(-1, 0, 0));
        turnBase = Math.atan2(f.z, -f.x);
        return true;
    }

    private static boolean commandTurnto(String arg) {
        var a = arg.split("\\s+");
        shipHeading = turnBase + Math.toRadians(Double.parseDouble(a[0]));
        headingLeft = Integer.parseInt(a[1]);
        line++;
        return false;
    }

    private static boolean commandHeadingread(String arg) {
        var f =
                HelmClient.body()
                        .logicalPose()
                        .orientation()
                        .transform(new org.joml.Vector3d(-1, 0, 0));
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE TURN {} relativeDegrees {}",
                        arg,
                        net.minecraft.util.Mth.wrapDegrees(
                                Math.toDegrees(Math.atan2(f.z, -f.x) - turnBase)));
        return true;
    }

    private static boolean commandTurn(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var body = HelmClient.body();
        if (body == null) {
            fail(mc, "turn needs helm");
            return false;
        }
        var f = body.logicalPose().orientation().transform(new org.joml.Vector3d(-1, 0, 0));
        shipHeading = Math.atan2(f.z, -f.x) + Math.toRadians(Double.parseDouble(a[0]));
        headingLeft = Integer.parseInt(a[1]);
        line++;
        return false;
    }

    private static boolean commandRequirepad(Minecraft mc) {
        if (HullLandingClient.site().kind() != HullLanding.Kind.PAD
                || !HullLandingClient.site().deployable()) {
            fail(mc, "pad/deployable required: " + HullLandingClient.site());
            return false;
        }
        return true;
    }

    private static boolean commandHeading(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        if (SpaceClient.active()) {
            var at = SpaceClient.universeEye(mc.player.position());
            var d =
                    SpaceClient.frame()
                            .levelVector(
                                    GroundClient.ephemeris()
                                            .at(Integer.parseInt(a[0]), SpaceClient.time())
                                            .center()
                                            .sub(at));
            shipHeading = Math.atan2(d.z(), -d.x());
            headingLeft = Integer.parseInt(a[1]);
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE heading requested planet {} angle {}", a[0], shipHeading);
            line++;
            return false;
        }
        return true;
    }

    private static boolean commandAirstairshot(String arg) {
        stairShot = arg;
        return true;
    }

    private static boolean commandPhotooff(Minecraft mc) {
        mc.setCameraEntity(mc.player);
        return true;
    }

    private static boolean commandPhotolocal(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var b =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level)
                        .getAllSubLevels()
                        .iterator()
                        .next();
        var bounds = b.getPlot().getBoundingBox();
        double x = (bounds.minX() + bounds.maxX() + 1) / 2d,
                z = (bounds.minZ() + bounds.maxZ() + 1) / 2d;
        var eye =
                b.logicalPose()
                        .transformPosition(
                                new org.joml.Vector3d(
                                        x + Double.parseDouble(a[0]),
                                        bounds.minY() + Double.parseDouble(a[1]),
                                        z + Double.parseDouble(a[2])));
        var target =
                b.logicalPose()
                        .transformPosition(
                                new org.joml.Vector3d(
                                        x + Double.parseDouble(a[3]),
                                        bounds.minY() + Double.parseDouble(a[4]),
                                        z + Double.parseDouble(a[5])));
        var camera =
                new net.minecraft.world.entity.decoration.ArmorStand(mc.level, eye.x, eye.y, eye.z);
        float cy = (float) Math.toDegrees(Math.atan2(eye.x - target.x, target.z - eye.z)),
                cp =
                        (float)
                                -Math.toDegrees(
                                        Math.atan2(
                                                target.y - eye.y,
                                                Math.hypot(target.x - eye.x, target.z - eye.z)));
        camera.setPos(eye.x, eye.y - camera.getEyeHeight(), eye.z);
        camera.setYRot(cy);
        camera.yRotO = cy;
        camera.setYHeadRot(cy);
        camera.yHeadRotO = cy;
        camera.setYBodyRot(cy);
        camera.yBodyRotO = cy;
        camera.setXRot(cp);
        camera.xRotO = cp;
        camera.setOldPosAndRot();
        mc.setCameraEntity(camera);
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE PHOTO separate client-only camera eye {} target {}; player unchanged",
                        eye,
                        target);
        return true;
    }

    private static boolean commandHud(Minecraft mc, String arg) {
        mc.options.hideGui = arg.equals("off");
        return true;
    }

    private static boolean commandFilm(Minecraft mc, String arg) {
        DevFilm.command(mc, arg);
        return true;
    }

    private static boolean commandView(Minecraft mc, String arg) {

        mc.options.setCameraType(net.minecraft.client.CameraType.valueOf(arg));
        return true;
    }

    private static boolean commandHudsize(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(
                mc.getWindow().getWindow(), Integer.parseInt(a[0]), Integer.parseInt(a[1]));
        mc.options.guiScale().set(Integer.parseInt(a[2]));
        mc.resizeDisplay();
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE HUDSIZE {} actual {}x{} scale {} layout {}",
                        arg,
                        mc.getWindow().getWidth(),
                        mc.getWindow().getHeight(),
                        mc.getWindow().getGuiScale(),
                        HudLayout.layout(
                                mc.getWindow().getGuiScaledWidth(),
                                mc.getWindow().getGuiScaledHeight()));
        return true;
    }

    private static boolean commandScopetier(Minecraft mc, String arg) {
        var b = HelmClient.body();
        if (b != null)
            for (var s : HelmClient.scopes(b)) {
                var facing =
                        s.getBlockState()
                                .getValue(
                                        net.minecraft.world.level.block.state.properties
                                                .BlockStateProperties.FACING);
                String block =
                        switch (arg) {
                            case "1" -> "scope";
                            case "2" -> "scope_nav";
                            case "3" -> "scope_precise";
                            default -> "";
                        };
                mc.player.connection.sendCommand(
                        "setblock "
                                + s.getBlockPos().toShortString().replace(",", "")
                                + " "
                                + (block.isEmpty()
                                        ? "minecraft:glass"
                                        : "starfold:"
                                                + block
                                                + "[facing="
                                                + facing.getSerializedName()
                                                + "]"));
            }
        return true;
    }

    private static boolean commandRemovescopes(Minecraft mc) {
        var b = HelmClient.body();
        if (b != null)
            for (var s : HelmClient.scopes(b))
                mc.player.connection.sendCommand(
                        "setblock "
                                + s.getBlockPos().toShortString().replace(",", "")
                                + " minecraft:glass");
        return true;
    }

    private static boolean commandFeeltest(Minecraft mc, String arg) {
        DevFeelProbe.start(mc, arg);
        line++;
        return false;
    }

    private static boolean commandPronefps(Minecraft mc, String arg) {
        mc.options.framerateLimit().set(Integer.parseInt(arg));
        return true;
    }

    private static boolean commandLanguage(Minecraft mc, String arg) {
        mc.getLanguageManager().setSelected(arg);
        mc.options.languageCode = arg;
        mc.reloadResourcePacks();
        wait = 100;
        line++;
        return false;
    }

    private static boolean commandFeelclick(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var key = a[0].equals("attack") ? mc.options.keyAttack : mc.options.keyUse;
        net.minecraft.client.KeyMapping.click(key.getKey());
        keys.put(a[0], a[1].equals("on"));
        return true;
    }

    private static boolean commandHelmview(String arg) {

        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE VIEW {} {}",
                        arg,
                        HelmClient.viewName() + " label " + HelmClient.viewLabel());
        return true;
    }

    private static boolean commandF5(Minecraft mc) {

        net.minecraft.client.KeyMapping.click(mc.options.keyTogglePerspective.getKey());
        return true;
    }

    private static boolean commandCamera(Minecraft mc, String arg) {
        var actual = mc.gameRenderer.getMainCamera();
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE VIEW {} {} look {} up {}",
                        arg,
                        HelmClient.viewName(),
                        actual.getLookVector(),
                        actual.getUpVector());
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE HELM {} pilot {}", arg, HelmClient.body() != null);
        var camera = mc.gameRenderer.getMainCamera();
        var b = dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(mc.player);
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE CAMERA {} type {} eye {} playerEye {} tracked {} bounds {} zoom {} slot {}",
                        arg,
                        mc.options.getCameraType(),
                        camera.getPosition(),
                        mc.player.getEyePosition(),
                        b == null ? "none" : b.getUniqueId(),
                        b == null ? "none" : b.getPlot().getBoundingBox(),
                        HelmClient.zoom(),
                        mc.player.getInventory().selected);
        var centre =
                b == null
                        ? new org.joml.Vector3d()
                        : b.logicalPose()
                                .transformPosition(
                                        new org.joml.Vector3d(
                                                (b.getPlot().getBoundingBox().minX()
                                                                + b.getPlot()
                                                                        .getBoundingBox()
                                                                        .maxX()
                                                                + 1)
                                                        / 2.0,
                                                (b.getPlot().getBoundingBox().minY()
                                                                + b.getPlot()
                                                                        .getBoundingBox()
                                                                        .maxY()
                                                                + 1)
                                                        / 2.0,
                                                (b.getPlot().getBoundingBox().minZ()
                                                                + b.getPlot()
                                                                        .getBoundingBox()
                                                                        .maxZ()
                                                                + 1)
                                                        / 2.0));
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE COCKPIT {} cameraYaw {} cameraPitch {} playerYaw {} playerPitch {} centre {} relative {} flight {} pointer {}",
                        arg,
                        camera.getYRot(),
                        camera.getXRot(),
                        mc.player.getYRot(),
                        mc.player.getXRot(),
                        centre,
                        camera.getPosition().subtract(centre.x, centre.y, centre.z),
                        SpaceAttitudeClient.mouseFlight(),
                        SpaceAttitudeClient.pointer());
        return true;
    }

    private static boolean commandDebug(Minecraft mc) {
        mc.getDebugOverlay().toggleOverlay();
        return true;
    }

    private static boolean commandShot(Minecraft mc, String arg) {
        String name = arg + ".png";
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE SHOT {} wall {} game {}",
                        name,
                        System.currentTimeMillis(),
                        mc.level.getGameTime());
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), m -> {});
        return true;
    }

    private static boolean commandJetvisible(String arg) {
        ThrusterRenderer.probeVisible = arg.equals("on");
        return true;
    }

    private static boolean commandJetload(Minecraft mc, String arg) {
        var body = HelmClient.body();
        int total = 0, powered = 0;
        if (body != null)
            for (var actor : body.getPlot().getBlockEntityActors())
                if (actor instanceof Thrusters.Thruster thruster) {
                    total++;
                    if (thruster.getBlockState()
                                    .getValue(
                                            net.minecraft.world.level.block.state.properties
                                                    .BlockStateProperties.POWER)
                            > 0) powered++;
                }
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE JETLOAD {} total {} powered {} plume {} fps {}",
                        arg,
                        total,
                        powered,
                        ThrusterRenderer.probeVisible,
                        mc.getFps());
        return true;
    }

    private static boolean commandFx(Minecraft mc, String arg) {

        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE FX {} fps {}{}",
                        arg,
                        mc.getFps(),
                        SpaceDustClient.probeReading() + ThrusterSoundClient.probeReading());
        return true;
    }

    private static boolean commandParticles(Minecraft mc, String arg) {

        mc.options.particles().set(net.minecraft.client.ParticleStatus.valueOf(arg));
        return true;
    }

    private static boolean commandLog(Minecraft mc, String arg) {
        var q = mc.player;
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE {} | pos {} {} {} yaw {} | fps {} | {} | {}{}",
                        arg,
                        String.format("%.2f", q.getX()),
                        String.format("%.2f", q.getY()),
                        String.format("%.2f", q.getZ()),
                        String.format("%.1f", q.getYRot()),
                        mc.getFps(),
                        GroundClient.active()
                                ? GroundClient.frame()
                                        + " face "
                                        + (GroundClient.ready() ? GroundClient.face() : -1)
                                : "-",
                        GroundClient.ready() ? GroundRenderer.performance() : "native inactive",
                        space(q.position()) + SpaceNavigationClient.probeReading());
        return true;
    }

    private static boolean commandScandown(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        int x = Integer.parseInt(a[0]),
                z = Integer.parseInt(a[1]),
                top = Integer.parseInt(a[2]),
                bottom = Integer.parseInt(a[3]);
        var log = org.slf4j.LoggerFactory.getLogger("starfold-probe");
        String previous = "";
        for (int y = top; y >= bottom; y--) {
            var at = new net.minecraft.core.BlockPos(x, y, z);
            String state = mc.level.getChunkAt(at).getBlockState(at).toString();
            if (!state.equals(previous)) {
                log.info(
                        "PROBE SCANDOWN {} {} {} raw {} loaded {}",
                        x,
                        y,
                        z,
                        state,
                        mc.level.getChunkSource().hasChunk(x >> 4, z >> 4));
                previous = state;
            }
        }
        return true;
    }

    private static boolean commandWalklocal(String arg) {
        var a = arg.split("\\s+");
        walkX = Double.parseDouble(a[0]);
        walkZ = Double.parseDouble(a[1]);
        walkLeft = Integer.parseInt(a[2]);
        walkJump = a.length > 3 && a[3].equals("jump");
        line++;
        return false;
    }

    private static boolean commandBoarding(Minecraft mc, String arg) {
        PilotPoseProbe.boarding(mc.player, arg);
        return true;
    }

    private static boolean commandLooklocal(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var b =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level)
                        .getAllSubLevels()
                        .iterator()
                        .next();
        var bounds = b.getPlot().getBoundingBox();
        var target =
                b.logicalPose()
                        .transformPosition(
                                new org.joml.Vector3d(
                                        (bounds.minX() + bounds.maxX() + 1) / 2.0
                                                + Double.parseDouble(a[0]),
                                        bounds.minY() + Double.parseDouble(a[1]),
                                        (bounds.minZ() + bounds.maxZ() + 1) / 2.0
                                                + Double.parseDouble(a[2])));
        var eye = mc.player.getEyePosition();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return true;
    }

    private static boolean commandHullblocks(Minecraft mc, String arg) {
        var bodies = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
        if (bodies != null)
            for (var b : bodies.getAllSubLevels()) {
                var bounds = b.getPlot().getBoundingBox();
                var counts = new TreeMap<String, Integer>();
                int n = 0;
                for (var at :
                        net.minecraft.core.BlockPos.betweenClosed(
                                bounds.minX(),
                                bounds.minY(),
                                bounds.minZ(),
                                bounds.maxX(),
                                bounds.maxY(),
                                bounds.maxZ())) {
                    if (++n > 100000) break;
                    var state = mc.level.getChunkAt(at).getBlockState(at);
                    if (!state.isAir()) counts.merge(state.toString(), 1, Integer::sum);
                }
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE HULLBLOCKS {} id {} plot {} counts {} capped {}",
                                arg,
                                b.getUniqueId(),
                                bounds,
                                counts,
                                n > 100000);
            }
        return true;
    }

    private static boolean commandBlockat(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var at =
                new net.minecraft.core.BlockPos(
                        Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE blockat {} = {} | loaded {}",
                        at.toShortString(),
                        mc.level.getBlockState(at),
                        mc.level.getChunkSource().hasChunk(at.getX() >> 4, at.getZ() >> 4));
        return true;
    }

    private static boolean commandAim(String arg) {
        track = arg.equals("track");
        if (!track && arg.equals("target")) GroundFlightClient.aimAtTarget();
        return true;
    }

    private static boolean commandSunlook(Minecraft mc, String arg) {
        if (!GroundClient.ready()) {
            fail(mc, "sun observer not ready");
            return false;
        }
        var sun = GroundClient.ephemeris().everyStar().get(Integer.parseInt(arg));
        if (GroundFlightClient.controlled()) {
            GroundFlightClient.probeAim(sun.position());
            return true;
        }
        var home = GroundClient.planetFrame(GroundClient.planet());
        var frame = PlanetOrbit.frame(GroundClient.shape(), GroundClient.state().pose());
        var d =
                ChartLabSpace.rotate(
                        frame.local(
                                home.inverseVector(sun.position().sub(GroundClient.universeEye()))),
                        GroundClient.frame().turns());
        yaw = (float) Math.toDegrees(Math.atan2(-d.x(), d.z()));
        pitch = (float) -Math.toDegrees(Math.atan2(d.y(), Math.hypot(d.x(), d.z())));
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE SUNLOOK {} radius {} yaw {} pitch {}", arg, sun.radius(), yaw, pitch);
        return true;
    }

    private static boolean commandSystem(Minecraft mc, String arg) {
        var model = GroundClient.ephemeris();
        if (model == null) {
            fail(mc, "system metadata not ready");
            return false;
        }
        double now = GroundClient.time();
        var logger = org.slf4j.LoggerFactory.getLogger("starfold-probe");
        for (double t : new double[] {now, Double.parseDouble(arg)}) {
            logger.info(
                    "PROBE SYSTEM tick {} suns {} planets {}", t, model.everyStar(), model.count());
            for (int id = 0; id < model.count(); id++)
                logger.info(
                        "PROBE PLANET tick {} id {} name {} size {} depth {} centre {} rotation {} spin {}",
                        t,
                        id,
                        PlanetSystem.name(id),
                        PlanetShape.of(id).size(),
                        PlanetShape.depth(id),
                        model.at(id, t).center(),
                        model.at(id, t).rotation(),
                        model.spin(id));
        }
        return true;
    }

    private static boolean commandBreaknear(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var at =
                mc.player
                        .blockPosition()
                        .offset(
                                Integer.parseInt(a[0]),
                                Integer.parseInt(a[1]),
                                Integer.parseInt(a[2]));
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE BREAK {} {}", at, mc.level.getBlockState(at));
        mc.gameMode.startDestroyBlock(at, net.minecraft.core.Direction.UP);
        return true;
    }

    private static boolean commandNav(Minecraft mc, String arg) {
        DevTripProbe.snapshot(mc, arg);

        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE nav {} | {} | {} hand {} | pos {} {} {}",
                        arg,
                        GroundFlightClient.probeReading(),
                        PlanetRemoteRenderer.probeReading(),
                        String.format(
                                "%.1f",
                                Math.hypot(
                                        mc.player.getYRot() - mc.player.yBob,
                                        mc.player.getXRot() - mc.player.xBob)),
                        String.format("%.1f", mc.player.getX()),
                        String.format("%.1f", mc.player.getY()),
                        String.format("%.1f", mc.player.getZ()));
        return true;
    }

    private static boolean commandUntil(String arg) {
        var a = arg.split("\\s+");
        until = a[0];
        untilValue = a.length > 2 ? Double.parseDouble(a[1]) : 0;
        untilLeft = Integer.parseInt(a[a.length - 1]);
        line++;
        return false;
    }

    private static boolean commandLight(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var at =
                new net.minecraft.core.BlockPos(
                        Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE light {} block {} sky {}",
                        at.toShortString(),
                        mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at),
                        mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, at));
        return true;
    }

    private static boolean commandBreak(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var at =
                new net.minecraft.core.BlockPos(
                        Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
        mc.gameMode.startDestroyBlock(at, net.minecraft.core.Direction.UP);
        return true;
    }

    private static boolean commandUse(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var at =
                new net.minecraft.core.BlockPos(
                        Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
        var face = net.minecraft.core.Direction.byName(a[3]);
        mc.gameMode.useItemOn(
                mc.player,
                net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(
                        net.minecraft.world.phys.Vec3.atCenterOf(at)
                                .add(
                                        net.minecraft.world.phys.Vec3.atLowerCornerOf(
                                                        face.getNormal())
                                                .scale(.5)),
                        face,
                        at,
                        false));
        return true;
    }

    // The same, on the block under the crosshair: a block of a Sable body is in its
    // plot, which no script can name.
    private static boolean commandUsehit(Minecraft mc, String arg) {
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE usehit {} | {}", arg, mc.level.getBlockState(hit.getBlockPos()));
            mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        } else
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE usehit {} | nothing", arg);
        return true;
    }

    // To where the guidance target will stand overhead so many ticks on, at a height:
    // "leadsite 6000 260".
    private static boolean commandLeadsite(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var spot = GroundFlightClient.under(Long.parseLong(a[0]));
        if (spot != null) {
            var feet =
                    GroundClient.access()
                            .feet(
                                    GroundClient.planet(),
                                    new PlanetShape.Pose(
                                            spot.face(),
                                            new dev.starfold.geometry.BentStripGeometry.Point(
                                                    spot.point().x(),
                                                    Double.parseDouble(a[1]),
                                                    spot.point().z())));
            String command =
                    String.format(
                            java.util.Locale.ROOT,
                            "tp @s %d.5 %s %d.5 180 20",
                            (int) Math.floor(feet.x()),
                            a[1],
                            (int) Math.floor(feet.z()));
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE leadsite {} | {}", arg, command);
            mc.player.connection.sendCommand(command);
        }
        return true;
    }

    // What the crosshair is on, without touching it.
    // A key through the client's own keyboard handler, as GLFW would deliver it: a GLFW
    // key, 1 down or 0 up.
    private static boolean commandScroll(Minecraft mc, String arg) {
        try {
            var method =
                    net.minecraft.client.MouseHandler.class.getDeclaredMethod(
                            "onScroll", long.class, double.class, double.class);
            method.setAccessible(true);
            int before = mc.player.getInventory().selected;
            method.invoke(mc.mouseHandler, mc.getWindow().getWindow(), 0d, Double.parseDouble(arg));
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE SCROLL notches {} factor {} slot {} -> {} helm {}",
                            arg,
                            HelmClient.zoom(),
                            before,
                            mc.player.getInventory().selected,
                            HelmClient.body() != null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return true;
    }

    private static boolean commandWeather(Minecraft mc, String arg) {

        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE WEATHER {} absent {} rain {} thunder {} flash {} region {} pos {} wall {}",
                        arg,
                        WeatherClient.absent(mc.level),
                        mc.level.getRainLevel(1),
                        mc.level.getThunderLevel(1),
                        mc.level.getSkyFlashTime(),
                        SpaceClient.present(),
                        mc.player.position(),
                        System.currentTimeMillis());
        return true;
    }

    private static boolean commandMouse(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        mouseX = Double.parseDouble(a[0]);
        mouseY = Double.parseDouble(a[1]);
        mouseTicks = Integer.parseInt(a[2]);
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE MOUSE START raw {} {} ticks {} sensitivity {} invert {} game {}",
                        mouseX,
                        mouseY,
                        mouseTicks,
                        mc.options.sensitivity().get(),
                        mc.options.invertYMouse().get(),
                        mc.level.getGameTime());
        return true;
    }

    private static boolean commandMouseaim(String arg) {
        var a = arg.split("\\s+");
        mouseAim = a[0].equals("locked") ? SpaceNavigationClient.locked() : Integer.parseInt(a[0]);
        mouseAimLeft = Integer.parseInt(a[1]);
        mouseMiss = a.length > 2 ? Double.parseDouble(a[2]) : Double.NaN;
        mouseArrival = a.length > 3 && a[3].equals("arrival");
        line++;
        return false;
    }

    private static boolean commandShipaim(String arg) {
        var a = arg.split("\\s+");
        shipAim = Integer.parseInt(a[0]);
        shipAimLeft = Integer.parseInt(a[1]);
        shipAimX = a.length > 2 ? Double.parseDouble(a[2]) : 1;
        shipAimY = a.length > 3 ? Double.parseDouble(a[3]) : 1;
        line++;
        return false;
    }

    private static boolean commandNavplanets(String arg) {
        var model = GroundClient.ephemeris();
        var frame = SpaceClient.frame();
        double tick = SpaceClient.time();
        var at = HelmClient.body().logicalPose().position();
        var ship = frame.universe(new BentStripGeometry.Point(at.x, at.y, at.z), model, tick);
        int rank = 0;
        for (int id : SpaceView.nearest(model, ship, tick, model.count()))
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE NAVPLANETS {} rank {} id {} name {} distance {} surface {} closing {} sun {}",
                            arg,
                            ++rank,
                            id,
                            PlanetSystem.name(id),
                            model.at(id, tick).center().sub(ship).length(),
                            SpaceNavigation.read(model, frame, ship, id, tick).distance(),
                            SpaceNavigation.read(model, frame, ship, id, tick).closing(),
                            model.parent(id, tick).center());
        return true;
    }

    private static boolean commandLock(Minecraft mc, String arg) {

        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE LOCK {} locked {} main {} off {}{}",
                        arg,
                        SpaceNavigationClient.locked(),
                        mc.player.getMainHandItem(),
                        mc.player.getOffhandItem(),
                        SpaceNavigationClient.probeReading());
        return true;
    }

    private static boolean commandPress(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        if (a[0].equals("mouse")) {
            try {
                var method =
                        net.minecraft.client.MouseHandler.class.getDeclaredMethod(
                                "onPress", long.class, int.class, int.class, int.class);
                method.setAccessible(true);
                method.invoke(
                        mc.mouseHandler,
                        mc.getWindow().getWindow(),
                        Integer.parseInt(a[1]),
                        Integer.parseInt(a[2]),
                        0);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        } else
            mc.keyboardHandler.keyPress(
                    mc.getWindow().getWindow(),
                    Integer.parseInt(a[0]),
                    0,
                    Integer.parseInt(a[1]),
                    0);
        return true;
    }

    private static boolean commandMilestone(Minecraft mc, String arg) {
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE MILESTONE {} wall {} tick {} health {} food {} pos {} screen {}",
                        arg,
                        System.currentTimeMillis(),
                        mc.level.getGameTime(),
                        mc.player.getHealth(),
                        mc.player.getFoodData().getFoodLevel(),
                        mc.player.position(),
                        mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE MILESTONE HUD {} {}", arg, SpaceNavigationClient.probeReading());
        return true;
    }

    // Looks about until the block under the crosshair is one whose name holds the word,
    // and keeps looking there:
    // a ship does not always come to rest where a script expects it.
    private static boolean commandFind(Minecraft mc, String arg) {
        boolean found = false;
        search:
        for (float down = 10; down <= 85; down += 3)
            for (float round = -180; round < 180; round += 3) {
                mc.player.setYRot(round);
                mc.player.setXRot(down);
                mc.gameRenderer.pick(1f);
                if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                        && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        && mc.level
                                .getBlockState(hit.getBlockPos())
                                .getBlock()
                                .toString()
                                .contains(arg)) {
                    yaw = round;
                    pitch = down;
                    found = true;
                    break search;
                }
            }
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE find {} | {}",
                        arg,
                        found ? "yaw " + yaw + " pitch " + pitch : "nothing");
        return true;
    }

    // Simulated's throttle lever set as its client sets it when dragged: the lever
    // looked at (remembered after), 0 to 15.
    private static boolean commandThrottle(Minecraft mc, String arg) {
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && mc.level
                        .getBlockState(hit.getBlockPos())
                        .getBlock()
                        .toString()
                        .contains("throttle")) lever = hit.getBlockPos();
        if (lever != null && arg.trim().equals("?")) {
            var be = mc.level.getBlockEntity(lever);
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE throttle ? | {} | {}",
                            lever.toShortString(),
                            be == null
                                    ? "no block entity"
                                    : be.saveWithoutMetadata(mc.level.registryAccess()));
        } else if (lever != null) {
            try {
                var payload =
                        (net.minecraft.network.protocol.common.custom.CustomPacketPayload)
                                Class.forName(
                                                "dev.simulated_team.simulated.network.packets.ThrottleLeverSignalPacket")
                                        .getConstructor(
                                                net.minecraft.core.BlockPos.class, int.class)
                                        .newInstance(lever, Integer.parseInt(arg.trim()));
                mc.player.connection.send(
                        new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                                payload));
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info("PROBE throttle {} | {}", arg, lever.toShortString());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        } else
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE throttle {} | nothing", arg);
        return true;
    }

    // A command about the remembered throttle lever, whose place among the plots no
    // script knows beforehand:
    // {lever} is that place, {lever:x,y,z} a place so far from it.
    private static boolean commandLeverdo(Minecraft mc, String arg) {
        if (lever != null) {
            var m =
                    java.util.regex.Pattern.compile("\\{lever(?::(-?\\d+),(-?\\d+),(-?\\d+))?\\}")
                            .matcher(arg);
            var out = new StringBuilder();
            while (m.find()) {
                var at =
                        m.group(1) == null
                                ? lever
                                : lever.offset(
                                        Integer.parseInt(m.group(1)),
                                        Integer.parseInt(m.group(2)),
                                        Integer.parseInt(m.group(3)));
                m.appendReplacement(out, at.getX() + " " + at.getY() + " " + at.getZ());
            }
            m.appendTail(out);
            mc.player.connection.sendCommand(out.toString());
        }
        return true;
    }

    private static boolean commandHit(Minecraft mc, String arg) {

        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE hit {} | {}",
                        arg,
                        mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                                        && hit.getType()
                                                == net.minecraft.world.phys.HitResult.Type.BLOCK
                                ? mc.level.getBlockState(hit.getBlockPos())
                                : "nothing");
        return true;
    }

    // The same, on a block counted from where the player stands.
    private static boolean commandUsenear(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var at =
                mc.player
                        .blockPosition()
                        .offset(
                                Integer.parseInt(a[0]),
                                Integer.parseInt(a[1]),
                                Integer.parseInt(a[2]));
        var face = net.minecraft.core.Direction.byName(a[3]);
        mc.gameMode.useItemOn(
                mc.player,
                net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(
                        net.minecraft.world.phys.Vec3.atCenterOf(at)
                                .add(
                                        net.minecraft.world.phys.Vec3.atLowerCornerOf(
                                                        face.getNormal())
                                                .scale(.5)),
                        face,
                        at,
                        false));
        return true;
    }

    private static boolean commandAsleep(Minecraft mc, String arg) {

        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE asleep {} | {} timer {}",
                        arg,
                        mc.player.isSleeping(),
                        mc.player.getSleepTimer());
        return true;
    }

    private static boolean commandEntities(Minecraft mc) {
        for (var e : mc.level.entitiesForRendering())
            if (e != mc.player && e.distanceTo(mc.player) < 96)
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE entity {} at {} {} {} yaw {}",
                                e.getType().toShortString(),
                                String.format("%.2f", e.getX()),
                                String.format("%.2f", e.getY()),
                                String.format("%.2f", e.getZ()),
                                String.format("%.1f", e.getYRot()));
        return true;
    }

    private static boolean commandEffects(Minecraft mc, String arg) {

        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE effects {} | {}",
                        arg,
                        mc.player.getActiveEffects().stream()
                                .map(e -> e.getEffect().getRegisteredName() + " " + e.getDuration())
                                .sorted()
                                .toList());
        return true;
    }

    private static boolean commandSurface(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        int x = Integer.parseInt(a[0]),
                z = Integer.parseInt(a[1]),
                y =
                        mc.level.getHeight(
                                        net.minecraft.world.level.levelgen.Heightmap.Types
                                                .WORLD_SURFACE,
                                        x,
                                        z)
                                - 1;
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE surface {} {} {} | {}",
                        x,
                        y,
                        z,
                        mc.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)));
        return true;
    }

    // What lies on the ground about the player: the top block of every column within a
    // radius, and what stands on it.
    private static boolean commandCensus(Minecraft mc, String arg) {
        int r = Integer.parseInt(arg);
        var counts = new java.util.TreeMap<String, Integer>();
        var at = mc.player.blockPosition();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int x = at.getX() + dx, z = at.getZ() + dz;
                if (!mc.level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                int y =
                        mc.level.getHeight(
                                        net.minecraft.world.level.levelgen.Heightmap.Types
                                                .WORLD_SURFACE,
                                        x,
                                        z)
                                - 1;
                counts.merge(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                .getKey(
                                        mc.level
                                                .getBlockState(
                                                        new net.minecraft.core.BlockPos(x, y, z))
                                                .getBlock())
                                .getPath(),
                        1,
                        Integer::sum);
            }
        org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE census {} | {}", r, counts);
        return true;
    }

    private static boolean commandExit(Minecraft mc) {
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE COMPLETE | line {} of {}", line + 1, script.size());
        mc.stop();
        line = script.size();
        return false;
    }

    private static boolean commandQuit(Minecraft mc) {
        // Client ticks continue when the server is stuck. Require a final server round
        // trip in verify.py.
        if (!finishing) {
            mc.player.connection.sendCommand("say CP_PROBE_SERVER_ACK");
            finishing = true;
            wait = 60;
            return false;
        }
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE COMPLETE | line {} of {}", line + 1, script.size());
        mc.stop();
        line = script.size();
        return false;
    }

    private static boolean commandUnknown() {
        throw new IllegalArgumentException("Probe line " + script.get(line));
    }

    private static String expand(String arg) {
        var m =
                java.util.regex.Pattern.compile(
                                "\\{(baseb|boardb|boarda)(?::(-?\\d+),(-?\\d+),(-?\\d+))?\\}")
                        .matcher(arg);
        var out = new StringBuilder();
        try {
            while (m.find()) {
                String value =
                        Files.readString(Path.of("../build/space/loop2-" + m.group(1) + ".txt"))
                                .trim();
                if (m.group(2) != null) {
                    var v = value.split(" ");
                    value =
                            (Integer.parseInt(v[0]) + Integer.parseInt(m.group(2)))
                                    + " "
                                    + (Integer.parseInt(v[1]) + Integer.parseInt(m.group(3)))
                                    + " "
                                    + (Integer.parseInt(v[2]) + Integer.parseInt(m.group(4)));
                }
                m.appendReplacement(out, value);
            }
            m.appendTail(out);
            return out.toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void fail(Minecraft mc, String reason) {
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .error("PROBE FAIL CLOSED {} scriptLine {}", reason, line + 1);
        pilotKeys(mc, Set.of());
        for (int k : new int[] {87, 83, 65, 68, 32, 340, 341})
            mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), k, 0, 0, 0);
        keys.clear();
        mc.options.keyUp.setDown(false);
        receipt = null;
        motionGuard = null;
        until = null;
        steerLeft = 0;
        sampleEvery = 0;
        mc.stop();
        line = script.size();
    }

    /** A simulated pilot, sampling the HUD geometry once a second; all actuation is ordinary keyboard input. */
    private static boolean steer(Minecraft mc) {
        var logger = org.slf4j.LoggerFactory.getLogger("starfold-probe");
        if (!SpaceClient.active()
                || --steerLeft <= 0
                || dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(mc.player) == null) {
            pilotKeys(mc, Set.of());
            logger.info(
                    "PROBE steer ended planet {} elapsed {} region {} remaining {}",
                    steerPlanet,
                    steerElapsed,
                    SpaceClient.active(),
                    steerLeft);
            steerLeft = 0;
            return false;
        }
        if (steerElapsed++ % 20 != 0) return true;
        var f = SpaceClient.frame();
        var model = GroundClient.ephemeris();
        double time = SpaceClient.time();
        var eye = SpaceClient.universeEye(mc.player.position());
        var d = f.levelVector(model.at(steerPlanet, time).center().sub(eye));
        double distance = d.length(), horizontal = Math.hypot(d.x(), d.z());
        var reading = SpaceNavigation.read(model, f, eye, steerPlanet, time);
        var velocity =
                f.levelVector(
                                f.universeVelocity(GroundEphemeris.ZERO, model, time)
                                        .sub(model.at(steerPlanet, time).velocity()))
                        .scale(20);
        double cruise = reading.distance() < 2200 ? 35 : reading.distance() < 4096 ? 60 : 120;
        boolean clearance = f.planet() != steerPlanet && d.y() < 0;
        double wantY = cruise * d.y() / distance, wantH = cruise * horizontal / distance;
        // Do not dive through the departure planet when the destination is below its horizon: climb
        // and go around it.
        if (clearance) {
            wantY = 30;
            wantH = 120;
        }
        var body = dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(mc.player);
        var forward = body.logicalPose().orientation().transform(new org.joml.Vector3d(-1, 0, 0));
        double error =
                Math.atan2(
                        Math.sin(Math.atan2(d.z(), -d.x()) - Math.atan2(forward.z, -forward.x)),
                        Math.cos(Math.atan2(d.z(), -d.x()) - Math.atan2(forward.z, -forward.x)));
        double along =
                horizontal > 1 ? (velocity.x() * d.x() + velocity.z() * d.z()) / horizontal : 0;
        var next = new HashSet<Integer>();
        if (horizontal / distance > .04 && Math.abs(error) > .12) next.add(error > 0 ? 65 : 68);
        if (Math.abs(error) < .25 && wantH > 6) {
            if (along < wantH - 5 && (reading.closing() < cruise || clearance)) {
                next.add(87);
                next.add(341);
            } else if (along > wantH + 20) next.add(83);
        }
        if (velocity.y() < wantY - 5) next.add(32);
        else if (velocity.y() > wantY + 5) next.add(340);
        // Departure safety uses the nearest/reference HUD closing, not velocity relative to the
        // distant destination.
        var referenceReading = SpaceNavigation.read(model, f, eye, f.planet(), time);
        if (f.planet() != steerPlanet) {
            next.remove(340);
            if (referenceReading.closing() > -25) next.add(32);
        }
        logger.info(
                "PROBE STEER clearance ref {} height {} closing {}",
                f.planet(),
                referenceReading.distance(),
                referenceReading.closing());
        pilotKeys(mc, next);
        logger.info(
                "PROBE STEER planet {} elapsed {} to {} height {} closing {} cruise {} velocity {} wantH {} wantY {} yawError {} clearance {} keys {}{}",
                steerPlanet,
                steerElapsed,
                d,
                reading.distance(),
                reading.closing(),
                cruise,
                velocity,
                wantH,
                wantY,
                error,
                clearance,
                next,
                SpaceNavigationClient.probeReading());
        if (steerElapsed == 1201) {
            yaw = 0f;
            pitch = 0f;
            Screenshot.grab(
                    mc.gameDirectory,
                    "space-trip4-mid-" + steerPlanet + ".png",
                    mc.getMainRenderTarget(),
                    m -> {});
        }
        return true;
    }

    private static void pilotKeys(Minecraft mc, Set<Integer> next) {
        for (int key : new HashSet<>(steerKeys))
            if (!next.contains(key))
                mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), key, 0, 0, 0);
        for (int key : next)
            if (!steerKeys.contains(key))
                mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), key, 0, 1, 0);
        steerKeys.clear();
        steerKeys.addAll(next);
    }

    private static String space(net.minecraft.world.phys.Vec3 position) {
        var at = SpaceClient.universeEye(position);
        if (at == null) return "";
        var f = SpaceClient.frame();
        var model = GroundClient.ephemeris();
        var out =
                new StringBuilder(
                        String.format(
                                Locale.ROOT,
                                " | space %.6f %.6f %.6f ref %d w %.6f",
                                at.x(),
                                at.y(),
                                at.z(),
                                f.planet(),
                                f.drift().length() * 20));
        for (int id = 0; id < Math.min(2, model.count()); id++) {
            var d = f.levelVector(model.at(id, SpaceClient.time()).center().sub(at));
            double distance = d.length();
            out.append(
                    String.format(
                            Locale.ROOT,
                            " | to %d %.6f %.6f %.6f %.6f",
                            id,
                            d.x() / distance,
                            d.y() / distance,
                            d.z() / distance,
                            distance));
        }
        var down = f.levelVector(model.at(f.planet(), SpaceClient.time()).center().sub(at));
        out.append(
                String.format(
                        Locale.ROOT,
                        " | downAngle %.9f wvec %.9f %.9f %.9f skyTick %.6f",
                        Math.toDegrees(Math.acos(Math.clamp(-down.y() / down.length(), -1, 1))),
                        f.drift().x() * 20,
                        f.drift().y() * 20,
                        f.drift().z() * 20,
                        SpaceClient.time()));
        for (int id = 0; id < Math.min(2, model.count()); id++) {
            var v = model.at(id, SpaceClient.time()).velocity();
            out.append(
                    String.format(
                            Locale.ROOT,
                            " | pv %d %.9f %.9f %.9f",
                            id,
                            v.x() * 20,
                            v.y() * 20,
                            v.z() * 20));
        }
        return out + " | sees " + PlanetRemoteRenderer.regionDrawn() + " planets";
    }

    /** Read-only telemetry; server commands are ordered, not an atomic physics snapshot. */
    private static void sample(Minecraft mc, String label) {
        var p = mc.player;
        var logger = org.slf4j.LoggerFactory.getLogger("starfold-probe");
        logger.info(
                "PROBE {} | pos {} | game {} wall {} | delta {}{}",
                label,
                p.position(),
                mc.level.getGameTime(),
                System.currentTimeMillis(),
                p.getDeltaMovement(),
                space(p.position()) + SpaceNavigationClient.probeReading());
        logger.info(
                "PROBE coverage {} | {}",
                label,
                GroundClient.ready() ? GroundRenderer.performance() : "native inactive");
        if (!SpaceClient.present()
                && mc.level.getChunkSource().hasChunk(p.getBlockX() >> 4, p.getBlockZ() >> 4)) {
            int surface =
                    mc.level.getHeight(
                            net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                            p.getBlockX(),
                            p.getBlockZ());
            logger.info(
                    "PROBE CHARTGROUND {} feet {} surface {} clearance {}",
                    label,
                    p.getY(),
                    surface,
                    p.getY() - surface);
        }
        logger.info(
                "PROBE abilities {} | mayfly {} flying {} creative {} ground {} health {} | gravity {}",
                label,
                p.getAbilities().mayfly,
                p.getAbilities().flying,
                p.isCreative(),
                p.onGround(),
                p.getHealth(),
                p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY));
        var bodies = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
        if (bodies != null)
            for (var b : bodies.getAllSubLevels()) {
                if (b.logicalPose().position().distance(p.getX(), p.getY(), p.getZ()) > 100)
                    continue;
                var local =
                        b.logicalPose()
                                .transformPositionInverse(
                                        new org.joml.Vector3d(p.getX(), p.getY(), p.getZ()));
                logger.info(
                        "PROBE CREW {} game {} local {} bounds {} gravity {} delta {}",
                        label,
                        mc.level.getGameTime(),
                        Arrays.toString(new double[] {local.x, local.y, local.z}),
                        b.getPlot().getBoundingBox(),
                        p.getAttributeValue(
                                net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY),
                        p.getDeltaMovement());
            }
        var body = dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(p);
        logger.info(
                "PROBE tracking {} | {}",
                label,
                body == null
                        ? "none"
                        : body.logicalPose()
                                .transformPositionInverse(
                                        new org.joml.Vector3d(p.getX(), p.getY(), p.getZ()))
                                .toString());
        logger.info(
                "PROBE LOOPVITAL {} game {} wall {} food {} air {} health {} clearance {}{}{}",
                label,
                mc.level.getGameTime(),
                System.currentTimeMillis(),
                p.getFoodData().getFoodLevel(),
                p.getAirSupply(),
                p.getHealth(),
                HullLandingClient.clearance(),
                SpaceDustClient.probeReading(),
                ThrusterSoundClient.probeReading());
        logger.info(
                "PROBE HELM {} pilot {} state {}",
                label,
                HelmClient.body() != null,
                body == null ? null : HelmInputClient.state(body.getUniqueId()));
        if (SpaceClient.active()) {
            var f = SpaceClient.frame();
            var model = GroundClient.ephemeris();
            double t = SpaceClient.time();
            var q = f.rotation();
            logger.info(
                    "PROBE frame {} | tick {} rotation {} shift {} ref {} centre {} drift {}",
                    label,
                    t,
                    Arrays.toString(new double[] {q.x, q.y, q.z, q.w}),
                    f.shift(),
                    f.planet(),
                    model.at(f.planet(), t).center(),
                    model.at(f.planet(), t).velocity());
        }
        for (String cmd :
                new String[] {
                    "tellraw @s \"TRIP_SAMPLE " + label + "\"",
                    "time query gametime",
                    "data get entity @s Pos",
                    "data get entity @s Motion",
                    "attribute @s minecraft:generic.gravity get",
                    "sable info @n"
                }) p.connection.sendCommand(cmd);
    }

    /** Inject raw deltas immediately before vanilla sensitivity/inversion, not into our attitude API. */
    private static void mouse(Minecraft mc) {
        try {
            var c = net.minecraft.client.MouseHandler.class;
            var x = c.getDeclaredField("accumulatedDX");
            var y = c.getDeclaredField("accumulatedDY");
            var turn = c.getDeclaredMethod("turnPlayer", double.class);
            x.setAccessible(true);
            y.setAccessible(true);
            turn.setAccessible(true);
            double oldX = x.getDouble(mc.mouseHandler), oldY = y.getDouble(mc.mouseHandler);
            try {
                x.setDouble(mc.mouseHandler, mouseX);
                y.setDouble(mc.mouseHandler, mouseY);
                turn.invoke(mc.mouseHandler, .05);
            } finally {
                x.setDouble(mc.mouseHandler, oldX);
                y.setDouble(mc.mouseHandler, oldY);
            }
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE MOUSE INPUT game {} raw {} {} flight {} yaw {} pitch {} probeTick {} wall {}",
                            mc.level.getGameTime(),
                            mouseX,
                            mouseY,
                            SpaceAttitudeClient.mouseFlight(),
                            mc.player.getYRot(),
                            mc.player.getXRot(),
                            probeTick,
                            System.currentTimeMillis());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MouseHandler probe contract", e);
        }
    }

    public static void mouseFrame(SpacePackets.Frame packet) {
        if (!tracing || packet.frame() == null) return;
        var mc = Minecraft.getInstance();
        var q = packet.frame().rotation();
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE MOUSE FRAME game {} sky {} q {} yaw {} pitch {} probeTick {} wall {}",
                        mc.level.getGameTime(),
                        packet.tick(),
                        Arrays.toString(new double[] {q.x, q.y, q.z, q.w}),
                        mc.player.getYRot(),
                        mc.player.getXRot(),
                        probeTick,
                        System.currentTimeMillis());
    }

    private static void trace(Minecraft mc) {
        var p = mc.player;
        var body = dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(p);
        var logger = org.slf4j.LoggerFactory.getLogger("starfold-probe");
        logger.info(
                "PROBE TRACE | wall {} game {} pos {} delta {} ground {} tracking {} chunk {}{}",
                System.currentTimeMillis(),
                mc.level.getGameTime(),
                p.position(),
                p.getDeltaMovement(),
                p.onGround(),
                body == null ? "none" : System.identityHashCode(body),
                mc.level.getChunkSource().hasChunk(p.getBlockX() >> 4, p.getBlockZ() >> 4),
                space(p.position()) + SpaceNavigationClient.probeReading());
        if (SpaceClient.active()) {
            var f = SpaceClient.frame();
            double a = Math.toRadians(p.getYRot());
            var nose = f.universeVector(new BentStripGeometry.Point(-Math.sin(a), 0, Math.cos(a)));
            double speed = f.drift().length() * 20,
                    angle =
                            speed < 1e-9
                                    ? 0
                                    : Math.toDegrees(
                                            Math.acos(
                                                    Math.clamp(
                                                            f.drift().dot(nose) * 20 / speed,
                                                            -1,
                                                            1)));
            logger.info(
                    "PROBE ASSIST game {} sky {} speed {} angle {} nose {} pointer {}",
                    mc.level.getGameTime(),
                    SpaceClient.time(),
                    speed,
                    angle,
                    nose,
                    SpaceAttitudeClient.pointer());
        }
        if (body != null) {
            var pose = body.logicalPose();
            var at = pose.position();
            var q = pose.orientation();
            var local =
                    pose.transformPositionInverse(
                            new org.joml.Vector3d(p.getX(), p.getY(), p.getZ()));
            logger.info(
                    "PROBE TRACEBODY | pos {} q {} local {}",
                    Arrays.toString(new double[] {at.x, at.y, at.z}),
                    Arrays.toString(new double[] {q.x, q.y, q.z, q.w}),
                    Arrays.toString(new double[] {local.x, local.y, local.z}));
        }
        logger.info(
                "PROBE TRACEHELM pilot {} state {} input {} {} jump {} sneak {}",
                HelmClient.body() != null,
                body == null ? null : HelmInputClient.state(body.getUniqueId()),
                p.input.forwardImpulse,
                p.input.leftImpulse,
                p.input.jumping,
                p.input.shiftKeyDown);
    }

    /** From the first client tick, including terrain-loading screens and untracked riders. */
    private static void rejoin(Minecraft mc) {
        var p = mc.player;
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
        if (container == null) return;
        var tracking = dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(p);
        var logger = org.slf4j.LoggerFactory.getLogger("starfold-probe");
        logger.info(
                "PROBE REJOIN tick {} pos {} tracking {} gravity {} ground {}",
                mc.level.getGameTime(),
                p.position(),
                tracking == null ? "none" : tracking.getUniqueId(),
                p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY),
                p.onGround());
        for (var body : container.getAllSubLevels()) {
            var at = body.logicalPose().position();
            if (at.distance(p.getX(), p.getY(), p.getZ()) > 1000) continue;
            var local =
                    body.logicalPose()
                            .transformPositionInverse(
                                    new org.joml.Vector3d(p.getX(), p.getY(), p.getZ()));
            logger.info(
                    "PROBE REJOINBODY tick {} body {} pos {} local {}",
                    mc.level.getGameTime(),
                    body.getUniqueId(),
                    Arrays.toString(new double[] {at.x, at.y, at.z}),
                    Arrays.toString(new double[] {local.x, local.y, local.z}));
        }
    }

    /** Read-only corner observations: client frame and carrier-local coordinates, without correcting either. */
    private static void corner(Minecraft mc, String label) {
        var p = mc.player;
        var log = org.slf4j.LoggerFactory.getLogger("starfold-probe");
        var storage = GroundClient.storage(p.getX(), p.getY(), p.getZ());
        log.info(
                "PROBE CORNER {} tick {} pos {} storage {} frame {} ground {} health {} effects {} vehicle {} collision {} {} flying {}",
                label,
                mc.level.getGameTime(),
                p.position(),
                storage,
                GroundClient.frame(),
                p.onGround(),
                p.getHealth(),
                p.getActiveEffects().stream()
                        .map(e -> e.getEffect().getRegisteredName() + ":" + e.getDuration())
                        .sorted()
                        .toList(),
                p.getVehicle() == null
                        ? "none"
                        : p.getVehicle().getUUID()
                                + " "
                                + p.getVehicle().getType()
                                + " "
                                + p.getVehicle().position(),
                p.horizontalCollision,
                p.verticalCollision,
                p.getAbilities().flying);
        var at = p.blockPosition();
        log.info(
                "PROBE CORNERCELL {} masked {} feet {} under {}",
                label,
                GroundSeams.clientMasked(mc.level, at),
                mc.level.getBlockState(at),
                mc.level.getBlockState(at.below()));
        log.info(
                "PROBE CLOSURE {} realCorners {} rawFeet {}",
                label,
                GroundClient.ready() ? GroundRenderer.realCorners(GroundClient.face()) : 0,
                mc.level.getChunkAt(at).getBlockState(at));
        var bodies = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
        var tracked = dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(p);
        if (bodies != null)
            for (var b : bodies.getAllSubLevels()) {
                var pose = b.logicalPose();
                if (pose.position().distance(p.getX(), p.getY(), p.getZ()) > 600) continue;
                var q = pose.orientation();
                var local =
                        pose.transformPositionInverse(
                                new org.joml.Vector3d(p.getX(), p.getY(), p.getZ()));
                log.info(
                        "PROBE CORNERBODY {} id {} tracked {} pos {} q {} local {} bounds {}",
                        label,
                        b.getUniqueId(),
                        b == tracked,
                        Arrays.toString(
                                new double[] {
                                    pose.position().x(), pose.position().y(), pose.position().z()
                                }),
                        Arrays.toString(new double[] {q.x, q.y, q.z, q.w}),
                        Arrays.toString(new double[] {local.x, local.y, local.z}),
                        Arrays.toString(
                                new double[] {
                                    b.boundingBox().minX(),
                                    b.boundingBox().minY(),
                                    b.boundingBox().minZ(),
                                    b.boundingBox().maxX(),
                                    b.boundingBox().maxY(),
                                    b.boundingBox().maxZ()
                                }));
            }
    }

    /** Read-only cargo census. Local coordinates use the same inverse full pose as the pilot trace. */
    private static void cargo(Minecraft mc, String label, boolean server) {
        var log = org.slf4j.LoggerFactory.getLogger("starfold-probe");
        var bodies = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
        log.info(
                "PROBE CARGO {} tick {} frame {} region {}",
                label,
                mc.level.getGameTime(),
                GroundClient.frame(),
                SpaceClient.present());
        if (bodies != null)
            for (var b : bodies.getAllSubLevels()) {
                var pose = b.logicalPose();
                log.info(
                        "PROBE CARGOBODY {} id {} pos {} bounds {}",
                        label,
                        b.getUniqueId(),
                        pose.position(),
                        b.getPlot().getBoundingBox());
                for (var e : mc.level.entitiesForRendering())
                    if (e == mc.player
                            || e.getName().getString().startsWith("cargo")
                            || e.getType() == net.minecraft.world.entity.EntityType.ITEM_FRAME
                            || e.getType().toShortString().contains("seat")) {
                        var box = b.getPlot().getBoundingBox();
                        boolean inPlot =
                                e.getX() >= box.minX() - 1
                                        && e.getX() <= box.maxX() + 2
                                        && e.getZ() >= box.minZ() - 1
                                        && e.getZ() <= box.maxZ() + 2;
                        var local =
                                inPlot
                                        ? new org.joml.Vector3d(e.getX(), e.getY(), e.getZ())
                                        : pose.transformPositionInverse(
                                                new org.joml.Vector3d(
                                                        e.getX(), e.getY(), e.getZ()));
                        var tracked = dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(e);
                        if (server && inPlot)
                            log.info(
                                    "PROBE CARGOPLOT {} uuid {} direct true item {}",
                                    label,
                                    e.getUUID(),
                                    e instanceof net.minecraft.world.entity.decoration.ItemFrame f
                                            ? f.getItem()
                                            : "none");
                        log.info(
                                "PROBE CARGOENTITY {} tick {} body {} uuid {} name {} type {} pos {} local {} alive {} health {} tracked {} vehicle {} pose {}",
                                label,
                                mc.level.getGameTime(),
                                b.getUniqueId(),
                                e.getUUID(),
                                e.getName().getString(),
                                e.getType().toShortString(),
                                e.position(),
                                Arrays.toString(new double[] {local.x, local.y, local.z}),
                                e.isAlive(),
                                e instanceof net.minecraft.world.entity.LivingEntity l
                                        ? l.getHealth()
                                        : -1,
                                tracked == null ? "none" : tracked.getUniqueId(),
                                e.getVehicle() == null ? "none" : e.getVehicle().getUUID(),
                                e.getPose());
                    }
                if (server) {
                    var box = b.getPlot().getBoundingBox();
                    int n = 0;
                    for (var at :
                            net.minecraft.core.BlockPos.betweenClosed(
                                    box.minX(),
                                    box.minY(),
                                    box.minZ(),
                                    box.maxX(),
                                    box.maxY(),
                                    box.maxZ())) {
                        if (++n > 100000) break;
                        if (mc.level.getBlockState(at).getBlock()
                                instanceof net.minecraft.world.level.block.ChestBlock)
                            mc.player.connection.sendCommand(
                                    "data get block "
                                            + at.getX()
                                            + " "
                                            + at.getY()
                                            + " "
                                            + at.getZ()
                                            + " Items");
                    }
                }
            }
        if (server) {
            mc.player.connection.sendCommand("tellraw @s \"CARGO_SERVER " + label + "\"");
            mc.player.connection.sendCommand("execute as @e[tag=cargo] run data get entity @s");
            mc.player.connection.sendCommand(
                    "execute as @e[type=create:seat] run data get entity @s");
            mc.player.connection.sendCommand("sable info @e");
        }
    }

    private DevProbe() {}
}
