package dev.starfold.api;

import com.mojang.serialization.MapCodec;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.universe.PlanetSpawn;
import dev.starfold.universe.SystemChunkGenerator;

import net.minecraft.server.level.ServerLevel;

/** Optional starter content and an independent terrain spawn policy. */
public interface PlanetContent {
    final class Registration {
        private static final PlanetContent NO_STARTER = new PlanetContent() {};
        private static volatile PlanetContent starter = NO_STARTER;
        private static volatile HomeSpawn terrain = PlanetContent::terrainHome;

        private Registration() {}
    }

    static PlanetContent starter() {
        return Registration.starter;
    }

    /** Passing null restores the core defaults. */
    static void installStarter(PlanetContent starter) {
        Registration.starter = starter == null ? Registration.NO_STARTER : starter;
    }

    /** Passing null restores the ordinary terrain spawn. */
    static HomeSpawn installHomeSpawn(HomeSpawn terrain) {
        var previous = Registration.terrain;
        Registration.terrain = terrain == null ? PlanetContent::terrainHome : terrain;
        return previous;
    }

    record Spawn(Point position, float yaw) {}

    @FunctionalInterface
    interface HomeSpawn {
        Spawn home(ServerLevel level, int face);
    }

    /** Extras owns fresh home entry when installed, including presets without a starter ship. */
    static Spawn home(ServerLevel level, int face) {
        var starter = starter();
        return starter == Registration.NO_STARTER
                ? Registration.terrain.home(level, face)
                : starter.starterHome(level, face);
    }

    static Spawn terrainHome(ServerLevel level, int face) {
        return new Spawn(PlanetSpawn.site(level, 0, face), 0);
    }

    default Spawn starterHome(ServerLevel level, int face) {
        return terrainHome(level, face);
    }

    default boolean startsWithShip(SystemChunkGenerator generator) {
        return false;
    }

    default boolean missingStarterDependencies() {
        return false;
    }

    default boolean overPad(ServerLevel level, ServerSubLevel body) {
        return false;
    }

    default Point launchCentre(ServerLevel level, ServerSubLevel body) {
        return null;
    }

    default MapCodec<SystemChunkGenerator> generatorCodec(MapCodec<SystemChunkGenerator> core) {
        return core;
    }
}
