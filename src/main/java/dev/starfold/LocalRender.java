package dev.starfold;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Frame values only: entity/particle stages carry vanilla's identity stack, not the model-view matrix. */
public record LocalRender(RenderLevelStageEvent event) {
    public PoseStack matrixStack(){return event.getPoseStack();}
    public Matrix4f positionMatrix(){return event.getModelViewMatrix();}
    public Matrix4f projectionMatrix(){return event.getProjectionMatrix();}
    public Camera camera(){return event.getCamera();}
    public DeltaTracker tickCounter(){return event.getPartialTick();}
}
