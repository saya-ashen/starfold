package dev.starfold.dev;

import static dev.starfold.dev.ProbeSession.*;
import static dev.starfold.dev.ProbeInput.*;
import static dev.starfold.dev.ProbeCamera.*;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.ground.GroundSeams;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.render.GroundClient;
import dev.starfold.render.GroundRenderer;
import dev.starfold.render.PlanetRemoteRenderer;
import dev.starfold.ship.HelmClient;
import dev.starfold.ship.HelmInputClient;
import dev.starfold.ship.HullLandingClient;
import dev.starfold.ship.ThrusterSoundClient;
import dev.starfold.space.SpaceAttitudeClient;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceDustClient;
import dev.starfold.space.SpaceNavigationClient;

import net.minecraft.client.Minecraft;

import java.nio.file.*;
import java.util.*;

/** Frame, corner, cargo and trace observations. */
final class ProbeSamples {
    static String frameLabel;

    static long frameLast;

    static int frameCount;

    static long[] frames = new long[0];

    static int cargoEvery, cargoCountdown;

    static int cornerEvery, cornerCountdown;

    static boolean tracing;

    static int sampleEvery, sampleCountdown, sampleNumber;

    static void updateSampling(Minecraft mc) {
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

    static boolean commandRecord(Minecraft mc, String arg) {
        return commandRecord(mc, arg, "record");
    }

    static boolean commandRecordpos(Minecraft mc, String arg) {
        return commandRecord(mc, arg, "recordpos");
    }

    static boolean commandRecord(Minecraft mc, String arg, String command) {
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

    static boolean commandRecordhit(Minecraft mc, String arg) {
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) {
            var at = hit.getBlockPos();
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info("PROBE RECORDHIT {} {} state {}", arg, at, mc.level.getBlockState(at));
        }
        return true;
    }

    static boolean commandConsole(String arg) {
        var path = Path.of(arg);
        if (!Files.exists(path)) return false;
        try {
            var commands = Files.readAllLines(path);
            Files.delete(path);
            var updated = new ArrayList<>(cursor.lines);
            updated.addAll(cursor.line, commands);
            cursor.lines = updated;
            cursor.line--;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return true;
    }

    static boolean commandEntitybench(Minecraft mc, String arg) {
        checkLabel = "entitybench " + arg;
        receipt = "PROBE ENTITY_BENCH " + arg + " ticks 600";
        guardLeft = 12000;
        mc.player.connection.sendCommand("entitybench measure " + arg);
        cursor.line++;
        return false;
    }

    static boolean commandHelmstate(Minecraft mc, String arg) {
        DevHelmProbe.state(mc.player, arg);
        mc.player.connection.sendCommand("helmstate " + arg);
        return true;
    }

    static boolean commandLoopstate(Minecraft mc, String arg) {
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

    static boolean commandSurfacehere(Minecraft mc) {
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

    static boolean commandCargowatch(String arg) {
        cargoEvery = Integer.parseInt(arg);
        cargoCountdown = 1;
        return true;
    }

    static boolean commandCargo(Minecraft mc, String arg) {
        cargo(mc, arg, true);
        return true;
    }

    static boolean commandCornerwatch(String arg) {
        cornerEvery = Integer.parseInt(arg);
        cornerCountdown = 1;
        return true;
    }

    static boolean commandCorner(Minecraft mc, String arg) {
        corner(mc, arg);
        return true;
    }

    static boolean commandTrace(String arg) {
        tracing = arg.equals("on");
        return true;
    }

    static boolean commandSample(String arg) {
        sampleEvery = Integer.parseInt(arg);
        sampleCountdown = 1;
        return true;
    }

    static boolean commandHeadingread(String arg) {
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

    /** "frames LABEL N" samples the next N frame intervals and logs them once full; the script runs on. */
    static boolean commandFrames(String arg) {
        var a = arg.split("\\s+");
        frameLabel = a[0];
        frames = new long[Integer.parseInt(a[1])];
        frameCount = 0;
        frameLast = 0;
        GroundRenderer.probeTiming = true;
        GroundRenderer.probeCpuNanos = GroundRenderer.probeGpuNanos = 0;
        GroundRenderer.probeMultiNanos = GroundRenderer.probeSkyNanos = GroundRenderer.probePrepNanos = 0;
        GroundRenderer.probeDraws = GroundRenderer.probeTiles = GroundRenderer.probeSubDraws = 0;
        GroundRenderer.probeGroups = GroundRenderer.probeCompactGroups = 0;
        return true;
    }

    static void frame() {
        DevFlightPerfProbe.frame();
        if (frameCount == frames.length) return;
        long now = System.nanoTime();
        if (frameLast != 0) frames[frameCount++] = now - frameLast;
        frameLast = now;
        if (frameCount < frames.length) return;
        var sorted = frames.clone();
        Arrays.sort(sorted);
        int n = sorted.length, low = Math.max(1, n / 100);
        double total = Arrays.stream(sorted).sum() / 1e6, slowest = 0;
        for (int i = n - low; i < n; i++) slowest += sorted[i] / 1e6;
        int spikes = 0;
        for (long f : sorted) if (f > 50_000_000L) spikes++;
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        String.format(
                                Locale.ROOT,
                                "PROBE FRAMES %s n %d avg_fps %.1f p50_ms %.2f p99_ms %.2f low1_fps %.1f max_ms %.1f over50ms %d far %s | per frame: far_cpu_ms %.2f far_gpu_ms %.2f draws %d tiles_visited %d compact_groups %d groups %d multidraw_ms %.2f subdraws %d sky_ms %.2f prep_ms %.2f",
                                frameLabel,
                                n,
                                n * 1000 / total,
                                sorted[n / 2] / 1e6,
                                sorted[n * 99 / 100] / 1e6,
                                low * 1000 / slowest,
                                sorted[n - 1] / 1e6,
                                spikes,
                                GroundRenderer.probeOff ? "off" : "on",
                                GroundRenderer.probeCpuNanos / 1e6 / n,
                                GroundRenderer.probeGpuNanos / 1e6 / n,
                                GroundRenderer.probeDraws / n,
                                GroundRenderer.probeTiles / n,
                                GroundRenderer.probeCompactGroups / n,
                                GroundRenderer.probeGroups / n,
                                GroundRenderer.probeMultiNanos / 1e6 / n,
                                GroundRenderer.probeSubDraws / n,
                                GroundRenderer.probeSkyNanos / 1e6 / n,
                                GroundRenderer.probePrepNanos / 1e6 / n));
        GroundRenderer.probeTiming = false;
    }

    static boolean commandFar(String arg) {
        GroundRenderer.probeOff = arg.equals("off");
        if (GroundRenderer.probeOff) GroundRenderer.clear();
        return true;
    }

    /** "darkscan R": loaded client chunks within R chunks whose surface has little sky light. */
    static boolean commandDarkscan(Minecraft mc, String arg) {
        int r = Integer.parseInt(arg.trim()), cx = mc.player.blockPosition().getX() >> 4,
                cz = mc.player.blockPosition().getZ() >> 4, loaded = 0;
        var dark = new StringBuilder();
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++) {
                if (!mc.level.getChunkSource().hasChunk(x, z)) continue;
                loaded++;
                int bx = x * 16 + 8, bz = z * 16 + 8;
                int y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, bx, bz);
                int sky = mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, new net.minecraft.core.BlockPos(bx, y, bz));
                if (sky < 8) dark.append(' ').append(x - cx).append(',').append(z - cz).append(':').append(sky).append('@').append(y);
            }
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE DARKSCAN loaded {} dark{}", loaded, dark);
        return true;
    }

    static boolean commandLog(Minecraft mc, String arg) {
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
        if(GroundClient.ready()) org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE {} | {}",arg,GroundRenderer.memory());
        return true;
    }

    static boolean commandScandown(Minecraft mc, String arg) {
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

    static boolean commandHullblocks(Minecraft mc, String arg) {
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

    static boolean commandBlockat(Minecraft mc, String arg) {
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

    static boolean commandSystem(Minecraft mc, String arg) {
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

    static boolean commandLight(Minecraft mc, String arg) {
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

    static boolean commandMilestone(Minecraft mc, String arg) {
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
    static boolean commandFind(Minecraft mc, String arg) {
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

    static boolean commandHit(Minecraft mc, String arg) {

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

    static boolean commandEntities(Minecraft mc) {
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

    static boolean commandEffects(Minecraft mc, String arg) {

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

    static boolean commandSurface(Minecraft mc, String arg) {
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
    static boolean commandCensus(Minecraft mc, String arg) {
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

    static String space(net.minecraft.world.phys.Vec3 position) {
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
    static void sample(Minecraft mc, String label) {
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

    static void trace(Minecraft mc) {
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
    static void rejoin(Minecraft mc) {
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
    static void corner(Minecraft mc, String label) {
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
    static void cargo(Minecraft mc, String label, boolean server) {
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
}
