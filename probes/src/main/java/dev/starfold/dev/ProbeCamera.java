package dev.starfold.dev;

import static dev.starfold.dev.ProbeSession.*;
import static dev.starfold.dev.ProbeInput.*;
import static dev.starfold.dev.ProbeSamples.*;

import dev.starfold.chart.PlanetOrbit;
import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.flight.HudLayout;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.render.GroundClient;
import dev.starfold.ship.Airstair;
import dev.starfold.ship.HelmClient;
import dev.starfold.ship.ThrusterRenderer;
import dev.starfold.ship.ThrusterSoundClient;
import dev.starfold.ship.Thrusters;
import dev.starfold.space.SpaceAttitudeClient;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceDustClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.file.*;
import java.util.*;

/** Scripted camera and presentation. */
final class ProbeCamera {
    static void initialize() {
        // "shipcam orbit <degrees per second>" carries the player round the target on an exact
        // circle, placed anew before every rendered frame
        // from the wall clock, the same clock the recorder samples: flying the circle by keys is
        // stepped by ticks and shows as a tremor.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) -> {
                    if (shipCam == null || shipCamRate == 0 && shipCamRise == 0) return;
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.level == null || mc.player == null) return;
                    var hulls =
                            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                                            mc.level)
                                    .getAllSubLevels()
                                    .iterator();
                    if (!hulls.hasNext()) return;
                    double seconds = (System.nanoTime() - shipCamStart) / 1e9;
                    double angle = shipCamAngle + Math.toRadians(shipCamRate) * seconds;
                    double radius = shipCamRadius + shipCamOut * seconds;
                    var at =
                            hulls.next()
                                    .logicalPose()
                                    .transformPosition(
                                            new org.joml.Vector3d(shipCam)
                                                    .add(
                                                            Math.cos(angle) * radius,
                                                            shipCamUp + shipCamRise * seconds,
                                                            Math.sin(angle) * radius));
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
    }

    static long probeTick;

    static String stairShot;

    static long shipCamStart;

    static double shipCamRadius, shipCamUp, shipCamAngle, shipCamRate, shipCamRise, shipCamOut;

    static org.joml.Vector3d shipCam;

    static void updatePresentation(Minecraft mc) {
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

    // Film the first hull from outside with the player as the camera: "shipcam off",
    // "shipcam center <back> <up> [degrees]" looks at the
    // hull's middle, "shipcam <block> <back> <up> [degrees] [x|-x|y|-y|z|-z]" at the
    // block of that name furthest along an axis. The view keeps following.
    static boolean commandShipcam(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        if (a[0].equals("off")) {
            shipCam = null;
            shipCamRate = shipCamRise = shipCamOut = 0;
            yaw = null;
            pitch = null;
        } else if (a[0].equals("orbit")) {
            shipCamRate = Double.parseDouble(a[1]);
            shipCamStart = System.nanoTime();
        } else if (a[0].equals("hold")) {
            // "shipcam hold" stops a rise where it is, still looking at the hull.
            double seconds = (System.nanoTime() - shipCamStart) / 1e9;
            shipCamRadius += shipCamOut * seconds;
            shipCamUp += shipCamRise * seconds;
            shipCamAngle += Math.toRadians(shipCamRate) * seconds;
            shipCamRate = shipCamRise = shipCamOut = 0;
        } else if (a[0].equals("rise")) {
            // "shipcam rise <up per second> <out per second> [degrees per second]" climbs away from
            // the hull while still looking at it.
            shipCamRise = Double.parseDouble(a[1]);
            shipCamOut = Double.parseDouble(a[2]);
            shipCamRate = a.length > 3 ? Double.parseDouble(a[3]) : 0;
            shipCamStart = System.nanoTime();
        } else {
            shipCamRate = shipCamRise = shipCamOut = 0;
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

    static boolean commandFocus(Minecraft mc) {
        org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().getWindow());
        return true;
    }

    static boolean commandProbeui(Minecraft mc) {
        mc.options.guiScale().set(3);
        mc.resizeDisplay();
        mc.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.FULL);
        mc.options.chatOpacity().set(0d);
        mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        return true;
    }

    static boolean commandSkylook(Minecraft mc, String arg) {
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

    static boolean commandAirstairshot(String arg) {
        stairShot = arg;
        return true;
    }

    static boolean commandPhotooff(Minecraft mc) {
        mc.setCameraEntity(mc.player);
        return true;
    }

    static boolean commandPhotolocal(Minecraft mc, String arg) {
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

    static boolean commandHud(Minecraft mc, String arg) {
        mc.options.hideGui = arg.equals("off");
        return true;
    }

    static boolean commandFilm(Minecraft mc, String arg) {
        DevFilm.command(mc, arg);
        return true;
    }

    static boolean commandView(Minecraft mc, String arg) {

        mc.options.setCameraType(net.minecraft.client.CameraType.valueOf(arg));
        return true;
    }

    static boolean commandHudsize(Minecraft mc, String arg) {
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

    static boolean commandF5(Minecraft mc) {

        net.minecraft.client.KeyMapping.click(mc.options.keyTogglePerspective.getKey());
        return true;
    }

    static boolean commandCamera(Minecraft mc, String arg) {
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

    static boolean commandDebug(Minecraft mc) {
        mc.getDebugOverlay().toggleOverlay();
        return true;
    }

    static boolean commandShot(Minecraft mc, String arg) {
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

    static boolean commandJetvisible(String arg) {
        ThrusterRenderer.probeVisible = arg.equals("on");
        return true;
    }

    static boolean commandJetload(Minecraft mc, String arg) {
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

    static boolean commandFx(Minecraft mc, String arg) {

        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE FX {} fps {}{}",
                        arg,
                        mc.getFps(),
                        SpaceDustClient.probeReading() + ThrusterSoundClient.probeReading());
        return true;
    }

    static boolean commandParticles(Minecraft mc, String arg) {

        mc.options.particles().set(net.minecraft.client.ParticleStatus.valueOf(arg));
        return true;
    }

    static boolean commandSunlook(Minecraft mc, String arg) {
        if (!GroundClient.ready()) {
            fail(mc, "sun observer not ready");
            return false;
        }
        var model = GroundClient.ephemeris();
        int gas =
                arg.equals("gas")
                        ? model.catalogIndices().stream()
                                .filter(i -> model.catalogBody(i).gas().isPresent())
                                .findFirst()
                                .orElseThrow()
                        : -1;
        var target =
                gas >= 0
                        ? model.frame(gas, GroundClient.time()).center()
                        : model.everyStar().get(Integer.parseInt(arg)).position();
        if (GroundFlightClient.controlled()) {
            GroundFlightClient.probeAim(target);
            return true;
        }
        var home = GroundClient.planetFrame(GroundClient.planet());
        var frame = PlanetOrbit.frame(GroundClient.shape(), GroundClient.state().pose());
        var d =
                ChartLabSpace.rotate(
                        frame.local(
                                home.inverseVector(target.sub(GroundClient.universeEye()))),
                        GroundClient.frame().turns());
        yaw = (float) Math.toDegrees(Math.atan2(-d.x(), d.z()));
        pitch = (float) -Math.toDegrees(Math.atan2(d.y(), Math.hypot(d.x(), d.z())));
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE SUNLOOK {} target {} yaw {} pitch {}", arg, target, yaw, pitch);
        return true;
    }
}
