package dev.starfold.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(EntityRenderDispatcher.class)
public abstract class NativeGroundEntityRenderMixin {
    @WrapMethod(method="render")
    private <E extends Entity> void starfold$nativeEntity(E entity,double x,double y,double z,float yaw,float partial,PoseStack poses,MultiBufferSource buffers,int light,Operation<Void> original){
        // Entities arrive at their image in the observer's continuous coordinates: draw them as vanilla does.
        original.call(entity,x,y,z,yaw,partial,poses,buffers,light);
    }
    /** Region columns have no skylight sections at ship height; retain the renderer's block/fire light. */
    @Inject(method="getPackedLightCoords",at=@At("RETURN"),cancellable=true)
    private <E extends Entity> void starfold$regionLight(E entity,float partial,CallbackInfoReturnable<Integer> ci){if(NativeSpaceClient.active()&&NativeSpaceServer.in(entity))ci.setReturnValue(net.minecraft.client.renderer.LightTexture.pack(net.minecraft.client.renderer.LightTexture.block(ci.getReturnValue()),15));}
    /** A vessel may have no chunk about it to be lit by: the hand of its pilot is in the open sky. */
    @Inject(method="getPackedLightCoords",at=@At("HEAD"),cancellable=true)
    private <E extends Entity> void starfold$nativeSpaceLight(E entity,float partial,CallbackInfoReturnable<Integer> ci){if(entity==net.minecraft.client.Minecraft.getInstance().player&&NativeGroundFlightClient.controlled())ci.setReturnValue(net.minecraft.client.renderer.LightTexture.pack(0,15));}
}
