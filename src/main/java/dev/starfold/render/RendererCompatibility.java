package dev.starfold.render;

import java.util.function.Predicate;

/** One policy for early mixin selection and the client projection guard; no game classes here. */
public final class RendererCompatibility {
    public static boolean supported(Predicate<String> loaded) {
        return !loaded.test("sodium")
                && !loaded.test("iris")
                && !loaded.test("embeddium")
                && !loaded.test("canvas");
    }

    public static boolean apply(String mixin, Predicate<String> loaded) {
        if (mixin.equals("dev.starfold.mixin.SentryShipMuzzleMixin"))
            return loaded.test("sentrymechanicalarm")
                    && loaded.test("tacz")
                    && loaded.test("sable");
        if (mixin.startsWith("dev.starfold.mixin.DevPort"))
            return Boolean.getBoolean("starfold.portProbe");
        // Both setupRender and allChanged belong to vanilla's terrain compiler/view area.
        // Replacing that compiler must not leave half of its projected observer installed.
        if (mixin.equals("dev.starfold.mixin.HullBottomCacheMixin")
                || mixin.equals("dev.starfold.mixin.PilotPoseProbeMixin")
                || mixin.equals("dev.starfold.mixin.SableShipWriteMixin")
                || mixin.equals("dev.starfold.mixin.SableShipStorageMixin")
                || mixin.equals("dev.starfold.mixin.SableShipSerializationMixin")
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
