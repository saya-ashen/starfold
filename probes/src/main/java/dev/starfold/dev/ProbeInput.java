package dev.starfold.dev;

import static dev.starfold.dev.ProbeSession.*;
import static dev.starfold.dev.ProbeCamera.*;
import static dev.starfold.dev.ProbeSamples.*;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.render.GroundClient;
import dev.starfold.render.PlanetRemoteRenderer;
import dev.starfold.ship.HelmClient;
import dev.starfold.space.SpacePackets;
import dev.starfold.sky.Space;
import dev.starfold.space.SpaceAttitudeClient;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceNavigation;
import dev.starfold.space.SpaceNavigationClient;
import dev.starfold.space.SpaceView;
import dev.starfold.waypoint.WaypointClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.file.*;
import java.util.*;

/** Script-owned keyboard, mouse and flight input. */
final class ProbeInput {
    static void release(Minecraft mc) {
        DevAtlasProbe.cancel(mc);
        pilotKeys(mc, Set.of());
        for (int key : new int[] {87, 83, 65, 68, 32, 340, 341})
            mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), key, 0, 0, 0);
        for (String name : keys.keySet()) {
            var key = switch (name) {
                case "forward" -> mc.options.keyUp;
                case "back" -> mc.options.keyDown;
                case "left" -> mc.options.keyLeft;
                case "right" -> mc.options.keyRight;
                case "jump" -> mc.options.keyJump;
                case "sneak" -> mc.options.keyShift;
                case "sprint" -> mc.options.keySprint;
                case "use" -> mc.options.keyUse;
                case "attack" -> mc.options.keyAttack;
                default -> null;
            };
            if (key != null) key.setDown(false);
        }
        keys.clear();
        mc.options.keyUp.setDown(false);
        steerLeft = walkLeft = mouseTicks = mouseAimLeft = shipAimLeft = homeLeft = headingLeft = 0;
        track = false;
        yaw = pitch = null;
        shipCam = null;
        SpaceClient.frameListener = frame -> {};
    }

    static boolean track;

    static Float yaw, pitch;

    static final Set<Integer> steerKeys = new HashSet<>();

    static int steerPlanet, steerLeft, steerElapsed;

    static double headingPrevious = Double.NaN;

    static int headingLeft, headingKey, headingStable;

    static Double shipHeading;

    static double turnBase;

    static boolean homeTurn;

    static int homeStable;

    static double homeStop, homePrevious, homeRate;

    static int homeLeft;

    static double mouseX, mouseY;

    static int mouseTicks;

    static boolean walkJump;

    static int walkLeft;

    static double walkX, walkZ;

    static double shipAimX = 1, shipAimY = 1;

    static int shipAim, shipAimLeft;

    static boolean mouseArrival;

    static double mouseMiss = Double.NaN;

    static int mouseAim = -1, mouseAimLeft;

    static net.minecraft.core.BlockPos lever;

    static final Map<String, Boolean> keys = new HashMap<>();

    static boolean walkStep(Minecraft mc) {
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

    static void updateTracking(Minecraft mc) {
        if (track) GroundFlightClient.aimAtTarget();
        if (mouseAimLeft > 0 && mouseArrival && SpaceClient.arriving()) {
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE MOUSEAIM arrival took control remaining {}", mouseAimLeft);
            mouseAimLeft = 0;
        }
    }

    static boolean shipAimStep(Minecraft mc) {
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

    static boolean mouseAimStep(Minecraft mc) {
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

    static boolean homeStep(Minecraft mc) {
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

    static boolean headingStep(Minecraft mc) {
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

    static boolean commandBoardpilot(Minecraft mc, String arg) {
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

    // Stand beside the first block of the first hull whose name contains the argument:
    // "boardblock helm [n]" tries the n-th free side.
    static boolean commandBoardblock(Minecraft mc, String arg) {
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

    static boolean commandKey(String arg) {
        var a = arg.split("\\s+");
        keys.put(a[0], a[1].equals("on"));
        return true;
    }

    static boolean commandLook(String arg) {
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
    static boolean commandFly(Minecraft mc, String arg) {
        mc.player.getAbilities().flying = arg.equals("on") && mc.player.getAbilities().mayfly;
        mc.player.onUpdateAbilities();
        return true;
    }

    static boolean commandAbilities(Minecraft mc, String arg) {

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

    static boolean commandUseifidle(Minecraft mc) {
        if (HelmClient.body() == null
                && mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)
            mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        return true;
    }

    static boolean commandBurn(Minecraft mc, String arg) {
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

    static boolean commandOpenhitdoor(Minecraft mc) {
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

    static boolean commandSelecthome(String arg) {
        return commandSelecthome(arg, "selecthome");
    }

    static boolean commandSelectplanet(String arg) {
        return commandSelecthome(arg, "selectplanet");
    }

    static boolean commandSelecthome(String arg, String command) {
        int target = command.equals("selecthome") ? 0 : Integer.parseInt(arg);
        if (SpaceNavigationClient.locked() != target) {
            var updated = new ArrayList<>(cursor.lines);
            updated.addAll(
                    cursor.line + 1,
                    List.of("press 71 1", "press 71 0", "wait 5", "selectplanet " + target));
            cursor.lines = updated;
        }
        return true;
    }

    static boolean commandDepartdetour() {
        int planet = SpaceClient.frame().planet();
        var updated = new ArrayList<>(cursor.lines);
        updated.addAll(
                cursor.line + 1,
                List.of(
                        "mouseaim " + (-planet - 1) + " 400 NaN arrival",
                        "press 87 1",
                        "until height> 6500 2000",
                        "milestone outward-clear",
                        "mouseaim " + planet + " 300 6000 arrival",
                        "wait 300",
                        "milestone around"));
        cursor.lines = updated;
        return true;
    }

    static boolean commandStairwalk(Minecraft mc, String arg) {
        var updated = new ArrayList<>(cursor.lines);
        updated.addAll(cursor.line + 1, DevLandingProbe.stairs(mc, arg.equals("back")));
        cursor.lines = updated;
        return true;
    }

    static boolean commandFollowlanding(String arg) {
        var a = arg.split("\\s+");
        DevLandingProbe.follow(
                Integer.parseInt(a[0]), a.length > 1 && a[1].equals("informational"));
        cursor.line++;
        return false;
    }

    static boolean commandGroundwalk(Minecraft mc, String arg) {
        yaw = null;
        DevLandingProbe.walk(mc, arg.equals("back"));
        cursor.line++;
        return false;
    }

    static boolean commandTravelstart(Minecraft mc) {
        walkStart = mc.player.position();
        return true;
    }

    static boolean commandHome(String arg) {
        var a = arg.split("\\s+");
        homeTurn = a[0].equals("turn");
        homeStop = homeTurn ? 0 : Double.parseDouble(a[0]);
        homeLeft = Integer.parseInt(a[1]);
        homePrevious = WaypointClient.guidance() == null ? 0 : WaypointClient.guidance().angle();
        homeRate = 0;
        homeStable = 0;
        cursor.line++;
        return false;
    }

    static boolean commandChartheading(String arg) {
        var a = arg.split("\\s+");
        shipHeading = Math.toRadians(Double.parseDouble(a[0]));
        headingLeft = Integer.parseInt(a[1]);
        cursor.line++;
        return false;
    }

    static boolean commandSteer(String arg) {
        var a = arg.split("\\s+");
        steerPlanet = Integer.parseInt(a[0]);
        steerLeft = Integer.parseInt(a[1]) * 20;
        steerElapsed = 0;
        cursor.line++;
        return false;
    }

    static boolean commandTurnbase() {
        var f =
                HelmClient.body()
                        .logicalPose()
                        .orientation()
                        .transform(new org.joml.Vector3d(-1, 0, 0));
        turnBase = Math.atan2(f.z, -f.x);
        return true;
    }

    static boolean commandTurnto(String arg) {
        var a = arg.split("\\s+");
        shipHeading = turnBase + Math.toRadians(Double.parseDouble(a[0]));
        headingLeft = Integer.parseInt(a[1]);
        cursor.line++;
        return false;
    }

    static boolean commandTurn(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var body = HelmClient.body();
        if (body == null) {
            fail(mc, "turn needs helm");
            return false;
        }
        var f = body.logicalPose().orientation().transform(new org.joml.Vector3d(-1, 0, 0));
        shipHeading = Math.atan2(f.z, -f.x) + Math.toRadians(Double.parseDouble(a[0]));
        headingLeft = Integer.parseInt(a[1]);
        cursor.line++;
        return false;
    }

    static boolean commandHeading(Minecraft mc, String arg) {
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
            cursor.line++;
            return false;
        }
        return true;
    }

    static boolean commandWalklocal(String arg) {
        var a = arg.split("\\s+");
        walkX = Double.parseDouble(a[0]);
        walkZ = Double.parseDouble(a[1]);
        walkLeft = Integer.parseInt(a[2]);
        walkJump = a.length > 3 && a[3].equals("jump");
        cursor.line++;
        return false;
    }

    static boolean commandBoarding(Minecraft mc, String arg) {
        PilotPoseProbe.boarding(mc.player, arg);
        return true;
    }

    static boolean commandLooklocal(Minecraft mc, String arg) {
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

    static boolean commandAim(String arg) {
        track = arg.equals("track");
        if (!track && arg.equals("target")) GroundFlightClient.aimAtTarget();
        return true;
    }

    static boolean commandBreaknear(Minecraft mc, String arg) {
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

    static boolean commandNav(Minecraft mc, String arg) {
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

    static boolean commandBreak(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var at =
                new net.minecraft.core.BlockPos(
                        Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
        mc.gameMode.startDestroyBlock(at, net.minecraft.core.Direction.UP);
        return true;
    }

    static boolean commandUse(Minecraft mc, String arg) {
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
    static boolean commandUsehit(Minecraft mc, String arg) {
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
    static boolean commandLeadsite(Minecraft mc, String arg) {
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
    static boolean commandScroll(Minecraft mc, String arg) {
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

    static boolean commandMouse(Minecraft mc, String arg) {
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

    static boolean commandMouseaim(String arg) {
        var a = arg.split("\\s+");
        mouseAim = a[0].equals("locked") ? SpaceNavigationClient.locked() : Integer.parseInt(a[0]);
        mouseAimLeft = Integer.parseInt(a[1]);
        mouseMiss = a.length > 2 ? Double.parseDouble(a[2]) : Double.NaN;
        mouseArrival = a.length > 3 && a[3].equals("arrival");
        cursor.line++;
        return false;
    }

    static boolean commandShipaim(String arg) {
        var a = arg.split("\\s+");
        shipAim = Integer.parseInt(a[0]);
        shipAimLeft = Integer.parseInt(a[1]);
        shipAimX = a.length > 2 ? Double.parseDouble(a[2]) : 1;
        shipAimY = a.length > 3 ? Double.parseDouble(a[3]) : 1;
        cursor.line++;
        return false;
    }

    static boolean commandNavplanets(String arg) {
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

    static boolean commandLock(Minecraft mc, String arg) {

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

    static boolean commandPress(Minecraft mc, String arg) {
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

    // Simulated's throttle lever set as its client sets it when dragged: the lever
    // looked at (remembered after), 0 to 15.
    static boolean commandThrottle(Minecraft mc, String arg) {
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
    static boolean commandLeverdo(Minecraft mc, String arg) {
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

    // The same, on a block counted from where the player stands.
    static boolean commandUsenear(Minecraft mc, String arg) {
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

    /** A simulated pilot, sampling the HUD geometry once a second; all actuation is ordinary keyboard input. */
    static boolean steer(Minecraft mc) {
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

    static void pilotKeys(Minecraft mc, Set<Integer> next) {
        for (int key : new HashSet<>(steerKeys))
            if (!next.contains(key))
                mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), key, 0, 0, 0);
        for (int key : next)
            if (!steerKeys.contains(key))
                mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), key, 0, 1, 0);
        steerKeys.clear();
        steerKeys.addAll(next);
    }

    /** Inject raw deltas immediately before vanilla sensitivity/inversion, not into our attitude API. */
    static void mouse(Minecraft mc) {
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

    static void mouseFrame(SpacePackets.Frame packet) {
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
}
