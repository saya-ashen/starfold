package dev.starfold.ship;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.GroundTopology;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;
import dev.starfold.universe.SystemChunkGenerator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Server-only definitions; only the helm's aggregate instrument readings cross the network. */
public final class ThrusterFuel extends SimplePreparableReloadListener<List<FuelDefinition>> {
    private static List<FuelDefinition> definitions = List.of();
    private static final Map<ServerSubLevel, Tanks> bodies = new WeakHashMap<>();
    private static final Direction[] SIDES = Direction.values();
    private static final TagKey<Block> DRIVES =
            TagKey.create(Registries.BLOCK, ResourceLocation.parse("starfold:interstellar_drives"));

    private static final class Tanks {
        long scanned = Long.MIN_VALUE, tick = Long.MIN_VALUE;
        List<BlockPos> positions = List.of();
        final List<Supply> supplies = new ArrayList<>();
        int cursor;
        boolean restricted;
        double currentMass;
        long drivesScanned = Long.MIN_VALUE;
        boolean drive;
        dev.ryanhcode.sable.api.physics.mass.MassTracker tracker;
        FuelMass mass = new FuelMass();
        // Ordinary prepaid impulse stays at home; interstellar credit remains usable everywhere.
        final FuelBudget ordinary = new FuelBudget(), interstellar = new FuelBudget();
    }

    private record Supply(
            IFluidHandler handler, FluidStack fluid, FuelDefinition definition, boolean interstellar) {
        double impulse() { return definition.impulse(); }
    }

    static void initialize() {
        NeoForge.EVENT_BUS.addListener(
                (AddReloadListenerEvent e) -> e.addListener(new ThrusterFuel()));
        NeoForge.EVENT_BUS.addListener(
                (ServerStoppedEvent e) -> {
                    definitions = List.of();
                    bodies.clear();
                });
    }

    @Override
    protected List<FuelDefinition> prepare(ResourceManager manager, ProfilerFiller profiler) {
        var loaded = new ArrayList<FuelDefinition>();
        // Reject malformed files instead of silently turning a fuel-requiring ship into a free one.
        for (var entry : manager.listResources(
                        "starfold/thruster_fuel", id -> id.getPath().endsWith(".json"))
                .entrySet())
            try (var reader = entry.getValue().openAsReader()) {
                loaded.add(FuelDefinition.parse(JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (IOException | RuntimeException e) {
                throw new JsonParseException("Invalid thruster fuel " + entry.getKey(), e);
            }
        return List.copyOf(loaded);
    }

    @Override
    protected void apply(
            List<FuelDefinition> loaded, ResourceManager manager, ProfilerFiller profiler) {
        definitions = loaded;
        // Keep applied mass so a datapack reload subtracts it instead of double-counting it.
        for (var state : bodies.values()) {
            state.scanned = state.tick = state.drivesScanned = Long.MIN_VALUE;
            state.ordinary.take(Double.POSITIVE_INFINITY);
            state.interstellar.take(Double.POSITIVE_INFINITY);
        }
    }

    private static FuelDefinition definition(FluidStack fluid, boolean restricted) {
        FuelDefinition best = null;
        for (var d : definitions)
            if ((!restricted || d.interstellar())
                    && (d.tag() ? fluid.is(TagKey.create(Registries.FLUID, d.fluid()))
                            : BuiltInRegistries.FLUID.getKey(fluid.getFluid()).equals(d.fluid()))
                    && (best == null || d.impulse() > best.impulse())) best = d;
        return best;
    }

    private static List<IFluidHandler> handlers(ServerSubLevel body, BlockPos pos) {
        var unsided = body.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (unsided != null) return List.of(unsided);
        var result = new ArrayList<IFluidHandler>();
        for (var side : SIDES) {
            var handler = body.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
            if (handler != null) result.add(handler);
        }
        return result;
    }

    static boolean outside(GroundEphemeris.Model home, Point position) {
        return position.sub(home.origin()).length() > home.extent();
    }

    static boolean outside(ServerSubLevel body) {
        var level = (ServerLevel) body.getLevel();
        if (!(level.getChunkSource().getGenerator() instanceof SystemChunkGenerator system)
                || !system.beyond(level)) return false;
        var clock = GroundClock.get(level).clock;
        var at = body.logicalPose().position();
        var position = new Point(at.x(), at.y(), at.z());
        if (Space.holds(at.x(), at.z()))
            position = ShipHost.get().surroundingsFrame(level)
                    .universe(position, clock.model, clock.tick());
        else {
            var access = PlanetAccess.of(level);
            int planet = access.storagePlanet(at.x(), at.z());
            // A hull anchor is not entity feet: do not add GroundTopology.body's 0.9 offset.
            var pose = new PlanetShape.Pose(Face.VALUES[access.face(at.x())], access.chart(position));
            position = clock.model.at(planet, clock.tick()).world(
                    GroundTopology.planet(PlanetShape.of(planet), pose));
        }
        return outside(system.homeModel(), position);
    }

    static void changed(ServerLevel level, BlockPos pos) {
        if (Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel body) {
            var state = bodies.get(body);
            if (state != null) state.scanned = state.drivesScanned = Long.MIN_VALUE;
        }
    }

    static boolean driveAllowed(boolean outside, boolean required, boolean present) {
        return !outside || !required || present;
    }

    static boolean missingDrive(ServerSubLevel body) {
        boolean required = BuiltInRegistries.BLOCK.getTag(DRIVES)
                .map(tag -> tag.size() > 0).orElse(false);
        if (!required || !outside(body)) return false;
        var state = bodies.computeIfAbsent(body, b -> new Tanks());
        long tick = body.getLevel().getGameTime();
        if (state.drivesScanned == Long.MIN_VALUE || tick - state.drivesScanned >= 100) {
            state.drivesScanned = tick;
            state.drive = hasDrive(body);
        }
        return !driveAllowed(true, required, state.drive);
    }

    private static boolean hasDrive(ServerSubLevel body) {
        for (var holder : body.getPlot().getLoadedChunks()) {
            var chunk = holder.getChunk();
            var sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                var section = sections[i];
                if (!section.maybeHas(block -> block.is(DRIVES))) continue;
                for (int x = 0; x < 16; x++)
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++) {
                            if (!section.getBlockState(x, y, z).is(DRIVES)) continue;
                            var pos = new BlockPos(
                                    chunk.getPos().getMinBlockX() + x,
                                    chunk.getMinBuildHeight() + i * 16 + y,
                                    chunk.getPos().getMinBlockZ() + z);
                            if (Sable.HELPER.getContaining(body.getLevel(), pos) == body) return true;
                        }
            }
        }
        return false;
    }

    private static Tanks tanks(ServerSubLevel body) {
        var state = bodies.computeIfAbsent(body, b -> new Tanks());
        long tick = body.getLevel().getGameTime();
        boolean restricted = definitions.stream().anyMatch(FuelDefinition::interstellar)
                && FuelDefinition.restricted(definitions, outside(body));
        if (state.tick == tick && state.restricted == restricted) return state;
        state.tick = tick;
        state.restricted = restricted;
        positions(body, state, tick);
        state.supplies.clear();
        state.cursor = 0;
        var seen = Collections.newSetFromMap(new IdentityHashMap<IFluidHandler, Boolean>());
        for (var pos : state.positions) {
            if (Sable.HELPER.getContaining(body.getLevel(), pos) != body) continue;
            for (var handler : handlers(body, pos)) {
                if (!seen.add(handler)) continue;
                var fluids = new ArrayList<FluidStack>();
                for (int i = 0; i < handler.getTanks(); i++) {
                    var fluid = handler.getFluidInTank(i);
                    if (fluid.isEmpty()) continue;
                    var same = fluids.stream()
                            .filter(f -> FluidStack.isSameFluidSameComponents(f, fluid)).findFirst();
                    if (same.isPresent()) same.get().grow(fluid.getAmount());
                    else fluids.add(fluid.copy());
                }
                for (var fluid : fluids) {
                    var definition = definition(fluid, state.restricted);
                    if (definition != null) {
                        // Only extractable fuel counts; sided input-only tanks cannot power a nozzle.
                        var available = handler.drain(fluid.copy(), IFluidHandler.FluidAction.SIMULATE);
                        if (!available.isEmpty())
                            state.supplies.add(new Supply(handler, available.copy(), definition,
                                    definition(fluid, true) != null));
                    }
                }
            }
        }
        state.supplies.sort(Comparator.comparingDouble(Supply::impulse).reversed());
        state.currentMass = body.getMassTracker().getMass() - state.mass.total()
                + sampleMass(body, state, tick).values().stream().mapToDouble(Double::doubleValue).sum()
                + state.ordinary.mass() + state.interstellar.mass();
        return state;
    }

    private static void positions(ServerSubLevel body, Tanks state, long tick) {
        if (state.scanned == Long.MIN_VALUE || tick - state.scanned >= 100) {
            state.scanned = tick;
            var positions = new ArrayList<BlockPos>();
            for (var holder : body.getPlot().getLoadedChunks())
                for (var entity : holder.getChunk().getBlockEntities().values())
                    if (!entity.isRemoved()
                            && Sable.HELPER.getContaining(body.getLevel(), entity.getBlockPos()) == body
                            && !handlers(body, entity.getBlockPos()).isEmpty())
                        positions.add(entity.getBlockPos().immutable());
            positions.sort(BlockPos::compareTo);
            state.positions = positions;
        }
    }

    /** Called before Sable merges/uploads mass, even for unpowered, unpiloted hulls. */
    static void updateMass(ServerSubLevel body) {
        if (definitions.isEmpty() && !bodies.containsKey(body)) return;
        var state = bodies.computeIfAbsent(body, b -> new Tanks());
        var tracker = body.getSelfMassTracker();
        if (state.tracker != tracker) {
            // Sable rebuilds trackers on load, assembly and splitting; the new one is dry.
            state.tracker = tracker;
            state.mass = new FuelMass();
            state.scanned = state.tick = Long.MIN_VALUE;
        }
        long tick = body.getLevel().getGameTime();
        if (!state.mass.due(tick)) return;
        var sampled = sampleMass(body, state, tick);
        state.mass.update(tick, sampled, (pos, mass) -> tracker.addBlockMass(
                body.getLevel(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                pos, mass, null));
    }

    private static Map<BlockPos, Double> sampleMass(ServerSubLevel body, Tanks state, long tick) {
        positions(body, state, tick);
        var sampled = new java.util.HashMap<BlockPos, Double>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<IFluidHandler, Boolean>());
        for (var pos : state.positions) {
            if (Sable.HELPER.getContaining(body.getLevel(), pos) != body) continue;
            for (var handler : handlers(body, pos)) {
                if (!seen.add(handler)) continue;
                for (int i = 0; i < handler.getTanks(); i++) {
                    var fluid = handler.getFluidInTank(i);
                    if (fluid.isEmpty()) continue;
                    // Stored fuel weighs something even if input-only or ineligible outside home.
                    var definition = definition(fluid, false);
                    if (definition != null && definition.mass() > 0)
                        sampled.merge(pos, definition.fuelMass(fluid.getAmount()), Double::sum);
                }
            }
        }
        return sampled;
    }

    /** Burn order matches propulsion, including prepaid fractions and subsequent fuel types. */
    static double deltaV(ServerSubLevel body) {
        if (!FuelDefinition.required(definitions)) return -1;
        if (missingDrive(body)) return 0;
        var state = tanks(body);
        double result = prepaidDeltaV(state);
        double mass = state.currentMass - state.interstellar.mass()
                - (state.restricted ? 0 : state.ordinary.mass());
        for (var supply : state.supplies) {
            double burned = supply.definition().fuelMass(supply.fluid().getAmount());
            result += supply.definition().deltaV(mass, supply.fluid().getAmount());
            mass -= burned;
        }
        return result;
    }

    /** Share the hull's discovery cache; input-only tanks may receive skim even if they cannot fuel a nozzle. */
    static int fill(ServerSubLevel body, FluidStack fluid) {
        var state = tanks(body);
        int remaining = fluid.getAmount();
        var seen = Collections.newSetFromMap(new IdentityHashMap<IFluidHandler, Boolean>());
        for (var pos : state.positions) {
            if (Sable.HELPER.getContaining(body.getLevel(), pos) != body) continue;
            for (var handler : handlers(body, pos)) {
                if (!seen.add(handler)) continue;
                remaining -= handler.fill(
                        fluid.copyWithAmount(remaining), IFluidHandler.FluidAction.EXECUTE);
                if (remaining == 0) break;
            }
            if (remaining == 0) break;
        }
        // A nozzle or HUD may already have sampled supplies earlier in this same tick.
        state.tick = Long.MIN_VALUE;
        return fluid.getAmount() - remaining;
    }

    private static double prepaidDeltaV(Tanks state) {
        double mass = state.currentMass;
        double result = state.interstellar.deltaV(mass, state.interstellar.credit());
        mass -= state.interstellar.mass();
        if (!state.restricted) result += state.ordinary.deltaV(mass, state.ordinary.credit());
        return result;
    }

    /** Live propellant mass, including fractions bought but not yet ejected. */
    public static double mass(ServerSubLevel body) {
        return FuelDefinition.required(definitions) ? tanks(body).currentMass
                : body.getMassTracker().getMass();
    }

    /** Requested and returned change are blocks/s; steering alone never calls this. */
    public static double change(ServerSubLevel body, double deltaV) {
        return burn(body, deltaV, true);
    }

    /** Sable still accepts impulse; space converts exact rocket Δv back using its snapshot mass. */
    static double draw(ServerSubLevel body, double requested) {
        if (!FuelDefinition.required(definitions)) return missingDrive(body) ? 0 : requested;
        if (requested == 0) return 0;
        var at = body.logicalPose().position();
        if (!Space.holds(at.x(), at.z())) return burn(body, requested, false);
        return burn(body, requested, false, true) * body.getMassTracker().getMass();
    }

    private static double burn(ServerSubLevel body, double requested, boolean velocity) {
        return burn(body, requested, velocity, velocity);
    }

    /** Buy whole mB, but burn prepaid mass only with its impulse, not at purchase time. */
    private static double burn(ServerSubLevel body, double requested, boolean velocity, boolean space) {
        if (missingDrive(body)) return 0;
        if (!FuelDefinition.required(definitions))
            return space && !velocity ? requested / body.getMassTracker().getMass() : requested;
        if (requested == 0) return 0;
        var state = tanks(body);
        double needed = requested, gained = 0;
        for (var budget : state.restricted ? List.of(state.interstellar)
                : List.of(state.interstellar, state.ordinary)) {
            double impulse = budget.take(velocity ? budget.impulseFor(state.currentMass, needed) : needed);
            double dv = budget.deltaV(state.currentMass, impulse);
            state.currentMass -= impulse * budget.massPerImpulse();
            gained += dv;
            needed = Math.max(0, needed - (velocity ? dv : impulse));
        }
        while (needed > 1e-12 && state.cursor < state.supplies.size()) {
            var supply = state.supplies.get(state.cursor);
            var budget = supply.interstellar() ? state.interstellar : state.ordinary;
            budget.buy(0, supply.impulse(), supply.definition().mass());
            double wanted = velocity ? budget.impulseFor(state.currentMass, needed) : needed;
            int amount = (int) Math.min(supply.fluid().getAmount(),
                    Math.ceil(FuelDefinition.consumption(wanted, 1, supply.impulse())));
            var drained =
                    supply.handler().drain(
                            supply.fluid().copyWithAmount(amount), IFluidHandler.FluidAction.EXECUTE);
            int used = drained.getAmount();
            supply.fluid().shrink(used);
            budget.buy(used, supply.impulse(), supply.definition().mass());
            double impulse = budget.take(wanted);
            double dv = budget.deltaV(state.currentMass, impulse);
            state.currentMass -= impulse * budget.massPerImpulse();
            gained += dv;
            needed = Math.max(0, needed - (velocity ? dv : impulse));
            if (used < amount || supply.fluid().isEmpty()) state.cursor++;
        }
        return space ? gained : requested - needed;
    }

    /** -1 means fuel is not in effect. No definition registry needs syncing to clients. */
    static double remaining(ServerSubLevel body) {
        if (missingDrive(body)) return 0;
        if (!FuelDefinition.required(definitions)) return -1;
        var state = tanks(body);
        double remaining = state.interstellar.remaining();
        if (!state.restricted) remaining += state.ordinary.remaining();
        for (var supply : state.supplies) remaining += supply.fluid().getAmount();
        return remaining;
    }
}
