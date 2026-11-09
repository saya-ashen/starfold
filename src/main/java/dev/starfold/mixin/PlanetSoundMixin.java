package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.starfold.planet.PlanetSoundClient;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SoundEngine.class)
public abstract class PlanetSoundMixin {
    @WrapOperation(
            method = "play",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/sounds/SoundEngine;calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"))
    private float starfold$initialVolume(
            SoundEngine engine,
            float volume,
            SoundSource source,
            Operation<Float> original,
            SoundInstance sound) {
        return starfold$muffle(engine, volume, source, original, sound, "initial-play");
    }

    @WrapOperation(
            method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/sounds/SoundEngine;calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"))
    private float starfold$instanceVolume(
            SoundEngine engine,
            float volume,
            SoundSource source,
            Operation<Float> original,
            SoundInstance sound) {
        return starfold$muffle(
                engine, volume, source, original, sound, "instance-tick-or-external-refresh");
    }

    /** Initial play and the instance overload still share exactly one scale before vanilla clamp. */
    @Unique
    private float starfold$muffle(
            SoundEngine engine,
            float volume,
            SoundSource source,
            Operation<Float> original,
            SoundInstance sound,
            String phase) {
        float result = original.call(engine, volume * PlanetSoundClient.scale(sound), source);
        if (PlanetSoundClient.observer.enabled())
            PlanetSoundClient.observer.calculated(sound, volume, result, phase);
        return result;
    }
}
