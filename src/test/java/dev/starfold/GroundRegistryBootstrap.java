package dev.starfold;

import dev.starfold.flight.FlightPermit;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.PlanetCore;
import dev.starfold.ship.AircraftDock;

import org.junit.jupiter.api.extension.*;

/** Vanilla/mod registries only, no client/server/game/window startup. */
public final class GroundRegistryBootstrap implements BeforeAllCallback {
    private static boolean own;

    /** NeoForge's JUnit launcher bootstraps vanilla and fires DeferredRegister events before discovery. */
    private static synchronized void own() {
        if (own) return;
        if (!net.neoforged.neoforge.internal.CommonModLoader.areRegistriesLoaded())
            throw new IllegalStateException("NeoForge JUnit registries not loaded");
        for (var block :
                new net.minecraft.world.level.block.Block[] {
                    Anomaly.SHELL_BLOCK,
                    Anomaly.GLOW_BLOCK,
                    Anomaly.GROUND_BLOCK,
                    Anomaly.RING_BLOCK,
                    PlanetCore.SHELL_BLOCK
                })
            if (block == null
                    || !net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(block)
                            .getNamespace()
                            .equals("starfold"))
                throw new IllegalStateException("Mod block not registered");
        if (FlightPermit.PACK == null
                || !net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(FlightPermit.PACK)
                        .getNamespace()
                        .equals("starfold"))
            throw new IllegalStateException("Mod item not registered");
        if (AircraftDock.block == null
                || AircraftDock.type == null
                || !net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(AircraftDock.block)
                        .equals(
                                net.minecraft.resources.ResourceLocation.parse(
                                        "starfold:aircraft_dock")))
            throw new IllegalStateException("Aircraft dock not registered");
        own = true;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        // Jupiter autodetection may instantiate the extension in Gradle's parent loader.
        // Always inspect the registries in the test's actual NeoForge transforming loader.
        var bootstrap =
                Class.forName(
                        getClass().getName(),
                        true,
                        context.getRequiredTestClass().getClassLoader());
        var method = bootstrap.getDeclaredMethod("own");
        method.setAccessible(true);
        method.invoke(null);
    }
}
