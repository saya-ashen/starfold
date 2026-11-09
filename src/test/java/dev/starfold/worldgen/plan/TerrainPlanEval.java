package dev.starfold.worldgen.plan;
/** Registry-only offline entry point, using the existing NeoForge JUnit loader. */
public final class TerrainPlanEval {
    public static void main(String[] args) throws Exception {
        var wrapper = Class.forName("net.neoforged.fml.junit.LaunchWrapper");
        var loader = (ClassLoader) wrapper.getMethod("getTransformingLoader").invoke(null);
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class.forName("dev.starfold.worldgen.plan.TerrainPlanEvaluation", true, loader)
                .getMethod("run", String[].class)
                .invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }
}
