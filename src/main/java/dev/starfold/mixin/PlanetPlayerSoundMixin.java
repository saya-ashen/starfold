package dev.starfold.mixin;

import dev.starfold.planet.PlanetSoundOwner;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.ElytraOnPlayerSoundInstance;
import net.minecraft.client.resources.sounds.UnderwaterAmbientSoundInstances;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin({
    ElytraOnPlayerSoundInstance.class,
    UnderwaterAmbientSoundInstances.UnderwaterAmbientSoundInstance.class,
    UnderwaterAmbientSoundInstances.SubSound.class
})
public abstract class PlanetPlayerSoundMixin implements PlanetSoundOwner {
    // NeoForge 1.21.1 uses these exact named fields; Mixin forbids remappable multi-target shadows.
    @Shadow(remap = false)
    @Final
    private LocalPlayer player;

    @Override
    public Object starfold$soundOwner() {
        return player;
    }
}
