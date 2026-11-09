package dev.starfold.dev;

import static dev.starfold.dev.ProbeInput.*;
import static dev.starfold.dev.ProbeCamera.*;
import static dev.starfold.dev.ProbeSamples.*;

import dev.starfold.chart.GroundSolar;
import dev.starfold.flight.ArrivalState;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.render.GroundClient;
import dev.starfold.ship.Airstair;
import dev.starfold.ship.HelmClient;
import dev.starfold.ship.HullLanding;
import dev.starfold.ship.HullLandingClient;
import dev.starfold.sky.Space;
import dev.starfold.sky.WeatherClient;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceNavigation;
import dev.starfold.space.SpaceNavigationClient;
import dev.starfold.waypoint.WaypointClient;

import net.minecraft.client.Minecraft;

import java.nio.file.*;
import java.util.*;

/** Script dispatch, waiting and termination. */
final class ProbeSession {
    static boolean finishing;

    static int untilLeft;

    static double untilValue;

    static String until;

    static org.joml.Vector3d lastHull;

    static net.minecraft.world.phys.Vec3 walkStart;

    static String motionGuard;

    static int guardLeft, stable;

    static String receipt, checkLabel;

    static final ProbeScript cursor = new ProbeScript();

    static boolean awaitReceipt(Minecraft mc) {
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

    static boolean awaitMotion(Minecraft mc) {
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

    static boolean awaitCondition(Minecraft mc) {
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
            if (cursor.strict && !done) {
                fail(mc, "until timeout");
                return true;
            }
        }
        return false;
    }

    static boolean conditionMet(
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
            case "ready" -> GroundClient.ready() && GroundClient.planet() == (int) untilValue;
            case "ground" -> !GroundFlightClient.controlled() && mc.player.onGround();
            case "free" -> !GroundFlightClient.controlled();
            case "controlled" -> GroundFlightClient.controlled();
            case "planet!=" -> GroundFlightClient.planet() != (int) untilValue;
            default -> true;
        };
    }

    static void runScript(Minecraft mc) {
        if (DevAtlasProbe.tick(mc)) return;
        cursor.tick(
                (command, arg) -> !DevAtlasProbe.busy() && command(mc, command, arg),
                ProbeSession::expand,
                reason -> fail(mc, reason));
    }

    /** Returns whether the script loop may advance; false preserves the command's tick boundary. */
    static boolean command(Minecraft mc, String command, String arg) {
        return switch (command) {
            case "", "#" -> commandComment();
            case "wait" -> commandWait(arg);
            case "port" -> commandPort(arg);
            case "waitworld" -> commandWaitworld(mc);
            case "p3sound" -> DevPlanetSoundProbe.command(mc, arg);
            case "atlas" -> DevAtlasProbe.command(mc, arg);
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
            case "pack" -> commandPack(mc, arg);
            case "key" -> commandKey(arg);
            case "createworld" -> commandCreateworld(mc, arg);
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
            case "fov" -> {
                mc.options.fov().set(Integer.parseInt(arg));
                yield true;
            }
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
            case "flightbench" -> { DevFlightPerfProbe.begin(mc, arg); cursor.line++; yield false; }
            case "frames" -> commandFrames(arg);
            case "far" -> commandFar(arg);
            case "darkscan" -> commandDarkscan(mc, arg);
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
            case "pause" -> commandPause(mc, arg);
            case "scope" -> commandScope(mc, arg);
            case "quit" -> commandQuit(mc);
            default -> commandUnknown();
        };
    }

    static boolean commandComment() {
        return true;
    }

    static boolean commandWait(String arg) {
        cursor.wait = Integer.parseInt(arg);
        cursor.line++;
        return false;
    }

    static boolean commandPort(String arg) {
        DevPortProbe.command(arg);
        return true;
    }

    static boolean commandWaitworld(Minecraft mc) {
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

    static boolean commandStrict() {
        cursor.strict = true;
        return true;
    }

    static boolean commandCheck(Minecraft mc, String arg) {
        var a = arg.split("\\s+", 2);
        checkLabel = a[0];
        receipt = "CP_CHECK_" + UUID.randomUUID();
        guardLeft = 200;
        mc.player.connection.sendCommand("execute " + a[1] + " run tellraw @s \"" + receipt + "\"");
        cursor.line++;
        return false;
    }

    static boolean commandLandguard(String arg) {
        motionGuard = "land";
        guardLeft = Integer.parseInt(arg);
        stable = 0;
        lastHull = null;
        cursor.line++;
        return false;
    }

    static boolean commandWalk20(Minecraft mc, String arg) {
        motionGuard = "walk";
        guardLeft = Integer.parseInt(arg);
        walkStart = mc.player.position();
        keys.put("forward", true);
        cursor.line++;
        return false;
    }

    static boolean commandSolarplan(Minecraft mc, String arg) {
        return commandSolarplan(mc, arg, "solarplan");
    }

    static boolean commandSolarjump(Minecraft mc, String arg) {
        return commandSolarplan(mc, arg, "solarjump");
    }

    static boolean commandSolarplan(Minecraft mc, String arg, String command) {
        if (!GroundClient.ready()) {
            fail(mc, "solar observer not ready");
            return false;
        }
        var model = GroundClient.ephemeris();
        var shape = GroundClient.shape();
        var eye = GroundClient.eye();
        var direction =
                shape.direction(
                        CubeSurfaceTopology.Face.VALUES[GroundClient.face()], eye.x(), eye.z());
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
            cursor.wait = 40;
        } else {
            until = "sky>";
            untilValue = chosen;
            untilLeft = 12000;
        }
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE SOLARPLAN {} now {} target {} elevation {}", arg, now, chosen, best);
        cursor.line++;
        return false;
    }

    static boolean commandGuide(Minecraft mc, String arg) {
        yaw = null;
        pitch = null;
        DevGuideProbe.command(mc, arg);
        cursor.line++;
        return false;
    }

    static boolean commandInput(Minecraft mc, String arg) {

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

    static boolean commandSolar(Minecraft mc, String arg) {
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

    /** Existing chat readback fences both server work and its preceding client packets. */
    static boolean commandPack(Minecraft mc, String arg) {
        if (!cursor.strict || !Boolean.getBoolean("starfold.packProbe"))
            throw new IllegalStateException("pack requires strict mode and starfold.packProbe");
        var kind = arg.split(" ", 2)[0];
        if (!java.util.Set.of("at", "orbit", "survey", "colorwater", "colortorch").contains(kind))
            throw new IllegalArgumentException("Unsupported fenced pack command");
        checkLabel = "pack line " + cursor.line + " " + arg;
        receipt = "CP_PACK_" + cursor.line + "_" + UUID.randomUUID();
        guardLeft = 6000;
        mc.player.connection.sendCommand("packprobe " + arg);
        mc.player.connection.sendCommand("tellraw @s \"" + receipt + "\"");
        cursor.line++;
        return false;
    }

    static boolean commandCmd(Minecraft mc, String arg) {
        mc.player.connection.sendCommand(arg);
        if (cursor.strict) {
            cursor.line++;
            cursor.wait = 2;
            return false;
        }
        return true;
    }

    static boolean commandPause(Minecraft mc, String arg) {
        if (arg.equals("on")) mc.pauseGame(false);
        else mc.setScreen(null);
        return true;
    }

    // Holds the main-hand item in use (a spyglass zooms) until "scope off".
    static boolean commandScope(Minecraft mc, String arg) {
        if (arg.equals("on")) mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
        else mc.gameMode.releaseUsingItem(mc.player);
        return true;
    }

    /** Opens a fresh world screen, selects a preset by id, or creates it with commands allowed ("go"). */
    static boolean commandCreateworld(Minecraft mc, String arg) {
        if (mc.screen instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen screen) {
            if (arg.contains(":")) {
                var presets = screen.getUiState().getSettings().worldgenRegistries()
                        .compositeAccess().registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET);
                screen.getUiState().setWorldType(
                        new net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry(
                                presets.getHolderOrThrow(net.minecraft.resources.ResourceKey.create(
                                        net.minecraft.core.registries.Registries.WORLD_PRESET,
                                        net.minecraft.resources.ResourceLocation.parse(arg)))));
            }
            org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE CREATEWORLD preset {}",
                    screen.getUiState().getWorldType().preset().unwrapKey().orElseThrow().location());
            if (arg.startsWith("seed ")) screen.getUiState().setSeed(arg.substring(5));
            if (arg.startsWith("preset "))
                screen.getUiState().setWorldType(screen.getUiState().getNormalPresetList().stream()
                        .filter(entry -> entry.preset() != null && entry.preset().is(net.minecraft.resources.ResourceLocation.parse(arg.substring(7))))
                        .findFirst().orElseThrow());
            if (arg.equals("go")) screen.getUiState().setAllowCommands(true);
            if (arg.equals("go"))
                screen.children().stream()
                        .filter(child -> child instanceof net.minecraft.client.gui.components.Button button
                                && button.getMessage().equals(net.minecraft.network.chat.Component.translatable("selectWorld.create")))
                        .findFirst()
                        .ifPresent(child -> ((net.minecraft.client.gui.components.Button) child).onPress());
        } else net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.openFresh(mc, null);
        return true;
    }

    static boolean commandRequirehelm(Minecraft mc) {
        boolean active = HelmClient.body() != null;
        org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE requirehelm {}", active);
        if (!active) {
            fail(mc, "controller pilot missing");
            return false;
        }
        return true;
    }

    static boolean commandRequirenohelm(Minecraft mc) {
        if (HelmClient.body() != null) {
            fail(mc, "unexpected controller pilot");
            return false;
        }
        return true;
    }

    static boolean commandRequireref(String arg) {
        if (!SpaceClient.active() || SpaceClient.frame().planet() != Integer.parseInt(arg)) {
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE WRONG REF expected {}", arg);
            cursor.line = cursor.lines.size() - 2;
        }
        return true;
    }

    static boolean commandRequireplanet(String arg) {
        if (SpaceClient.present() || GroundClient.frame().planet() != Integer.parseInt(arg)) {
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE WRONG PLANET expected {}", arg);
            cursor.line = cursor.lines.size() - 2;
        }
        return true;
    }

    static boolean commandRequirechart() {
        if (SpaceClient.present()) {
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE NOT IN CHART: skip arrival/landing");
            cursor.line = cursor.lines.size() - 2;
        }
        return true;
    }

    static boolean commandAboardelsequit(Minecraft mc) {
        if (dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(mc.player) == null) {
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE NOT ABOARD: skip descent/landing");
            cursor.line = cursor.lines.size() - 2;
        }
        return true;
    }

    static boolean commandRequirepad(Minecraft mc) {
        if (HullLandingClient.site().kind() != HullLanding.Kind.PAD
                || !HullLandingClient.site().deployable()) {
            fail(mc, "pad/deployable required: " + HullLandingClient.site());
            return false;
        }
        return true;
    }

    static boolean commandScopetier(Minecraft mc, String arg) {
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

    static boolean commandRemovescopes(Minecraft mc) {
        var b = HelmClient.body();
        if (b != null)
            for (var s : HelmClient.scopes(b))
                mc.player.connection.sendCommand(
                        "setblock "
                                + s.getBlockPos().toShortString().replace(",", "")
                                + " minecraft:glass");
        return true;
    }

    static boolean commandFeeltest(Minecraft mc, String arg) {
        DevFeelProbe.start(mc, arg);
        cursor.line++;
        return false;
    }

    static boolean commandPronefps(Minecraft mc, String arg) {
        mc.options.framerateLimit().set(Integer.parseInt(arg));
        return true;
    }

    static boolean commandLanguage(Minecraft mc, String arg) {
        mc.getLanguageManager().setSelected(arg);
        mc.options.languageCode = arg;
        mc.reloadResourcePacks();
        cursor.wait = 100;
        cursor.line++;
        return false;
    }

    static boolean commandFeelclick(Minecraft mc, String arg) {
        var a = arg.split("\\s+");
        var key = a[0].equals("attack") ? mc.options.keyAttack : mc.options.keyUse;
        net.minecraft.client.KeyMapping.click(key.getKey());
        keys.put(a[0], a[1].equals("on"));
        return true;
    }

    static boolean commandHelmview(String arg) {

        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE VIEW {} {}",
                        arg,
                        HelmClient.viewName() + " label " + HelmClient.viewLabel());
        return true;
    }

    static boolean commandUntil(String arg) {
        var a = arg.split("\\s+");
        until = a[0];
        untilValue = a.length > 2 ? Double.parseDouble(a[1]) : 0;
        untilLeft = Integer.parseInt(a[a.length - 1]);
        cursor.line++;
        return false;
    }

    static boolean commandWeather(Minecraft mc, String arg) {

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

    static boolean commandAsleep(Minecraft mc, String arg) {

        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE asleep {} | {} timer {}",
                        arg,
                        mc.player.isSleeping(),
                        mc.player.getSleepTimer());
        return true;
    }

    static boolean commandExit(Minecraft mc) {
        if (DevPlanetSoundProbe.enabled()) DevPlanetSoundProbe.stop(mc);
        release(mc);
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE COMPLETE | line {} of {}", cursor.line + 1, cursor.lines.size());
        mc.stop();
        cursor.line = cursor.lines.size();
        return false;
    }

    static boolean commandQuit(Minecraft mc) {
        if (DevPlanetSoundProbe.enabled()) DevPlanetSoundProbe.stop(mc);
        // Client ticks continue when the server is stuck. Require a final server round
        // trip in verify.py.
        if (!finishing) {
            mc.player.connection.sendCommand("say CP_PROBE_SERVER_ACK");
            finishing = true;
            cursor.wait = 60;
            return false;
        }
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE COMPLETE | line {} of {}", cursor.line + 1, cursor.lines.size());
        release(mc);
        mc.stop();
        cursor.line = cursor.lines.size();
        return false;
    }

    static boolean commandUnknown() {
        throw new IllegalArgumentException("Probe line " + cursor.lines.get(cursor.line));
    }

    static String expand(String arg) {
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

    static void fail(Minecraft mc, String reason) {
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .error("PROBE FAIL CLOSED {} scriptLine {}", reason, cursor.line + 1);
        release(mc);
        receipt = null;
        motionGuard = null;
        until = null;
        steerLeft = 0;
        sampleEvery = 0;
        mc.stop();
        cursor.line = cursor.lines.size();
    }
}
