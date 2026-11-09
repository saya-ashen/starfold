package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class ReversedDepthTest {
    @Test
    void projectionReversesDepthWithoutChangingCallerOrBobbing() {
        float near = .05f;
        var plain = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9, near, 224);
        var bobbing = new Matrix4f().translate(.04f, -.06f, 0)
                .rotateZ((float) Math.toRadians(2.5)).rotateX((float) Math.toRadians(1.8));
        for (var view : new Matrix4f[] {new Matrix4f(), bobbing}) {
            var matrix = new Matrix4f(plain).mul(view);
            var original = new Matrix4f(matrix);
            var reversed = ReversedDepth.projection(matrix);
            assertEquals(original, matrix);
            assertNotSame(matrix, reversed);
            assertEquals(reversed, ReversedDepth.projection(reversed));
            for (float distance : new float[] {near, 1400, 900_000}) {
                var point = view.invert(new Matrix4f()).transform(new Vector4f(0, 0, -distance, 1));
                var clip = reversed.transform(point);
                assertEquals(near / distance, clip.z / clip.w, (near / distance) * 2e-6f);
            }
            for (float x : new float[] {-60000, 0, 60000})
                for (float y : new float[] {-60000, 0, 60000}) {
                    var point = new Vector4f(x, y, -900_000, 1);
                    float distance = -view.transform(new Vector4f(point)).z;
                    var clip = reversed.transform(point);
                    assertEquals(near / distance, clip.z / clip.w, (near / distance) * 2e-6f);
                }
        }
        var ortho = new Matrix4f().ortho(0, 320, 240, 0, .1f, 1000);
        var original = new Matrix4f(ortho);
        var reversed = ReversedDepth.projection(ortho);
        assertEquals(original, ortho);
        for (float ndc : new float[] {-1, 0, 1}) {
            var point = ortho.invert(new Matrix4f()).transform(new Vector4f(0, 0, ndc, 1));
            var clip = reversed.transform(point);
            assertEquals(.5f * (1 - ndc), clip.z / clip.w, 1e-6f);
        }
    }
}
