package dev.starfold;

import dev.starfold.ground.GroundServer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("starfold")
public final class Starfold {
    public Starfold(IEventBus modBus) {
        StarfoldNetwork.initialize(modBus);
        GroundServer.initialize(modBus);
    }
}
