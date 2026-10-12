package dev.starfold.mixin;

import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FlatLevelGeneratorSettings.class)
public interface GroundFlatAccess {
    @Accessor("decoration")
    boolean starfold$decoration();

    @Accessor("addLakes")
    boolean starfold$lakes();
}
