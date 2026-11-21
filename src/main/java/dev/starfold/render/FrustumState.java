package dev.starfold.render;

import org.joml.Matrix4f;

/** Effective culling state, including captured frustums and camera-cube offsets. */
public interface FrustumState {
    Matrix4f starfold$matrix();

    double starfold$camX();

    double starfold$camY();

    double starfold$camZ();
}
