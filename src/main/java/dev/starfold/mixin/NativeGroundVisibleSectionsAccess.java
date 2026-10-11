package dev.starfold.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Observe actual compiled visible sections, not merely loaded chunk data. */
@Mixin(LevelRenderer.class)
public interface NativeGroundVisibleSectionsAccess {
    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> starfold$visibleSections();
}
