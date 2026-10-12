package dev.starfold;

import dev.starfold.network.StarfoldNetwork;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("starfold")
public final class Starfold {
    public Starfold(IEventBus modBus) {
        StarfoldNetwork.initialize(modBus);
        StarfoldServer.initialize(modBus);
    }
}
