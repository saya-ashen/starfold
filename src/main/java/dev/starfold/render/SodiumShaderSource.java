package dev.starfold.render;

/**
 * Sodium 0.8.x compiles its own chunk program ({@code sodium:blocks/block_layer_opaque}), so the
 * {@code rendertype_*} patches never reach terrain. Keep upstream shading untouched: add the
 * projection include after Sodium declared everything it reads (chunk matrices, fog uniforms and
 * {@code v_FragDistance}), call it after Sodium computed the flat position, then finish the
 * fragment with Starfold's discards and atmosphere.
 */
public final class SodiumShaderSource {
    static final String VERTEX = "blocks/block_layer_opaque.vsh";
    static final String FRAGMENT = "blocks/block_layer_opaque.fsh";

    static final String VERTEX_INCLUDE = "#import <starfold:include/native_terrain_vertex.glsl>";
    static final String FRAGMENT_INCLUDE =
            "#import <starfold:include/native_terrain_fragment.glsl>";

    /** Sodium's flat projection; the warp has to run after the position is fully assembled. */
    private static final String VERTEX_ANCHOR =
            "gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);";

    private static final String FRAGMENT_ANCHOR =
            "fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);";

    private static final String MAIN = "void main() {";

    /** Whether Sodium asked for one of the two shaders this projection replaces. */
    public static boolean handles(String path) {
        return VERTEX.equals(path) || FRAGMENT.equals(path);
    }

    public static String patch(String path, String source) {
        if (source.contains(VERTEX_INCLUDE) || source.contains(FRAGMENT_INCLUDE)) return source;
        if (VERTEX.equals(path)) {
            require(source, VERTEX_ANCHOR, path);
            return include(source, VERTEX_INCLUDE)
                    .replace(VERTEX_ANCHOR, VERTEX_ANCHOR + "\n    cpNativeWarp(position);");
        }
        if (FRAGMENT.equals(path)) {
            require(source, FRAGMENT_ANCHOR, path);
            return include(source, FRAGMENT_INCLUDE)
                    .replace(
                            FRAGMENT_ANCHOR,
                            FRAGMENT_ANCHOR + "\n    cpNativeFragment(fragColor);");
        }
        return source;
    }

    /**
     * The includes read Sodium's chunk matrices, fog uniforms and {@code v_FragDistance}, so they
     * must follow every declaration in the shader: insert directly before {@code main}.
     */
    private static String include(String source, String line) {
        int at = source.indexOf(MAIN);
        if (at < 0 || source.indexOf(MAIN, at + 1) >= 0)
            throw new IllegalArgumentException(
                    "Unexpected Sodium chunk shader layout: no single main");
        return source.substring(0, at) + line + "\n" + source.substring(at);
    }

    private static void require(String source, String anchor, String path) {
        if (!source.contains(anchor))
            throw new IllegalArgumentException("Unexpected Sodium chunk shader layout: " + path);
    }

    private SodiumShaderSource() {}
}
