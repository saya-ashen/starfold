package dev.starfold.ground;

import dev.starfold.planet.PlanetAccess;

/** Light propagation re-addressing for face seams; see GroundLightSeamMixin. */
public final class LightSeams {
    /** Implemented by every LightEngine: the Native layout it lights, or null for other levels and clients. */
    public interface Engine {
        PlanetAccess starfold$seams();
    }

    public static long block(Object engine, long pos) {
        var access = ((Engine) engine).starfold$seams();
        return access == null ? pos : ChunkViews.alias(access, pos);
    }

    /** A halo position whose owner section holds no light data (a corner or a just-unloaded owner) reads dark and takes no writes. */
    public static int get(
            Object engine,
            Object storage,
            long pos,
            java.util.function.LongToIntFunction original) {
        long owner = block(engine, pos);
        if (owner != pos
                && !((dev.starfold.mixin.GroundLightStorageAccess) storage)
                        .starfold$storing(net.minecraft.core.SectionPos.blockToSection(owner)))
            return 0;
        return original.applyAsInt(owner);
    }

    public static void set(
            Object engine,
            Object storage,
            long pos,
            int level,
            java.util.function.LongConsumer original) {
        long owner = block(engine, pos);
        if (owner != pos
                && !((dev.starfold.mixin.GroundLightStorageAccess) storage)
                        .starfold$storing(net.minecraft.core.SectionPos.blockToSection(owner)))
            return;
        original.accept(owner);
    }

    public static long section(Object engine, long section) {
        var access = ((Engine) engine).starfold$seams();
        return access == null ? section : ChunkViews.aliasSection(access, section);
    }

    private LightSeams() {}
}
