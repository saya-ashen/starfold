package dev.starfold.mixin;

import dev.starfold.render.FrustumState;

import net.minecraft.client.renderer.culling.Frustum;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Implements the ordinary culling-state contract without exposing a mixin type to callers. */
@Mixin(Frustum.class)
public abstract class FrustumAccess implements FrustumState {
    @Shadow @Final private Matrix4f matrix;
    @Shadow private double camX;
    @Shadow private double camY;
    @Shadow private double camZ;

    @Override
    public Matrix4f starfold$matrix() {
        return matrix;
    }

    @Override
    public double starfold$camX() {
        return camX;
    }

    @Override
    public double starfold$camY() {
        return camY;
    }

    @Override
    public double starfold$camZ() {
        return camZ;
    }
}
