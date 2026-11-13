package dev.starfold.render;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/**
 * Sodium projection support is version- and pipeline-specific: 0.8.x chunk shaders, no Iris shader
 * pack. Anything else must keep the whole projection off instead of warping half the world.
 */
public final class SodiumSupport {
    private static Boolean loaded;
    private static Boolean version;
    private static volatile boolean broken;
    private static boolean packWarned, irisUnknown;
    private static Class<?> irisApi;
    private static java.lang.reflect.Method irisInstance;
    private static java.lang.reflect.Method irisPackInUse;

    static boolean present() {
        var value = loaded;
        if (value == null) loaded = value = ModList.get().isLoaded("sodium");
        return value;
    }

    public static boolean supported() {
        return !present() || shaderVersion() && !irisShaderPack();
    }

    /** Whether Starfold may claim native chunk coverage from Sodium's terrain. */
    static boolean tracksNativeChunks() {
        return present() && supported() && !broken();
    }

    /** Sodium's chunk shader did not match the layout this projection can wrap. */
    public static boolean broken() {
        return broken;
    }

    public static void breakShader(String detail) {
        if (broken) return;
        broken = true;
        org.slf4j.LoggerFactory.getLogger("starfold-renderer")
                .error(
                        "Starfold cannot project Sodium's chunk shader ({}); planet rendering is"
                                + " disabled",
                        detail);
    }

    private static boolean shaderVersion() {
        var value = version;
        if (value != null) return value;
        String text = "";
        try {
            text =
                    ModList.get()
                            .getModContainerById("sodium")
                            .map(container -> container.getModInfo().getVersion().toString())
                            .orElse("");
        } catch (RuntimeException ignored) {
        }
        version = value = text.matches(".*(?:^|[^0-9])0\\.8\\..*");
        if (!value)
            org.slf4j.LoggerFactory.getLogger("starfold-renderer")
                    .warn(
                            "Starfold supports Sodium 0.8.x chunk shaders; found '{}'. Planet"
                                    + " rendering is disabled",
                            text);
        return value;
    }

    /**
     * Iris always owns Sodium's chunk program once a pack is enabled, and pack shaders cannot carry
     * this projection, so an active pack (or an unanswerable API) means unsupported. The answer is
     * read every frame: packs can be switched without restarting the game.
     */
    static boolean irisShaderPack() {
        if (!ModList.get().isLoaded("iris")) return false;
        if (irisUnknown) return true;
        try {
            if (irisApi == null) {
                irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisInstance = irisApi.getMethod("getInstance");
                irisPackInUse = irisApi.getMethod("isShaderPackInUse");
            }
            return (Boolean) irisPackInUse.invoke(irisInstance.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Unknown state is not the safe state to warp terrain in.
            irisUnknown = true;
            if (!packWarned) {
                packWarned = true;
                org.slf4j.LoggerFactory.getLogger("starfold-renderer")
                        .warn("Starfold could not query Iris; assuming a shader pack is active", e);
            }
            return true;
        }
    }

    /** Horizontal radius of chunks Sodium can be drawing natively this frame. */
    static int radius() {
        var mc = Minecraft.getInstance();
        if (mc.options == null) return 0;
        return Math.max(2, mc.options.getEffectiveRenderDistance() - 1);
    }

    private SodiumSupport() {}
}
