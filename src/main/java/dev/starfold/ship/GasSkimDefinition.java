package dev.starfold.ship;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.resources.ResourceLocation;

/** One global output; Starfold itself supplies neither a definition nor a fluid. */
record GasSkimDefinition(ResourceLocation fluid, double rate) {
    static GasSkimDefinition parse(JsonObject json) {
        var fluid = json.get("fluid");
        var rate = json.get("rate");
        if (fluid == null || !fluid.isJsonPrimitive() || !fluid.getAsJsonPrimitive().isString()
                || rate == null || !rate.isJsonPrimitive() || !rate.getAsJsonPrimitive().isNumber())
            throw new JsonParseException("Expected fluid string and positive numeric rate");
        var id = ResourceLocation.tryParse(fluid.getAsString());
        double value = rate.getAsDouble();
        if (id == null || id.getPath().isEmpty() || !(value > 0)
                || !Double.isFinite(value) || value > Integer.MAX_VALUE / 2.0)
            throw new JsonParseException("Invalid fluid id or rate (maximum 1073741823.5)");
        return new GasSkimDefinition(id, value);
    }

    double amount(double pressure) {
        // First-guess balance: pressure 0.5 yields rate mB/tick; doubling pressure doubles intake.
        return rate * pressure / .5;
    }
}
