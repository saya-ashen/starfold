package dev.starfold.api;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import net.minecraft.client.renderer.ShaderInstance;

/** Optional client appearance extension. No registration means byte-for-byte unchanged shaders.
 * Snippets run on material colour, before Starfold's atmosphere grade. They must not change alpha.
 * Near also covers captured terrain and all chart/voxel LODs; exterior covers whole planets.
 * Terrain vertex/near patches can read flat float cpSurfaceClass (SurfaceClass IDs); alpha is restored
 * before upstream shader readers. Exterior patches receive the same flat cpSurfaceClass.
 * Register during client mod construction, before shader compilation. */
public final class PlanetSurfaceRender {
    public record Patch(String declarations, String apply) {
        public static final Patch NONE = new Patch("", "");
    }
    public record Context(GroundEphemeris.Model model, int planet, double tick, Face observer, dev.starfold.worldgen.plan.terrain.TerrainPlan terrain) {
        /** Convert body-local vectors to the near shader's observer-face coordinates. */
        public Point vector(Point p) {
            return observer == null ? p : new Point(p.dot(observer.u), p.dot(observer.normal), p.dot(observer.v));
        }
    }
    public interface Appearance {
        Patch near(String shader);
        default Patch vertex(String shader) { return Patch.NONE; }
        Patch exterior();
        /** Invoked only with the shader program bound. */
        void uniforms(ShaderInstance shader, Context context);
    }
    private static Appearance appearance;
    private static Context exterior;
    public static void register(Appearance value) {
        if (appearance != null) throw new IllegalStateException("Surface appearance already registered");
        appearance = java.util.Objects.requireNonNull(value);
    }
    public static Patch vertex(String name) { return appearance == null ? Patch.NONE : appearance.vertex(name); }
    public static Patch near(String name) { return appearance == null ? Patch.NONE : appearance.near(name); }
    public static String exterior(String name, boolean vertex, String source) {
        if (appearance == null || vertex || !name.equals("starfold:native_ground_exterior")) return source;
        var patch = appearance.exterior();
        return source.replace("void main(){", patch.declarations() + "\nvoid main(){")
                .replace("surfaceColor.rgb*light*AtmosphereGrade", "surface.rgb*light*AtmosphereGrade")
                .replace("float light=0.12+0.88*daylight;", "vec4 surface=surfaceColor;" + patch.apply() + "\nfloat light=0.12+0.88*daylight;");
    }
    public static void nearUniforms(ShaderInstance shader, Context context) {
        if (appearance != null) appearance.uniforms(shader, context);
    }
    /** Render-thread scope around exterior draws; never used by terrain shaders. */
    public static void exteriorContext(Context context) { exterior = context; }
    public static void apply(ShaderInstance shader) {
        if (appearance != null && exterior != null && shader.getFragmentProgram().getName().equals("starfold:native_ground_exterior"))
            appearance.uniforms(shader, exterior);
    }
    private PlanetSurfaceRender() {}
}
