package dev.starfold.ship;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Server-thread table. A reload replaces the table and its state cache together. */
final class HullPressure extends SimplePreparableReloadListener<List<HullPressureDefinition>> {
    static final double DEFAULT = .5;
    private static Table table = new Table(List.of());

    static final class Table {
        private record Rule(Predicate<BlockState> matches, double pressure) {}
        private final List<Rule> rules;
        private final Map<BlockState, Double> cache = new IdentityHashMap<>();

        Table(List<HullPressureDefinition> definitions) {
            rules = definitions.stream().map(d -> {
                if (d.tag()) {
                    var tag = TagKey.create(Registries.BLOCK, d.blocks());
                    return new Rule(state -> state.is(tag), d.pressure());
                }
                return new Rule(state -> BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(d.blocks()),
                        d.pressure());
            }).toList();
        }

        double get(BlockState state) {
            return cache.computeIfAbsent(state, s -> {
                // Overlapping definitions choose the highest matching value, independent of file order.
                double best = 0;
                for (var rule : rules)
                    if (rule.matches().test(s)) best = Math.max(best, rule.pressure());
                return best == 0 ? DEFAULT : best;
            });
        }
    }

    static boolean damaged(BlockState state, double pressure) {
        return pressure >= table.get(state);
    }

    /** Preserve D1 cadence; weaker datapack materials can also fail in the outer layer. */
    static int damageInterval(double pressure) {
        double minimum = DEFAULT;
        for (var rule : table.rules) minimum = Math.min(minimum, rule.pressure());
        return pressure < minimum ? 0 : dev.starfold.chart.GasGiant.damageInterval(Math.max(DEFAULT, pressure));
    }

    static void initialize() {
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(new HullPressure()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> table = new Table(List.of()));
    }

    @Override
    protected List<HullPressureDefinition> prepare(ResourceManager manager, ProfilerFiller profiler) {
        var loaded = new ArrayList<HullPressureDefinition>();
        for (var entry : manager.listResources("starfold/hull_pressure", id -> id.getPath().endsWith(".json")).entrySet())
            try (var reader = entry.getValue().openAsReader()) {
                loaded.add(HullPressureDefinition.parse(JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (IOException | RuntimeException e) {
                throw new JsonParseException("Invalid hull pressure " + entry.getKey(), e);
            }
        return List.copyOf(loaded);
    }

    @Override
    protected void apply(List<HullPressureDefinition> loaded, ResourceManager manager, ProfilerFiller profiler) {
        table = new Table(loaded);
    }
}
