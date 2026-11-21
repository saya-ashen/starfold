package dev.starfold.devtools.mixin;

import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.planet.PlanetLevels;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class SectionVisibilityMixin {
    @Shadow @Final private ObjectArrayList<RenderSection> visibleSections;

    @Inject(method = "compileSections", at = @At("HEAD"))
    private void starfold$visibleSections(Camera camera, CallbackInfo ci) {
        var trace = ChunkWorkDiagnostics.listener();
        if (trace == null) return;
        var level = Minecraft.getInstance().level;
        if (level == null || !PlanetLevels.is(level)) return;
        long now = System.nanoTime();
        for (var section : visibleSections) {
            var origin = section.getOrigin();
            trace.sectionVisible(ChunkPos.asLong(origin.getX() >> 4, origin.getZ() >> 4), origin.getY() >> 4, now);
        }
    }
}
