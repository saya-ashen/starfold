package dev.starfold.devtools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import dev.starfold.api.FarViewDiagnostics;
import dev.starfold.render.GroundClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Local file mailbox; game objects are inspected exclusively on the client/render thread. */
final class LiveBridge {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final AtomicReference<CompletableFuture<JsonObject>> PENDING =
            new AtomicReference<>();
    private static final AtomicReference<Runnable> CLIENT_TASK = new AtomicReference<>();
    private static volatile LiveMailbox mailbox;
    private static boolean attempted;

    static void register() {
        NeoForge.EVENT_BUS.addListener(
                (ClientTickEvent.Post event) -> {
                    if (!attempted) {
                        attempted = true;
                        if (!Boolean.getBoolean("starfold.devtools.live.disabled")) start();
                    }
                    var task = CLIENT_TASK.getAndSet(null);
                    if (task != null) task.run();
                });
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> stop());
        NeoForge.EVENT_BUS.addListener(
                (RegisterClientCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        Commands.literal("sflive")
                                                .executes(
                                                        context ->
                                                                message(
                                                                        mailbox == null
                                                                                ? "Stopped"
                                                                                : "Ready: "
                                                                                        + directory()))
                                                .then(
                                                        Commands.literal("start")
                                                                .executes(
                                                                        context -> {
                                                                            attempted = true;
                                                                            start();
                                                                            return 1;
                                                                        }))
                                                .then(
                                                        Commands.literal("stop")
                                                                .executes(
                                                                        context -> {
                                                                            attempted = true;
                                                                            stop();
                                                                            return message(
                                                                                    "Stopped");
                                                                        }))));
    }

    private static java.nio.file.Path directory() {
        return Minecraft.getInstance()
                .gameDirectory
                .toPath()
                .toAbsolutePath()
                .resolve("starfold-devtools/live");
    }

    private static void start() {
        if (mailbox != null) return;
        try {
            var next =
                    new LiveMailbox(
                            Minecraft.getInstance().gameDirectory.toPath(), LiveBridge::dispatch);
            mailbox = next;
            try {
                next.start();
            } catch (Exception exception) {
                mailbox = null;
                next.close();
                throw exception;
            }
            Devtools.LOGGER.info("[sflive] Local inspection ready: {}", directory());
        } catch (Exception exception) {
            Devtools.LOGGER.error("[sflive] Cannot start local inspection", exception);
        }
    }

    private static void stop() {
        var old = mailbox;
        mailbox = null;
        if (old != null) old.close();
    }

    private static CompletableFuture<JsonObject> dispatch(JsonObject request) {
        var result = new CompletableFuture<JsonObject>();
        if (!PENDING.compareAndSet(null, result)) {
            result.completeExceptionally(
                    new IllegalStateException("Client is still processing a previous request"));
            return result;
        }
        var owner = mailbox;
        CLIENT_TASK.set(
                () -> {
                    boolean asynchronous = false;
                    try {
                        if (owner == null
                                || mailbox != owner
                                || result.isDone()
                                || System.currentTimeMillis()
                                        >= request.get("expiresAt").getAsLong())
                            throw new IllegalStateException(
                                    "Request expired or bridge stopped before client"
                                            + " execution");
                        String operation = request.get("operation").getAsString();
                        if (operation.equals("screenshot")) {
                            screenshot(request, result);
                            asynchronous = true;
                            return;
                        }
                        result.complete(inspect(operation, request));
                    } catch (Exception exception) {
                        result.completeExceptionally(exception);
                    } finally {
                        if (!asynchronous) PENDING.compareAndSet(result, null);
                    }
                });
        return result;
    }

    private static JsonObject inspect(String operation, JsonObject request) {
        var mc = Minecraft.getInstance();
        JsonObject out = new JsonObject();
        out.addProperty("capturedAt", Instant.now().toString());
        switch (operation) {
            case "status", "snapshot" -> {
                out.addProperty("inWorld", mc.level != null);
                out.addProperty("paused", mc.isPaused());
                out.addProperty("fps", mc.getFps());
                out.addProperty(
                        "screen", mc.screen == null ? null : mc.screen.getClass().getName());
                out.addProperty("integratedServer", mc.getSingleplayerServer() != null);
                out.addProperty("source", "CLIENT_ONLY; not authoritative server state");
                out.add(
                        "operations",
                        GSON.toJsonTree(
                                List.of(
                                        "status",
                                        "snapshot",
                                        "far_watch",
                                        "far_snapshot",
                                        "far_stop",
                                        "screenshot",
                                        "profile_start",
                                        "profile_stop")));
                out.addProperty("farWatchActive", FarViewDiagnostics.active());
                if (mc.level != null) {
                    out.addProperty("dimension", mc.level.dimension().location().toString());
                    out.addProperty("gameTime", mc.level.getGameTime());
                }
                if (mc.player != null) {
                    var player = new JsonObject();
                    player.add(
                            "position", xyz(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
                    var v = mc.player.getDeltaMovement();
                    player.add("velocity", xyz(v.x, v.y, v.z));
                    player.addProperty("yaw", mc.player.getYRot());
                    player.addProperty("pitch", mc.player.getXRot());
                    player.addProperty("onGround", mc.player.onGround());
                    player.addProperty("health", mc.player.getHealth());
                    player.addProperty(
                            "vehicle",
                            mc.player.getVehicle() == null
                                    ? null
                                    : BuiltInRegistries.ENTITY_TYPE
                                            .getKey(mc.player.getVehicle().getType())
                                            .toString());
                    out.add("player", player);
                }
                var camera = mc.gameRenderer.getMainCamera();
                var p = camera.getPosition();
                var view = new JsonObject();
                view.add("position", xyz(p.x, p.y, p.z));
                view.addProperty("yaw", camera.getYRot());
                view.addProperty("pitch", camera.getXRot());
                out.add("camera", view);
                var ground = new JsonObject();
                ground.addProperty("ready", mc.level != null && GroundClient.ready());
                if (mc.level != null && GroundClient.ready()) {
                    var eye = GroundClient.eye();
                    ground.addProperty("planet", GroundClient.planet());
                    ground.addProperty("face", GroundClient.face());
                    ground.add("chartEye", xyz(eye.x(), eye.y(), eye.z()));
                }
                out.add("ground", ground);
                if (operation.equals("snapshot")) {
                    out.add("target", target());
                    out.add("far", GSON.toJsonTree(FarViewDiagnostics.snapshot()));
                }
            }
            case "far_watch" -> {
                int radius = request.has("radius") ? request.get("radius").getAsInt() : 0;
                if (radius < 0 || radius > 2)
                    throw new IllegalArgumentException("radius must be 0..2");
                boolean explicit = request.has("face") || request.has("x") || request.has("z");
                if (explicit && !(request.has("face") && request.has("x") && request.has("z")))
                    throw new IllegalArgumentException("face, x and z must be supplied together");
                int started =
                        explicit
                                ? FarCommands.watch(
                                        request.get("face").getAsInt(),
                                        request.get("x").getAsInt(),
                                        request.get("z").getAsInt(),
                                        radius)
                                : FarCommands.here(radius);
                if (started == 0)
                    throw new IllegalStateException(
                            "Watch not started: observer unavailable or a watch is already active");
                out.add("watch", GSON.toJsonTree(FarViewDiagnostics.snapshot().watch()));
            }
            case "far_snapshot" -> out.add("report", GSON.toJsonTree(FarCommands.liveSnapshot()));
            case "far_stop" -> {
                FarCommands.liveStop();
                out.addProperty("stopped", true);
            }
            case "profile_start" -> {
                Profiler.start();
                out.addProperty(
                        "status", "requested; see profiler chat/log for frame-boundary activation");
            }
            case "profile_stop" -> {
                Profiler.stop();
                out.addProperty("status", "requested; export occurs at frame boundary");
            }
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        }
        return out;
    }

    private static JsonObject target() {
        var mc = Minecraft.getInstance();
        var out = new JsonObject();
        if (mc.hitResult == null || mc.level == null) {
            out.addProperty("status", "UNAVAILABLE");
            return out;
        }
        out.addProperty("type", mc.hitResult.getType().name());
        var hit = mc.hitResult.getLocation();
        out.add("hit", xyz(hit.x, hit.y, hit.z));
        if (mc.hitResult instanceof BlockHitResult block
                && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            var pos = block.getBlockPos();
            out.add("position", xyz(pos.getX(), pos.getY(), pos.getZ()));
            out.addProperty("side", block.getDirection().getName());
            var chunk = mc.level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            out.addProperty("status", chunk == null ? "UNLOADED" : "LOADED_CLIENT");
            if (chunk != null) {
                var state = chunk.getBlockState(pos);
                out.addProperty(
                        "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                out.addProperty("state", state.toString());
                out.addProperty("fluid", state.getFluidState().toString());
            }
        } else if (mc.hitResult instanceof EntityHitResult entity) {
            out.addProperty(
                    "entityType",
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getEntity().getType()).toString());
            out.addProperty("entityId", entity.getEntity().getId());
        }
        return out;
    }

    private static void screenshot(JsonObject request, CompletableFuture<JsonObject> result) {
        var mc = Minecraft.getInstance();
        var target = mc.getMainRenderTarget();
        if ((long) target.width * target.height > 16_777_216L)
            throw new IllegalStateException("Screenshot exceeds 16 megapixel bound");
        var context = inspect("status", request);
        var image = Screenshot.takeScreenshot(target);
        var path =
                mc.gameDirectory
                        .toPath()
                        .toAbsolutePath()
                        .resolve(
                                "screenshots/starfold-live-"
                                        + java.util.UUID.randomUUID()
                                        + ".png");
        CompletableFuture.runAsync(
                () -> {
                    try (image) {
                        Files.createDirectories(path.getParent());
                        image.writeToFile(path);
                        context.addProperty("path", path.toString());
                        context.addProperty(
                                "imageTiming",
                                "Last completed framebuffer; state sampled at request execution,"
                                        + " not an atomic render snapshot");
                        result.complete(context);
                    } catch (Exception exception) {
                        result.completeExceptionally(exception);
                    } finally {
                        PENDING.compareAndSet(result, null);
                    }
                });
    }

    private static JsonObject xyz(double x, double y, double z) {
        var out = new JsonObject();
        out.addProperty("x", x);
        out.addProperty("y", y);
        out.addProperty("z", z);
        return out;
    }

    private static int message(String text) {
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[sflive] " + text));
        return 1;
    }

    private LiveBridge() {}
}
