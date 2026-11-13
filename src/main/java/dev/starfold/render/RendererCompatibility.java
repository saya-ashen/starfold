package dev.starfold.render;

import java.util.function.Predicate;

/** One policy for early mixin selection and the client projection guard; no game classes here. */
public final class RendererCompatibility {
    /**
     * Sodium and Iris are handled by {@link SodiumSupport}, which also checks the Sodium shader
     * version and whether an Iris pack owns the world shaders. Embeddium and Canvas replace the
     * renderer in ways this projection has no shader hook for.
     */
    public static boolean supported(Predicate<String> loaded) {
        return !loaded.test("embeddium") && !loaded.test("canvas");
    }

    public static boolean apply(String mixin, Predicate<String> loaded) {
        if (mixin.equals("dev.starfold.mixin.SentryShipMuzzleMixin"))
            return loaded.test("sentrymechanicalarm")
                    && loaded.test("tacz")
                    && loaded.test("sable");
        if (mixin.startsWith("dev.starfold.mixin.DevPort"))
            return Boolean.getBoolean("starfold.portProbe");
        // Both hook Sodium's own chunk program; without Sodium they cannot resolve a target.
        if (mixin.startsWith("dev.starfold.mixin.Sodium")) return loaded.test("sodium");
        // Sodium, Embeddium and Canvas overwrite renderSectionLayer and setupRender, so the
        // section observer would have no draw call left to wrap. Frame start lives separately.
        if (mixin.equals("dev.starfold.mixin.GroundNativeDrawMixin"))
            return !loaded.test("sodium") && !loaded.test("embeddium") && !loaded.test("canvas");
        // Both setupRender and allChanged belong to vanilla's terrain compiler/view area.
        // Replacing that compiler must not leave half of its projected observer installed.
        if (mixin.equals("dev.starfold.mixin.HullBottomCacheMixin")
                || mixin.equals("dev.starfold.mixin.PilotPoseProbeMixin")
                || mixin.equals("dev.starfold.mixin.SableShipWriteMixin")
                || mixin.equals("dev.starfold.mixin.SableShipStorageMixin")
                || mixin.equals("dev.starfold.mixin.SableShipSerializationMixin")
                || mixin.equals("dev.starfold.mixin.SableShipSplitMixin")
                || mixin.equals("dev.starfold.mixin.SableFuelMassMixin")
                || mixin.equals("dev.starfold.mixin.SableShipReadinessMixin"))
            return loaded.test("sable");
        if (mixin.equals("dev.starfold.mixin.HelmFitMixin")
                || mixin.equals("dev.starfold.mixin.HelmCollisionPoseMixin")
                || mixin.equals("dev.starfold.mixin.HelmPerspectiveMixin")
                || mixin.equals("dev.starfold.mixin.HelmCameraMixin")
                || mixin.equals("dev.starfold.mixin.HelmMouseMixin")) return loaded.test("sable");
        return true;
    }

    /** A new client level (including reconnect) is a new entry, not every tick or face seam. */
    static final class Notice {
        private Object level;
        private boolean announced;

        boolean enter(Object current, boolean planet, boolean player, boolean supported) {
            if (level != current) {
                level = current;
                announced = false;
            }
            if (current == null || !planet || !player || supported || announced) return false;
            announced = true;
            return true;
        }
    }

    private RendererCompatibility() {}
}
