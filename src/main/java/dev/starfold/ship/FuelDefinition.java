package dev.starfold.ship;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.function.Predicate;

/** Impulse is in Sable force-seconds per mB (the same force unit as Thrusters.THRUST). */
record FuelDefinition(ResourceLocation fluid, boolean tag, double impulse, boolean interstellar, double mass) {
    static FuelDefinition parse(JsonObject json) {
        var fluid = json.get("fluid");
        var impulse = json.get("impulse");
        if (fluid == null
                || !fluid.isJsonPrimitive()
                || !fluid.getAsJsonPrimitive().isString()
                || impulse == null
                || !impulse.isJsonPrimitive()
                || !impulse.getAsJsonPrimitive().isNumber())
            throw new JsonParseException("Expected fluid string and positive numeric impulse");
        String name = fluid.getAsString();
        boolean tag = name.startsWith("#");
        var id = ResourceLocation.tryParse(tag ? name.substring(1) : name);
        double value = impulse.getAsDouble();
        if (id == null || id.getPath().isEmpty() || !(value > 0) || !Double.isFinite(value))
            throw new JsonParseException("Invalid fluid id/tag or impulse");
        var interstellar = json.get("interstellar");
        if (interstellar != null
                && (!interstellar.isJsonPrimitive()
                        || !interstellar.getAsJsonPrimitive().isBoolean()))
            throw new JsonParseException("Expected boolean interstellar");
        var mass = json.get("mass");
        if (mass != null
                && (!mass.isJsonPrimitive() || !mass.getAsJsonPrimitive().isNumber()
                        || !Double.isFinite(mass.getAsDouble()) || mass.getAsDouble() < 0))
            throw new JsonParseException("Expected finite nonnegative numeric mass per 1000 mB");
        return new FuelDefinition(id, tag, value, interstellar != null && interstellar.getAsBoolean(),
                mass == null ? 0 : mass.getAsDouble());
    }

    static boolean required(Collection<FuelDefinition> definitions) {
        return !definitions.isEmpty();
    }

    static double best(Collection<FuelDefinition> definitions, Predicate<FuelDefinition> matches) {
        double best = 0;
        for (var definition : definitions)
            if (matches.test(definition)) best = Math.max(best, definition.impulse());
        return best;
    }

    static boolean restricted(Collection<FuelDefinition> definitions, boolean outside) {
        return outside && definitions.stream().anyMatch(FuelDefinition::interstellar);
    }

    static double best(
            Collection<FuelDefinition> definitions, Predicate<FuelDefinition> matches,
            boolean restricted) {
        return best(definitions, d -> (!restricted || d.interstellar()) && matches.test(d));
    }

    double fuelMass(double millibuckets) {
        return millibuckets * mass / 1000;
    }

    /** Exact rocket integral; massless definitions use its constant-mass limit. */
    double deltaV(double currentMass, double millibuckets) {
        return FuelBudget.velocityChange(currentMass, millibuckets * impulse, fuelMass(millibuckets));
    }

    static double consumption(double thrust, double seconds, double impulse) {
        return thrust * seconds / impulse;
    }
}
