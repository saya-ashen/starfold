package dev.starfold.render;

/** Shared core-shader selection and Native wrappers for vanilla and Veil compilation. */
public final class CoreShaderSource {
    private CoreShaderSource() {}

    private static String coreName(String name) {
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
        return GroundShaderSource.wrap(name, vertex, source);
    }
}
