package dev.starfold.mixin;

import net.minecraft.world.level.lighting.LayerLightSectionStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets seam light re-addressing confirm the owner section is stored before touching it. */
@Mixin(LayerLightSectionStorage.class)
public interface GroundLightStorageAccess {
    @Invoker("storingLightForSection")
    boolean starfold$storing(long section);
}
