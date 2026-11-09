package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.starfold.planet.PlanetSoundClient;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientLevel.class)
public abstract class PlanetLevelSoundMixin {
    @WrapOperation(
            method = "playSound",
            at =
                    @At(
                            value = "NEW",
                            target =
                                    "(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFLnet/minecraft/util/RandomSource;DDD)Lnet/minecraft/client/resources/sounds/SimpleSoundInstance;"))
    private SimpleSoundInstance starfold$provenance(
            SoundEvent event,
            SoundSource source,
            float volume,
            float pitch,
            RandomSource random,
            double x,
            double y,
            double z,
            Operation<SimpleSoundInstance> original) {
        return PlanetSoundClient.created(
                original.call(event, source, volume, pitch, random, x, y, z));
    }
}
