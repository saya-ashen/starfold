package dev.starfold.space;

import com.mojang.blaze3d.platform.InputConstants;

import dev.starfold.dev.DevProbe;
import dev.starfold.flight.OrbitFlightControls;
import dev.starfold.ship.HelmClient;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import org.lwjgl.glfw.GLFW;

/** Held arrow wishes and mouse aim; the nose consumes the pointer's remaining angle. */
public final class SpaceAttitudeClient {
    private static KeyMapping up, down, left, right;
    private static boolean sent;
    private static double mouseYaw, mousePitch;
    private static Object mouseBody;
    private static Object lookBody;
    private static boolean looking;
    private static float lookYaw;
    static final double AIM = 45, CHASE = .16, AIM_TURN = 120, EPSILON = .02;

    public static record Pointer(double x, double y) {
        Pointer move(double dx, double dy) {
            double nx = x + dx,
                    ny = y + dy,
                    scale = Math.min(1, AIM / Math.max(1e-300, Math.hypot(nx, ny)));
            return new Pointer(nx * scale, ny * scale);
        }

        // Close the final sub-epsilon gap once instead of leaving an asymptotic residual.
        Pointer step() {
            double length = Math.hypot(x, y);
            if (length == 0) return this;
            double angle = Math.min(AIM_TURN / 20, length * CHASE);
            if (length <= EPSILON) angle = length;
            return new Pointer(x * angle / length, y * angle / length);
        }

        Pointer remaining(Pointer turn) {
            return new Pointer(x - turn.x, y - turn.y);
        }
    }

    // Freeze the coming subtraction at the tick boundary; frame mouse deltas remain immediate.
    private static Pointer pointer = new Pointer(0, 0), plannedStep = new Pointer(0, 0);

    static Pointer draw(Pointer offset, Pointer step, double partial) {
        return offset.x() == 0 && offset.y() == 0
                ? offset
                : new Pointer(offset.x() - step.x() * partial, offset.y() - step.y() * partial);
    }

    static Pointer drawnPointer(float partial) {
        var drawn = draw(pointer, plannedStep, partial);
        if (Boolean.getBoolean("starfold.carryProbe") && DevProbe.active())
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE AIM FRAME wall {} partial {} raw {} {} drawn {} {}",
                            System.nanoTime(),
                            partial,
                            pointer.x(),
                            pointer.y(),
                            drawn.x(),
                            drawn.y());
        return drawn;
    }

    private static void recenter() {
        pointer = new Pointer(0, 0);
        plannedStep = new Pointer(0, 0);
        mouseYaw = mousePitch = 0;
        mouseBody = null;
    }

    public static Pointer pointer() {
        return pointer;
    }

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        up = key("pitch_up", GLFW.GLFW_KEY_UP);
        down = key("pitch_down", GLFW.GLFW_KEY_DOWN);
        left = key("roll_left", GLFW.GLFW_KEY_LEFT);
        right = key("roll_right", GLFW.GLFW_KEY_RIGHT);
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) -> {
                    for (var key : new KeyMapping[] {up, down, left, right}) event.register(key);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> tick());
    }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping(
                "key.starfold." + name,
                InputConstants.Type.KEYSYM,
                code,
                "key.categories.starfold");
    }

    public static boolean mouseFlight() {
        var mc = Minecraft.getInstance();
        if (!SpaceClient.active()
                || HelmClient.body() == null
                || mc.screen != null
                || !(mc.isWindowActive() || DevProbe.active())
                || mc.options.getCameraType() == net.minecraft.client.CameraType.THIRD_PERSON_FRONT
                || OrbitFlightControls.freeLook()) return false;
        return !SpaceClient.arriving();
    }

    /** Arguments have already passed vanilla sensitivity, smoothing and invert-Y; Entity.turn uses 0.15 degrees. */
    public static boolean mouse(double x, double y) {
        if (HelmClient.scopeMouse(x, y)) {
            recenter();
            return true;
        }
        if (!prepare()) return false;
        pointer = pointer.move(x * .15, -y * .15);
        return true;
    }

    private static boolean prepare() {
        var mc = Minecraft.getInstance();
        var body = HelmClient.body();
        if (body != lookBody || !SpaceClient.active()) {
            looking = false;
            lookBody = body;
        }
        if (body != null && SpaceClient.active() && OrbitFlightControls.freeLook()) {
            if (!looking) {
                lookYaw = mc.player.getYRot();
                looking = true;
            }
        } else if (looking) {
            mc.player.setYRot(lookYaw);
            mc.player.yRotO = lookYaw;
            looking = false;
        }
        if (!mouseFlight()) {
            recenter();
            return false;
        }
        if (mouseBody != body) {
            recenter();
            mouseBody = body;
        }
        {
            float yaw = HelmClient.pilotYaw();
            mc.player.setYRot(yaw);
            mc.player.yRotO = yaw;
        }
        mc.player.setXRot(0);
        mc.player.xRotO = 0;
        return true;
    }

    private static void tick() {
        var mc = Minecraft.getInstance();
        if (!SpaceClient.active() || HelmClient.body() == null) {
            sent = false;
            looking = false;
            lookBody = null;
            recenter();
            return;
        }
        boolean active = mc.screen == null && (mc.isWindowActive() || DevProbe.active());
        float pitch = active ? (up.isDown() ? 1 : 0) - (down.isDown() ? 1 : 0) : 0,
                roll = active ? (right.isDown() ? 1 : 0) - (left.isDown() ? 1 : 0) : 0;
        prepare();
        var turn = pointer.step();
        mouseYaw = turn.x();
        mousePitch = -turn.y();
        if (pitch != 0 || roll != 0 || sent || mouseYaw != 0 || mousePitch != 0) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new SpacePackets.Attitude(pitch, roll, (float) mouseYaw, (float) mousePitch));
            if (DevProbe.active() && (mouseYaw != 0 || mousePitch != 0))
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE MOUSE SEND game {} angles {} {}",
                                mc.level.getGameTime(),
                                mouseYaw,
                                mousePitch);
        }
        pointer = pointer.remaining(turn);
        plannedStep = pointer.step();
        sent = pitch != 0 || roll != 0;
        mouseYaw = mousePitch = 0;
    }

    private SpaceAttitudeClient() {}
}
