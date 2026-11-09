package dev.starfold.worldgen.plan;

/** Uses the existing registry-only NeoForge test loader; no game/server/GUI. */
public final class PlanetPlanEval {
    public static void main(String[] args) throws Exception {
        var wrapper=Class.forName("net.neoforged.fml.junit.LaunchWrapper");
        var loader=(ClassLoader)wrapper.getMethod("getTransformingLoader").invoke(null);
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class.forName("dev.starfold.worldgen.plan.PlanetPlanEvaluation",true,loader).getMethod("run",String[].class).invoke(null,(Object)args);
        } catch(java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
    }
}
