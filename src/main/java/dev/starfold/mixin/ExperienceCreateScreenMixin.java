package dev.starfold.mixin;

import dev.starfold.ship.StarterShip;
import dev.starfold.worldgen.SystemChunkGenerator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reject only the selected starter preset, not registry loading of unused presets. */
@Mixin(CreateWorldScreen.class)
public abstract class ExperienceCreateScreenMixin {
    @Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
    private void starfold$requireExperienceMods(CallbackInfo ci) {
        var screen = (CreateWorldScreen) (Object) this;
        if (!StarterShip.missingDependencies()) return;
        boolean starter =
                screen
                        .getUiState()
                        .getSettings()
                        .selectedDimensions()
                        .dimensions()
                        .values()
                        .stream()
                        .anyMatch(
                                stem ->
                                        stem.generator() instanceof SystemChunkGenerator system
                                                && system.starterShip().isPresent());
        if (!starter) return;
        ci.cancel();
        var mc = Minecraft.getInstance();
        mc.setScreen(
                new AlertScreen(
                        () -> mc.setScreen(screen),
                        Component.translatable("screen.starfold.experience_unavailable"),
                        Component.translatable("screen.starfold.experience_dependencies")));
    }
}
