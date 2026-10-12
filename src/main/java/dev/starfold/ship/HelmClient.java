package dev.starfold.ship;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.starfold.flight.OrbitFlightControls;
import dev.starfold.space.SpaceAttitudeClient;

import net.minecraft.client.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

import org.joml.Vector3d;

/** A view of the server-authorized hull, never a new observer or a seat. */
public final class HelmClient {
    static final float CHASE_TILT = 8;
    static final double ZOOM_STEP = 1.15, ZOOM_MIN = .4, ZOOM_MAX = 4;
    private static double zoom = 1;

    public static double zoom() {
        return zoom;
    }

    public static int tier() {
        var hull = body();
        return hull == null ? 0 : Thrusters.scopeTier(hull);
    }

    static double zoomed(double factor, double notches) {
        return Double.isFinite(notches)
                ? Math.clamp(factor * Math.pow(ZOOM_STEP, -notches), ZOOM_MIN, ZOOM_MAX)
                : factor;
    }

    private static void scroll(
            net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.screen != null || body() == null || event.getScrollDeltaY() == 0) return;
        if (!scopeView() && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK)
            zoom = zoomed(zoom, event.getScrollDeltaY());
        event.setCanceled(true);
    }

    static final double SCOPE_OUT = .6;

    private record View(boolean first, net.minecraft.core.BlockPos scope) {}

    private static final View CHASE = new View(false, null), FIRST = new View(true, null);
    private static final java.util.Map<java.util.UUID, View> views = new java.util.HashMap<>();
    private static double lookX, lookY;
    private static long viewChanged;

    public static boolean viewFlash() {
        return System.nanoTime() - viewChanged < 1_000_000_000L;
    }

    public static String viewLabel() {
        var body = body();
        if (body == null) return "";
        var all = scopes(body);
        var selected = scope(body);
        if (selected == null)
            return net.minecraft.client.resources.language.I18n.get(
                            view(body).first()
                                    ? "hud.starfold.view_first"
                                    : "hud.starfold.view_chase")
                    + (all.isEmpty()
                            ? " · "
                                    + net.minecraft.client.resources.language.I18n.get(
                                            "hud.starfold.view_none")
                            : "");
        var facing =
                selected.getBlockState()
                        .getValue(
                                net.minecraft.world.level.block.state.properties
                                        .BlockStateProperties.FACING);
        var bow = HelmInputClient.facing(body);
        String direction =
                facing == net.minecraft.core.Direction.UP
                        ? "up"
                        : facing == net.minecraft.core.Direction.DOWN
                                ? "down"
                                : facing == bow
                                        ? "forward"
                                        : facing == bow.getOpposite()
                                                ? "aft"
                                                : facing == bow.getClockWise() ? "right" : "left";
        return net.minecraft.client.resources.language.I18n.get(
                "hud.starfold.view_camera",
                all.indexOf(selected) + 1,
                all.size(),
                net.minecraft.client.resources.language.I18n.get("hud.starfold.view_" + direction));
    }

    private static View view(ClientSubLevel body) {
        return views.getOrDefault(body.getUniqueId(), CHASE);
    }

    private static Vector3d nose(ClientSubLevel body) {
        var d = HelmInputClient.facing(body);
        return new Vector3d(d.getStepX(), 0, d.getStepZ());
    }

    public static java.util.List<Thrusters.Scope> scopes(ClientSubLevel body) {
        var list = new java.util.ArrayList<Thrusters.Scope>();
        for (var holder : body.getPlot().getLoadedChunks())
            for (var entity : holder.getChunk().getBlockEntities().values())
                if (entity instanceof Thrusters.Scope scope && !scope.isRemoved()) list.add(scope);
        var nose = nose(body);
        list.sort(
                java.util.Comparator.<Thrusters.Scope>comparingDouble(
                                s -> {
                                    var p = s.getBlockPos();
                                    return -nose.dot(p.getX(), p.getY(), p.getZ());
                                })
                        .thenComparing(s -> s.getBlockPos()));
        return list;
    }

    private static Thrusters.Scope scope(ClientSubLevel body) {
        var selected = view(body).scope();
        if (selected == null) return null;
        for (var s : scopes(body)) if (s.getBlockPos().equals(selected)) return s;
        return null;
    }

    public static Vector3d forward(ClientSubLevel body) {
        return body.logicalPose().orientation().transform(nose(body));
    }

    public static float pilotYaw() {
        var body = body();
        var forward = forward(body);
        return (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
    }

    public static boolean scopeView() {
        var body = body();
        return body != null && scope(body) != null;
    }

    public static void cycle() {
        tick();
        var body = body();
        if (body == null) return;
        var cycle = new java.util.ArrayList<View>();
        cycle.add(CHASE);
        for (var s : scopes(body)) cycle.add(new View(false, s.getBlockPos().immutable()));
        cycle.add(FIRST);
        var next = cycle.get((cycle.indexOf(view(body)) + 1) % cycle.size());
        views.put(body.getUniqueId(), next);
        viewChanged = System.nanoTime();
        lookX = lookY = 0;
        Minecraft.getInstance()
                .options
                .setCameraType(
                        next.first() ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
    }

    public static String viewName() {
        var body = body();
        if (body == null) return "vanilla";
        var scope = scope(body);
        return scope != null
                ? "scope "
                        + scope.getBlockState()
                                .getValue(
                                        net.minecraft.world.level.block.state.properties
                                                .BlockStateProperties.FACING)
                        + " "
                        + scope.getBlockPos().toShortString()
                : view(body).first() ? "first person" : "chase";
    }

    public static boolean scopeMouse(double x, double y) {
        if (!scopeView() || !OrbitFlightControls.freeLook()) return false;
        lookX += x * .15;
        lookY += y * .15;
        double scale = Math.min(1, 90 / Math.max(1e-12, Math.hypot(lookX, lookY)));
        lookX *= scale;
        lookY *= scale;
        return true;
    }

    private static Vec3 scopeCamera(
            Camera camera, ClientSubLevel body, Thrusters.Scope scope, float partial) {
        var d =
                scope.getBlockState()
                        .getValue(
                                net.minecraft.world.level.block.state.properties
                                        .BlockStateProperties.FACING);
        var facing = new Vector3d(d.getStepX(), d.getStepY(), d.getStepZ());
        var up =
                d.getAxis() == net.minecraft.core.Direction.Axis.Y
                        ? new Vector3d(nose(body))
                        : new Vector3d(0, 1, 0);
        var pose = body.renderPose(partial);
        var p = scope.getBlockPos();
        var at =
                pose.transformPosition(
                        new Vector3d(p.getX() + .5, p.getY() + .5, p.getZ() + .5)
                                .fma(SCOPE_OUT, facing));
        pose.orientation().transform(facing);
        pose.orientation().transform(up);
        var rotation =
                new org.joml.Quaternionf()
                        .lookAlong(
                                new org.joml.Vector3f(
                                        (float) facing.x, (float) facing.y, (float) facing.z),
                                new org.joml.Vector3f((float) up.x, (float) up.y, (float) up.z))
                        .conjugate();
        if (!OrbitFlightControls.freeLook()) lookX = lookY = 0;
        double angle = Math.hypot(lookX, lookY);
        if (angle > 0)
            rotation.rotateAxis(
                    (float) Math.toRadians(angle),
                    (float) (-lookY / angle),
                    (float) (-lookX / angle),
                    0);
        camera.rotation().set(rotation);
        camera.getLookVector().set(0, 0, -1).rotate(rotation);
        camera.getUpVector().set(0, 1, 0).rotate(rotation);
        camera.getLeftVector().set(-1, 0, 0).rotate(rotation);
        return new Vec3(at.x, at.y, at.z);
    }

    private static CameraType previous;
    private static Object player;

    public static void initialize() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> tick());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(HelmClient::scroll);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.RenderHighlightEvent.Block event) -> {
                    if (body() != null) event.setCanceled(true);
                });
    }

    public static ClientSubLevel body() {
        return HelmInputClient.body();
    }

    private static void tick() {
        var mc = Minecraft.getInstance();
        boolean active = body() != null;
        if (previous != null && (!active || player != mc.player)) {
            mc.options.setCameraType(previous);
            previous = null;
            player = null;
            lookX = lookY = 0;
        }
        if (active && previous == null) {
            previous = mc.options.getCameraType();
            player = mc.player;
            var b = body();
            nose(b);
            mc.options.setCameraType(
                    view(b).first() ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
        }
        if (active && view(body()).scope() != null && scope(body()) == null) {
            views.put(body().getUniqueId(), CHASE);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
    }

    public static Vec3 camera(Camera camera, float partial) {
        var mc = Minecraft.getInstance();
        var body = body();
        if (body == null
                || camera.getEntity() != mc.player
                || mc.options.getCameraType().isFirstPerson()) return null;
        var scope = scope(body);
        if (scope != null) return scopeCamera(camera, body, scope, partial);
        var box = body.getPlot().getBoundingBox();
        double x = box.maxX() - box.minX() + 1,
                y = box.maxY() - box.minY() + 1,
                z = box.maxZ() - box.minZ() + 1;
        var pivot =
                body.renderPose(partial)
                        .transformPosition(
                                new Vector3d(
                                        box.minX() + x / 2,
                                        box.minY() + y / 2,
                                        box.minZ() + z / 2));
        var centre = new Vec3(pivot.x, pivot.y, pivot.z);
        double distance =
                Math.clamp(2.5 * Math.sqrt(x * x + y * y + z * z) / 2 + 6, 12, 64)
                        * (mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK
                                ? zoom
                                : 1); // Tilt only the stand-back direction: level sight clears the
                                      // roof, with the hull below centre.
        var back =
                (SpaceAttitudeClient.mouseFlight()
                                ? Vec3.directionFromRotation(CHASE_TILT, camera.getYRot())
                                : new Vec3(camera.getLookVector()))
                        .scale(-1);
        // Sable's vanilla zoom sees glass too. Keep the eight terrain rays but ignore this hull, in
        // its render pose.
        var poses = (LevelPoseProviderExtension) mc.level;
        poses.sable$pushPoseSupplier(b -> ((ClientSubLevel) b).renderPose(partial));
        try {
            for (int i = 0; i < 8; i++) {
                var start =
                        centre.add(
                                ((i & 1) * 2 - 1) * .1,
                                ((i >> 1 & 1) * 2 - 1) * .1,
                                ((i >> 2 & 1) * 2 - 1) * .1);
                var ray =
                        new ClipContext(
                                start,
                                start.add(back.scale(distance)),
                                ClipContext.Block.VISUAL,
                                ClipContext.Fluid.NONE,
                                mc.player);
                ((ClipContextExtension) ray).sable$setSubLevelIgnoring(b -> b == body);
                var hit = mc.level.clip(ray);
                if (hit.getType() != HitResult.Type.MISS)
                    distance =
                            Math.min(
                                    distance,
                                    Math.sqrt(
                                            Sable.HELPER.distanceSquaredWithSubLevels(
                                                    mc.level, hit.getLocation(), centre)));
            }
        } finally {
            poses.sable$popPoseSupplier();
        }
        return centre.add(back.scale(distance));
    }

    private HelmClient() {}
}
