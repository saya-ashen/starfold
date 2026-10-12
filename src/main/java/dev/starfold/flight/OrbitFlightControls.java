package dev.starfold.flight;

import com.mojang.blaze3d.platform.InputConstants;

import dev.starfold.probe.ProbeState;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import org.lwjgl.glfw.GLFW;

/** Hold-only binding: no sticky mode, no persisted flight state and no raw Alt polling. */
public final class OrbitFlightControls {
    private static KeyMapping direct;

    private OrbitFlightControls() {}

    public static boolean direct() {
        var mc = Minecraft.getInstance();
        return direct != null && mc.screen == null && mc.isWindowActive() && direct.isDown();
    }

    /** The own-flight binding is idle at a typewriter; reuse it for helm free look. */
    public static boolean freeLook() {
        var mc = Minecraft.getInstance();
        return direct != null
                && mc.screen == null
                && (mc.isWindowActive() || ProbeState.active())
                && direct.isDown();
    }

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        direct =
                new KeyMapping(
                        "key.starfold.direct_flight",
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_LEFT_ALT,
                        "key.categories.starfold");
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) ->
                        event.register(direct));
    }
}
