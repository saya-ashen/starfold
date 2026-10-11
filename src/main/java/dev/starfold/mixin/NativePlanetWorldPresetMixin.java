package dev.starfold.mixin;

import dev.starfold.NativePlanetWorldPreset;
import net.minecraft.core.Registry;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** No saved v2 dimension can become v3 merely because a newer mod JAR is installed. */
@Mixin(WorldDimensions.class)
public abstract class NativePlanetWorldPresetMixin {
    @ModifyVariable(method="bake",at=@At("HEAD"),argsOnly=true)
    private Registry<LevelStem> starfold$explicitMiningPreset(Registry<LevelStem> original){return NativePlanetWorldPreset.registryFor((WorldDimensions)(Object)this,original);}
}
