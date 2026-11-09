package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.starfold.planet.PlanetSoundClient;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep the exact local emitter across the vanilla call that otherwise erases its identity. */
@Mixin(LocalPlayer.class)
public abstract class PlanetLocalSoundMixin {
    @WrapOperation(
            method = {"playSound", "playNotifySound", "updateIsUnderwater"},
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"))
    private void starfold$localSound(
            Level level,
            double x,
            double y,
            double z,
            SoundEvent event,
            SoundSource source,
            float volume,
            float pitch,
            boolean delayed,
            Operation<Void> original) {
        PlanetSoundClient.withOwner(
                this, () -> original.call(level, x, y, z, event, source, volume, pitch, delayed));
    }
}
