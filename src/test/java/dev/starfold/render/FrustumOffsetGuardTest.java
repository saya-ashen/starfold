package dev.starfold.render;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrustumOffsetGuardTest {
    @Test
    void degenerateFrustumGivesUpInsteadOfSpinning() {
        var matrix = new Matrix4f().m00(Float.NaN);
        var view = matrix.transformTranspose(new Vector4f(0, 0, 1, 0));
        assertNull(FrustumOffset.offset(new FrustumIntersection(matrix), view, 16384.5, 200, 24576.5, 8));
    }

    @Test
    void ordinaryFrustumBacksOffUntilTheCameraCubeFits() {
        var matrix = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, .05f, 512);
        var view = matrix.transformTranspose(new Vector4f(0, 0, 1, 0));
        var camera = FrustumOffset.offset(new FrustumIntersection(matrix), view, 3.5, 70.5, -2.5, 8);
        assertNotNull(camera);
        assertTrue(camera[2] > -2.5, "backed off against the view direction");
        assertEquals(FrustumIntersection.INSIDE, new FrustumIntersection(matrix).intersectAab(
                (float) (0 - camera[0]), (float) (64 - camera[1]), (float) (-8 - camera[2]),
                (float) (8 - camera[0]), (float) (72 - camera[1]), (float) (0 - camera[2])));
    }
}
