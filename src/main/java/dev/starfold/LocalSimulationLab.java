package dev.starfold;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;

@Mod("starfold")
public final class LocalSimulationLab {
    public LocalSimulationLab(IEventBus modBus) {
        LocalNet.initialize(modBus);
        NativeGroundServer.initialize(modBus);
    }
}
