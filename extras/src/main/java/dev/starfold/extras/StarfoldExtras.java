package dev.starfold.extras;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.starfold.api.PlanetContent;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.common.Mod;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

@Mod("starfold_extras")
public final class StarfoldExtras implements PlanetContent {
    private static final Map<SystemChunkGenerator, StarterShipOption> ships =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    public StarfoldExtras() {
        PlanetContent.install(this);
        StarterShip.initialize();
    }

    public static Optional<StarterShipOption> starterShip(SystemChunkGenerator generator) {
        return Optional.ofNullable(ships.get(generator));
    }

    private static SystemChunkGenerator withStarter(SystemChunkGenerator generator, Optional<StarterShipOption> ship) {
        ship.ifPresent(option -> ships.put(generator, option));
        return generator;
    }

    @Override public MapCodec<SystemChunkGenerator> generatorCodec(MapCodec<SystemChunkGenerator> core) {
        return RecordCodecBuilder.mapCodec(i -> i.group(
                core.forGetter(g -> g),
                StarterShipOption.CODEC.optionalFieldOf("starter_ship").forGetter(StarfoldExtras::starterShip))
                .apply(i, StarfoldExtras::withStarter));
    }

    @Override public Spawn home(ServerLevel level, int face) {
        return new Spawn(StarterShip.home(level, face), StarterShip.homeYaw(level));
    }
    @Override public boolean missingStarterDependencies() { return StarterShip.missingDependencies(); }
    @Override public boolean startsWithShip(SystemChunkGenerator generator) { return starterShip(generator).isPresent(); }
    @Override public boolean overPad(ServerLevel level, ServerSubLevel body) { return StarterShip.overPad(level, body); }
    @Override public Point launchCentre(ServerLevel level, ServerSubLevel body) { return StarterShip.launchCentre(level, body); }
}
