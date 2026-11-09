package dev.starfold.worlds.mixin;

import dev.starfold.api.PlanetContent;
import dev.starfold.universe.SystemChunkGenerator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fresh worlds start on a Starfold preset; reject only the selected starter preset, not registry loading of unused presets. */
@Mixin(CreateWorldScreen.class)
public abstract class ExperienceCreateScreenMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void starfold$defaultPreset(CallbackInfo ci) {
        var ui = ((CreateWorldScreen) (Object) this).getUiState();
        var current = ui.getWorldType().preset();
        // Only a fresh vanilla default; re-created worlds keep their own preset.
        if (current == null || !current.is(WorldPresets.NORMAL)) return;
        var wanted = ResourceLocation.parse(
                PlanetContent.get().missingStarterDependencies() ? "starfold:native_universe" : "starfold_worlds:experience");
        ui.getNormalPresetList().stream()
                .filter(entry -> entry.preset() != null && entry.preset().is(wanted))
                .findFirst()
                .ifPresent(ui::setWorldType);
    }

    @Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
    private void starfold$requireExperienceMods(CallbackInfo ci) {
        var screen = (CreateWorldScreen) (Object) this;
        if (!PlanetContent.get().missingStarterDependencies()) return;
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
                                                && system.startsWithShip());
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
