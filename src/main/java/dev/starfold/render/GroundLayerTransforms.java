package dev.starfold.render;

import org.joml.Matrix4f;

import java.util.function.IntFunction;

/** Lazy chart placement for one render layer. The caller keeps placement inputs fixed in the layer. */
final class GroundLayerTransforms {
    private final IntFunction<Matrix4f> place;
    private final Matrix4f[] faces = new Matrix4f[6];

    GroundLayerTransforms(IntFunction<Matrix4f> place) {
        this.place = place;
    }

    Matrix4f copy(int face) {
        var matrix = faces[face];
        if (matrix == null) faces[face] = matrix = place.apply(face);
        // Shader/buffer consumers retain independent mutable arguments, including the first draw.
        return new Matrix4f(matrix);
    }
}
