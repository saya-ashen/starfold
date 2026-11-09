package dev.starfold.render;

import com.mojang.logging.LogUtils;

import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

/** One depth convention for the entire client frame; GlStateManager keeps logical vanilla state. */
public final class ReversedDepth {
    public static boolean ACTIVE;
    private static boolean initialized;

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        var caps = GL.getCapabilities();
        ACTIVE = (caps.OpenGL45 || caps.GL_ARB_clip_control);
        if (ACTIVE) {
            GL45.glClipControl(GL45.GL_LOWER_LEFT, GL45.GL_ZERO_TO_ONE);
            // RenderSystem.initRenderer already cached LEQUAL; give GL its reversed form.
            GL11.glDepthFunc(GL11.GL_GEQUAL);
            GL11.glClearDepth(0);
        }
        LogUtils.getLogger().info("Reversed depth active: {}", ACTIVE);
    }

    public static int depthFunc(int function) {
        return switch (function) {
            case GL11.GL_LESS -> GL11.GL_GREATER;
            case GL11.GL_GREATER -> GL11.GL_LESS;
            case GL11.GL_LEQUAL -> GL11.GL_GEQUAL;
            case GL11.GL_GEQUAL -> GL11.GL_LEQUAL;
            default -> function;
        };
    }

    public static Matrix4f projection(Matrix4f matrix) {
        var result = new Matrix4f(matrix);
        if (Math.abs(matrix.m33()) < 1e-5f
                && (matrix.m03() != 0 || matrix.m13() != 0 || matrix.m23() != 0)) {
            // Already reversed: a caller re-setting RenderSystem.getProjectionMatrix().
            if (matrix.m02() == 0 && matrix.m12() == 0 && matrix.m22() == 0) return result;
            float a = -matrix.m22() / matrix.m23();
            float b = matrix.m32() + a * matrix.m33();
            // For P * affine bobbing, w already contains the bobbed distance.
            return result.m02(0).m12(0).m22(0).m32(b / (a - 1));
        }
        return result.m02(.5f * (matrix.m03() - matrix.m02()))
                .m12(.5f * (matrix.m13() - matrix.m12()))
                .m22(.5f * (matrix.m23() - matrix.m22()))
                .m32(.5f * (matrix.m33() - matrix.m32()));
    }

    private ReversedDepth() {}
}
