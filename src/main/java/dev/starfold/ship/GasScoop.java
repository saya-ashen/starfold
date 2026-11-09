package dev.starfold.ship;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.starfold.chart.GasPressure;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.fluids.FluidStack;

import java.io.IOException;
import java.util.Optional;

/** A passive intake, serviced once per game tick rather than once per physics substep. */
final class GasScoop extends BlockEntity {
    private static GasSkimDefinition definition;
    private double fraction;

    GasScoop(BlockPos pos, BlockState state) {
        super(Thrusters.scoopType, pos, state);
    }

    static void initialize() {
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(new Definitions()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> definition = null);
    }

    private static final class Definitions extends SimplePreparableReloadListener<Optional<GasSkimDefinition>> {
        @Override
        protected Optional<GasSkimDefinition> prepare(ResourceManager manager, ProfilerFiller profiler) {
            GasSkimDefinition loaded = null;
            for (var entry : manager.listResources("starfold/gas_skim", id -> id.getPath().endsWith(".json")).entrySet()) {
                if (loaded != null) throw new JsonParseException("Only one global gas skim definition is supported");
                try (var reader = entry.getValue().openAsReader()) {
                    loaded = GasSkimDefinition.parse(JsonParser.parseReader(reader).getAsJsonObject());
                    var fluid = BuiltInRegistries.FLUID.get(loaded.fluid());
                    if (!BuiltInRegistries.FLUID.containsKey(loaded.fluid()) || fluid == Fluids.EMPTY)
                        throw new JsonParseException("Unknown or empty skim fluid " + loaded.fluid());
                } catch (IOException | RuntimeException e) {
                    throw new JsonParseException("Invalid gas skim " + entry.getKey(), e);
                }
            }
            return Optional.ofNullable(loaded);
        }

        @Override
        protected void apply(Optional<GasSkimDefinition> loaded, ResourceManager manager, ProfilerFiller profiler) {
            definition = loaded.orElse(null);
        }
    }

    static void tick(Level world, BlockPos pos, BlockState state, GasScoop scoop) {
        if (definition == null || !PlanetLevels.is(world)) {
            scoop.fraction = 0;
            return;
        }
        if (!(Sable.HELPER.getContaining(world, pos) instanceof ServerSubLevel body)) {
            scoop.fraction = 0;
            return;
        }
        var at = body.logicalPose().position();
        if (!Space.holds(at.x(), at.z())) {
            scoop.fraction = 0;
            return;
        }
        var level = (ServerLevel) world;
        var clock = GroundClock.get(level).clock;
        var point = body.logicalPose().transformPosition(
                new org.joml.Vector3d(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5),
                new org.joml.Vector3d());
        var universe = ShipHost.get().surroundingsFrame(level).universe(
                new Point(point.x(), point.y(), point.z()), clock.model, clock.tick());
        // The same pressure field as GasShipDamage, at the intake itself (no conservative hull padding).
        double pressure = GasPressure.snapshot(clock.model, clock.tick()).worst(universe, 0)
                .map(GasPressure.Reading::pressure).orElse(0.0);
        double amount = scoop.fraction + definition.amount(pressure);
        int whole = (int) amount;
        scoop.fraction = amount - whole;
        if (whole > 0 && ThrusterFuel.fill(body,
                new FluidStack(BuiltInRegistries.FLUID.get(definition.fluid()), whole)) > 0)
            ShipHost.get().gasCollected(body);
        // Full/incompatible tanks discard the uncollected whole mB, never bank a future burst.
    }
}
