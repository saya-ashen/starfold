package dev.starfold.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only resource-pack-resolved image; never mutate or close the atlas-owned image. */
@Mixin(SpriteContents.class)
public interface CubicSpriteAccess {
    @Accessor("originalImage") NativeImage starfold$cubicImage();
}
