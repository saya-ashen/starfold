package dev.starfold.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.render.EmptySectionTask;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionRenderDispatcher.class)
abstract class GroundSectionDispatchMixin {
    @Shadow private volatile boolean closed;

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void starfold$publishEmpty(@Coerce Object task, CallbackInfo ci) {
        if (closed || !(task instanceof EmptySectionTask empty) || !RenderSystem.isOnRenderThread()) return;
        var level = Minecraft.getInstance().level;
        if (level != null && PlanetLevels.is(level) && empty.starfold$publishEmpty()) ci.cancel();
    }
}
