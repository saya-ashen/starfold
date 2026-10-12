package dev.starfold.render;

import net.minecraft.client.renderer.ShaderInstance;

import org.lwjgl.opengl.GL20;

/** Write through the shader's managed value before uploading. Veil discovers uniforms not in
 * the JSON and uploads them on apply; raw glUniform alone leaves its CPU copy at zero.
 * The raw path is for unregistered uniforms in vanilla, with program-scoped locations. */
public final class ShaderUniforms {
    private static final ProgramUniformCache<ShaderInstance> LOCATIONS =
            new ProgramUniformCache<>();

    private ShaderUniforms() {}

    public static void invalidate(ShaderInstance shader) {
        LOCATIONS.invalidate(shader);
    }

    private static int location(ShaderInstance s, String name) {
        int id = s.getId();
        return LOCATIONS.location(s, id, name, () -> GL20.glGetUniformLocation(id, name));
    }

    public static void setInt(ShaderInstance s, String name, int x) {
        var u = s.getUniform(name);
        if (u != null) {
            u.set(x);
            u.upload();
        } else GL20.glUniform1i(location(s, name), x);
    }

    public static void set(ShaderInstance s, String name, float x) {
        var u = s.getUniform(name);
        if (u != null) {
            u.set(x);
            u.upload();
        } else GL20.glUniform1f(location(s, name), x);
    }

    public static void set(ShaderInstance s, String name, float x, float y) {
        var u = s.getUniform(name);
        if (u != null) {
            u.set(x, y);
            u.upload();
        } else GL20.glUniform2f(location(s, name), x, y);
    }

    public static void set(ShaderInstance s, String name, float x, float y, float z) {
        var u = s.getUniform(name);
        if (u != null) {
            u.set(x, y, z);
            u.upload();
        } else GL20.glUniform3f(location(s, name), x, y, z);
    }

    public static void set(ShaderInstance s, String name, float x, float y, float z, float w) {
        var u = s.getUniform(name);
        if (u != null) {
            u.set(x, y, z, w);
            u.upload();
        } else GL20.glUniform4f(location(s, name), x, y, z, w);
    }
}
