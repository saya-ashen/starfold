package dev.starfold.render;

/** Shared core-shader selection and Native wrappers for vanilla and Veil compilation. */
public final class CoreShaderSource {
    private CoreShaderSource() {}

    private static String coreName(String name) {
        if (name.equals(GroundCompact.NAME) || GroundShaderSource.compact(name)) return "rendertype_solid";
        return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    }

    public static boolean supported(String name) {
        name = coreName(name);
        return name.equals("particle")
                || (name.startsWith("rendertype_")
                        && !name.startsWith("rendertype_gui")
                        && !name.equals("rendertype_clouds"));
    }

    public static String patch(String name, boolean vertex, String source) {
        return wrap(name, vertex, hooks(name, vertex, source));
    }

    /** Hooks inside upstream code must precede other mods' shader transformers. */
    public static String hooks(String name, boolean vertex, String source) {
        return GroundShaderSource.hooks(name, vertex, source);
    }

    /** Veil's GLSL serializer needs a body for every function node. Put Mekasuit's helper before its caller. */
    public static String veilFragment(
            net.minecraft.resources.ResourceLocation id, boolean fragment, String source) {
        if (!fragment
                || !id.getNamespace().equals("mekanism")
                || !id.getPath().equals("shaders/core/rendertype_mekasuit.fsh")) return source;
        String signature = "bool shouldTint(float red, float green, float blue)";
        int declaration = source.indexOf(signature + ";");
        int definition = source.indexOf(signature + " {");
        if (declaration < 0 || definition <= declaration) return source;
        int end = source.indexOf('}', definition);
        if (end < 0) return source;
        return source.substring(0, declaration)
                + source.substring(definition, end + 1)
                + source.substring(declaration + signature.length() + 1, definition)
                + source.substring(end + 1);
    }

    /** Wrap the finished entry point after other mods' transformers. */
    public static String wrap(String name, boolean vertex, String source) {
        if (supported(name) && !source.contains("void cpCapOriginalMain(")) {
            var entry =
                    java.util.regex.Pattern.compile(
                                    "\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{")
                            .matcher(source);
            if (!entry.find())
                throw new IllegalArgumentException("Unsupported core shader layout: " + name);
            source =
                    entry.replaceFirst("void cpCapOriginalMain() {")
                            + "\nvoid main(){cpCapOriginalMain();}\n";
        }
        source = dev.starfold.api.PlanetSurfaceRender.exterior(name, vertex, source);
        source = GroundShaderSource.wrap(name, vertex, source);
        if (vertex && (name.equals(GroundCompact.NAME) || GroundShaderSource.compact(name)) && source.contains("in vec3 Position;"))
            source = GroundCompact.unpack(source);
        if (vertex && supported(name) && !source.contains("void cpAaOriginalMain(")) {
            source = source.replaceAll("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)", "void cpAaOriginalMain()");
            source += "\nuniform vec2 CPWorldJitter;\nvoid main(){cpAaOriginalMain();gl_Position.xy+=CPWorldJitter*gl_Position.w;}\n";
        }
        return source;
    }
}
