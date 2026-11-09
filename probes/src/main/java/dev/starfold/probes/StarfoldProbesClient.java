package dev.starfold.probes;

import dev.starfold.DevelopmentHooks;
import dev.starfold.dev.DevFeelProbe;
import dev.starfold.dev.DevPlanetSoundProbe;
import dev.starfold.dev.DevProbe;
import dev.starfold.planet.PlanetSoundClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = "starfold_probes", dist = Dist.CLIENT)
public final class StarfoldProbesClient {
    public StarfoldProbesClient() {
        DevelopmentHooks.beforeKeys = () -> DevFeelProbe.prepare(Minecraft.getInstance());
        PlanetSoundClient.observer = new PlanetSoundClient.Observer() {
            @Override public boolean enabled() { return DevPlanetSoundProbe.enabled(); }
            @Override public void created(SoundInstance sound) { DevPlanetSoundProbe.created(sound); }
            @Override public void scaled(SoundInstance sound, double density, boolean self, float scale) { DevPlanetSoundProbe.scaled(sound, density, self, scale); }
            @Override public void calculated(SoundInstance sound, float raw, float result, String phase) { DevPlanetSoundProbe.calculated(sound, raw, result, phase); }
            @Override public void refreshing(boolean value) { DevPlanetSoundProbe.refreshing(value); }
        };
        DevProbe.initialize();
    }
}
