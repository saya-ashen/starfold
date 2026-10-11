package dev.starfold.mixin;
import dev.starfold.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
public abstract class NativeGroundFogMixin {
    @Shadow private static float fogRed;@Shadow private static float fogGreen;@Shadow private static float fogBlue;
    @Inject(method="setupColor",at=@At("TAIL"))
    private static void starfold$nativeSky(Camera camera,float partial,ClientLevel world,int distance,float darken,CallbackInfo ci){if(NativeGroundServer.is(world)&&NativeSpaceClient.present()){fogRed=fogGreen=fogBlue=0;RenderSystem.clearColor(0,0,0,0);return;}if(!NativeGroundServer.is(world)||!NativeGroundClient.ready())return;var sky=GroundSolar.sky(NativeGroundClient.elevation(),NativeGroundClient.darkness());fogRed=(float)sky.x();fogGreen=(float)sky.y();fogBlue=(float)sky.z();RenderSystem.clearColor(fogRed,fogGreen,fogBlue,0);}
}
