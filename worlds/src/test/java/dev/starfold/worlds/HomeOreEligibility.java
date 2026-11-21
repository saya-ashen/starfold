package dev.starfold.worlds;

import com.google.gson.JsonArray;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetMinerals;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetUnderground;
import dev.starfold.worldgen.plan.terrain.TerrainPlan;

import net.minecraft.resources.ResourceLocation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

/** Test-only access to the underground noise kernel; no blocks are registered or substituted.
 * This lives beside HomeSettlementTest to keep the entire offline probe in its owned directory.
 * Unknown mod block names remain names: the result is eligibility, never a generated BlockState.
 */
public final class HomeOreEligibility {
    // Keep the helper in the Worlds package: a split worldgen package cannot resolve in JPMS.
    // The automatic mod module permits reflective test access to this package-private pure kernel.
    private static final MethodHandle NOISE = noiseKernel();

    private static MethodHandle noiseKernel() {
        try {
            var method =
                    PlanetTerrain.class.getDeclaredMethod(
                            "noise", Point.class, double.class, long.class);
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static double noise(PlanetTerrain terrain, Point point, long salt) {
        try {
            return (double) NOISE.invokeExact(terrain, point, 1.0 / 5, salt);
        } catch (Throwable failure) {
            throw new AssertionError("Cannot evaluate production underground noise", failure);
        }
    }

    private record Ore(String id, int top, int bottom, double rarity) {}

    private final List<Ore> ores;

    public HomeOreEligibility(JsonArray input) {
        var parsed = new ArrayList<Ore>();
        for (var element : input) {
            var json = element.getAsJsonObject();
            String id = ResourceLocation.parse(json.get("block").getAsString()).toString();
            double from = json.has("from") ? json.get("from").getAsDouble() : 0;
            double to = json.has("to") ? json.get("to").getAsDouble() : 1;
            double rarity = json.get("rarity").getAsDouble();
            if (!(from >= 0 && from <= to && to <= 1 && rarity >= .5 && rarity <= .999))
                throw new IllegalArgumentException("Invalid pack ore " + json);
            parsed.add(new Ore(id, PlanetMinerals.layer(from), PlanetMinerals.layer(to), rarity));
        }
        ores = List.copyOf(parsed);
    }

    /** Mirrors only PlanetUnderground's first-winning-ore predicate, including caves and strata shift.
     * Callers exclude columns with local volumes/roads/anomaly and the four-block material cap.
     * The test cross-checks the registered prefix against actual production column states.
     */
    public String winner(PlanetTerrain terrain, Face face, int u, int v, int ground, int y) {
        if (y > ground - PlanetTerrain.SURFACE_DEPTH || y <= terrain.floor()) return null;
        var underground = new PlanetUnderground(terrain);
        Point point = underground.sample(terrain.unit(face, u + .5, v + .5), y);
        if (underground.cave(point, ground, y)) return null;
        int shift =
                (int)
                        Math.round(
                                terrain.terrainPlan()
                                        .interpolate(
                                                TerrainPlan.STRATA_SHIFT, face, u + .5, v + .5));
        int layer = terrain.layer(y - shift);
        for (int i = 0; i < ores.size(); i++) {
            var ore = ores.get(i);
            if (layer <= ore.top
                    && layer >= ore.bottom
                    && noise(terrain, point, 201L + i) > ore.rarity) return ore.id;
        }
        return null;
    }
}
