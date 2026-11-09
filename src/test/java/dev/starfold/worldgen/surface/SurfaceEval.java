package dev.starfold.worldgen.surface;

/** Reuses NeoForge's registry-only JUnit bootstrap. Does not start a client, server, world or window. */
public final class SurfaceEval {
    public static void main(String[] args) throws Exception {
        var wrapper = Class.forName("net.neoforged.fml.junit.LaunchWrapper");
        var loader = (ClassLoader) wrapper.getMethod("getTransformingLoader").invoke(null);
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class.forName("dev.starfold.worldgen.surface.SurfaceEvaluation",true,loader)
                    .getMethod("run").invoke(null);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }
}
