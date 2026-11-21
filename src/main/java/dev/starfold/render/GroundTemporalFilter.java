package dev.starfold.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Jittered world resolve before hand/HUD rendering; history never contains the GUI. */
public final class GroundTemporalFilter {
    private static int program, presentProgram, vao, width, height, inputFbo, inputColor, inputDepth;
    private static final int[] historyFbo = new int[2], historyColor = new int[2], historyDepth = new int[2];
    private static int write, sequence, face = -1;
    private static boolean active, valid, failed;
    private static Object level, observer;
    private static Vec3 eye, previousEye;
    private static final Matrix4f view = new Matrix4f(), projection = new Matrix4f();
    private static final Matrix4f previousView = new Matrix4f(), previousProjection = new Matrix4f();
    private static long lastFrame;
    private static float jitterX, jitterY, previousJitterX, previousJitterY;
    private static org.joml.Vector3f lens = new org.joml.Vector3f(), previousLens = new org.joml.Vector3f();

    public static float jitterX() { return active ? jitterX : 0; }
    public static float jitterY() { return active ? jitterY : 0; }

    public static void begin(Camera camera, Matrix4f cameraView) {
        active = false;
        if (failed || !GroundClient.projectedFrame()) {
            valid = false;
            return;
        }
        var mc = Minecraft.getInstance();
        var target = mc.getMainRenderTarget();
        if (target.width <= 0 || target.height <= 0
                || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != target.frameBufferId
                || GL11.glGetInteger(GL13.GL_SAMPLES) != 0
                || !singleColor(target.getColorTextureId(), target.getDepthTextureId())) {
            valid = false;
            return;
        }
        try (var state = new State()) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            if (program == 0) createProgram();
            if (width != target.width || height != target.height) allocate(target.width, target.height);
            long now = System.nanoTime();
            var chartEye = GroundClient.eye().add(GroundClient.renderFrame.viewOffset);
            eye = new Vec3(chartEye.x(), chartEye.y(), chartEye.z());
            var currentObserver = GroundClient.frame();
            if (level != mc.level || observer == null || !observer.equals(currentObserver)
                    || face != GroundClient.face() || previousEye == null
                    || eye.distanceToSqr(previousEye) > 16 || now - lastFrame > 250_000_000L) valid = false;
            level = mc.level;
            observer = currentObserver;
            face = GroundClient.face();
            lastFrame = now;
            var frame = GroundClient.renderFrame;
            view.identity().setColumn(0, new org.joml.Vector4f(frame.X, 0))
                    .setColumn(1, new org.joml.Vector4f(frame.Y, 0))
                    .setColumn(2, new org.joml.Vector4f(frame.Z, 0));
            projection.set(RenderSystem.getProjectionMatrix());
            var optics = dev.starfold.chart.GroundSpaceOptics.lens(projection, GroundClient.eye().y());
            lens.set((float) optics.weight(), (float) optics.x(), (float) optics.y());
            if (valid && Math.abs(projection.m00() - previousProjection.m00()) > .01f) valid = false;
            int sample = (sequence++ & 7) + 1;
            // MSAA already integrates subpixel coverage; full-width jitter softens it again.
            float spread = .35f;
            jitterX = (halton(sample, 2) - .5f) * 2 * spread / width;
            jitterY = (halton(sample, 3) - .5f) * 2 * spread / height;
            active = true;
        } catch (RuntimeException failure) {
            failed = true;
            valid = false;
            LogUtils.getLogger().error("Ground TAA disabled after initialization failure", failure);
        }
    }

    private static float halton(int value, int base) {
        float scale = 1, result = 0;
        for (; value > 0; value /= base) { scale /= base; result += scale * (value % base); }
        return result;
    }

    private static boolean singleColor(int color, int depth) {
        if (color <= 0 || depth <= 0
                || GL11.glGetInteger(GL11.GL_DRAW_BUFFER) != GL30.GL_COLOR_ATTACHMENT0
                || GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                        GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) != color
                || GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                        GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) != depth) return false;
        int count = GL11.glGetInteger(GL30.GL_MAX_COLOR_ATTACHMENTS);
        for (int i = 1; i < count; i++)
            if (GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0 + i, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL11.GL_NONE) return false;
        return true;
    }

    public static void resolve() {
        if (!active) return;
        try (var state = new State()) {
            var target = Minecraft.getInstance().getMainRenderTarget();
            if (state.draw != target.frameBufferId
                    || !singleColor(target.getColorTextureId(), target.getDepthTextureId())) {
                valid = false;
                return;
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, state.draw);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, inputFbo);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, historyFbo[write]);
            GL11.glViewport(0, 0, width, height);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL11.glColorMask(true, true, true, true);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            bind(0, inputColor); bind(1, inputDepth);
            bind(2, historyColor[write ^ 1]); bind(3, historyDepth[write ^ 1]);
            uniform("CurrentColor", 0); uniform("CurrentDepth", 1);
            uniform("HistoryColor", 2); uniform("HistoryDepth", 3);
            uniform("HasHistory", valid ? 1 : 0);
            uniform("ZeroToOne", ReversedDepth.ACTIVE ? 1 : 0);
            GL20.glUniform2f(location("PixelSize"), 1f / width, 1f / height);
            GL20.glUniform2f(location("Jitter"), jitterX, jitterY);
            GL20.glUniform2f(location("PreviousJitter"), previousJitterX, previousJitterY);
            matrix("Projection", projection);
            matrix("PreviousProjection", previousProjection);
            GL20.glUniform3f(location("Lens"), lens.x, lens.y, lens.z);
            GL20.glUniform3f(location("PreviousLens"), previousLens.x, previousLens.y, previousLens.z);
            var previousClip = new Matrix4f(previousView);
            if (previousEye != null) previousClip.translate((float) (eye.x - previousEye.x),
                    (float) (eye.y - previousEye.y), (float) (eye.z - previousEye.z));
            previousClip.mul(new Matrix4f(view).invert());
            matrix("CurrentToPreviousView", previousClip);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, state.draw);
            GL20.glUseProgram(presentProgram);
            bind(0, historyColor[write]);
            GL20.glUniform1i(GL20.glGetUniformLocation(presentProgram, "ResolvedColor"), 0);
            GL20.glUniform2f(GL20.glGetUniformLocation(presentProgram, "PixelSize"), 1f / width, 1f / height);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            previousProjection.set(projection);
            previousLens.set(lens);
            previousJitterX = jitterX; previousJitterY = jitterY;
            previousView.set(view);
            previousEye = eye;
            valid = true;
            write ^= 1;
        } catch (RuntimeException failure) {
            failed = true;
            valid = false;
            LogUtils.getLogger().error("Ground TAA disabled after resolve failure", failure);
        }
    }

    public static void end() { active = false; }

    public static void reset() {
        active = valid = false;
        level = observer = null;
        previousEye = null;
        sequence = 0;
    }

    private static int location(String name) { return GL20.glGetUniformLocation(program, name); }
    private static void uniform(String name, int value) { GL20.glUniform1i(location(name), value); }
    private static void matrix(String name, Matrix4f value) {
        try (var stack = MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(location(name), false, value.get(stack.mallocFloat(16)));
        }
    }
    private static void bind(int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL33.glBindSampler(unit, 0);
    }
    private static int texture(int format, int filtering) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filtering);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filtering);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        int external = format == GL30.GL_DEPTH_COMPONENT32F ? GL11.GL_DEPTH_COMPONENT
                : format == GL30.GL_R32F ? GL11.GL_RED : GL11.GL_RGBA;
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, width, height, 0, external, GL11.GL_FLOAT, 0L);
        return id;
    }
    private static void attach(int point, int texture) {
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, point, GL11.GL_TEXTURE_2D, texture, 0);
    }
    private static void complete() {
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Incomplete ground temporal framebuffer");
    }
    private static void allocate(int w, int h) {
        releaseBuffers();
        width = w; height = h;
        inputFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, inputFbo);
        inputColor = texture(GL30.GL_RGBA16F, GL11.GL_LINEAR);
        inputDepth = texture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_NEAREST);
        attach(GL30.GL_COLOR_ATTACHMENT0, inputColor);
        attach(GL30.GL_DEPTH_ATTACHMENT, inputDepth);
        complete();
        for (int i = 0; i < 2; i++) {
            historyFbo[i] = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, historyFbo[i]);
            historyColor[i] = texture(GL30.GL_RGBA16F, GL11.GL_LINEAR);
            historyDepth[i] = texture(GL30.GL_R32F, GL11.GL_NEAREST);
            attach(GL30.GL_COLOR_ATTACHMENT0, historyColor[i]);
            attach(GL30.GL_COLOR_ATTACHMENT1, historyDepth[i]);
            GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
            complete();
        }
        valid = false;
    }
    private static void releaseBuffers() {
        if (inputFbo != 0) GL30.glDeleteFramebuffers(inputFbo);
        if (inputColor != 0) GL11.glDeleteTextures(inputColor);
        if (inputDepth != 0) GL11.glDeleteTextures(inputDepth);
        inputFbo = inputColor = inputDepth = 0;
        for (int i = 0; i < 2; i++) {
            if (historyFbo[i] != 0) GL30.glDeleteFramebuffers(historyFbo[i]);
            if (historyColor[i] != 0) GL11.glDeleteTextures(historyColor[i]);
            if (historyDepth[i] != 0) GL11.glDeleteTextures(historyDepth[i]);
            historyFbo[i] = historyColor[i] = historyDepth[i] = 0;
        }
        width = height = 0;
    }
    public static void clear() {
        reset();
        releaseBuffers();
        if (program != 0) GL20.glDeleteProgram(program);
        if (presentProgram != 0) GL20.glDeleteProgram(presentProgram);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        program = presentProgram = vao = 0;
        failed = false;
    }
    private static String resource(String name) {
        try (var in = GroundTemporalFilter.class.getResourceAsStream("/assets/starfold/shaders/core/ground_temporal." + name)) {
            if (in == null) throw new IllegalStateException("Missing temporal shader " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    private static int shader(int type, String source) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, source);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(id);
            GL20.glDeleteShader(id);
            throw new IllegalStateException(log);
        }
        return id;
    }
    private static void createProgram() {
        program = link("fsh");
        presentProgram = link("present.fsh");
        vao = GL30.glGenVertexArrays();
    }

    private static int link(String fragmentResource) {
        int vertex = shader(GL20.GL_VERTEX_SHADER, resource("vsh"));
        int fragment = 0, linked = 0;
        try {
            fragment = shader(GL20.GL_FRAGMENT_SHADER, resource(fragmentResource));
            linked = GL20.glCreateProgram();
            GL20.glAttachShader(linked, vertex); GL20.glAttachShader(linked, fragment);
            GL20.glLinkProgram(linked);
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(linked));
            int result = linked; linked = 0; return result;
        } finally {
            GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
            if (linked != 0) GL20.glDeleteProgram(linked);
        }
    }

    /** Raw GL changes are restored without changing Minecraft's cached state. */
    private static final class State implements AutoCloseable {
        final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        final int[] textures = new int[4], samplers = new int[4], viewport = new int[4], polygon = new int[2];
        final boolean[] colorMask = new boolean[4];
        final int[] caps = {GL11.GL_DEPTH_TEST, GL11.GL_BLEND, GL11.GL_CULL_FACE,
                GL11.GL_SCISSOR_TEST, GL11.GL_STENCIL_TEST, GL30.GL_FRAMEBUFFER_SRGB};
        final boolean[] enabled = new boolean[caps.length];
        State() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, polygon);
            try (var stack = MemoryStack.stackPush()) {
                var mask = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < 4; i++) colorMask[i] = mask.get(i) != 0;
            }
            for (int i = 0; i < caps.length; i++) enabled[i] = GL11.glIsEnabled(caps[i]);
            for (int i = 0; i < 4; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                samplers[i] = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, i);
            }
            GL13.glActiveTexture(activeTexture);
        }
        public void close() {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, polygon[0]);
            GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            for (int i = 0; i < caps.length; i++) {
                if (enabled[i]) GL11.glEnable(caps[i]); else GL11.glDisable(caps[i]);
            }
            for (int i = 0; i < 4; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
                GL33.glBindSampler(i, samplers[i]);
            }
            GL13.glActiveTexture(activeTexture);
        }
    }
    private GroundTemporalFilter() {}
}
