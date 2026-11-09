package dev.starfold.ship;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.resources.ResourceLocation;

/** Only pressure belongs here; other damage mechanisms do not exist yet. */
record HullPressureDefinition(ResourceLocation blocks, boolean tag, double pressure) {
    static HullPressureDefinition parse(JsonObject json) {
        var blocks = json.get("blocks");
        var pressure = json.get("pressure");
        if (blocks == null || !blocks.isJsonPrimitive() || !blocks.getAsJsonPrimitive().isString()
                || pressure == null || !pressure.isJsonPrimitive()
                || !pressure.getAsJsonPrimitive().isNumber())
            throw new JsonParseException("Expected blocks string and positive numeric pressure");
        String name = blocks.getAsString();
        boolean tag = name.startsWith("#");
        var id = ResourceLocation.tryParse(tag ? name.substring(1) : name);
        double value = pressure.getAsDouble();
        if (id == null || id.getPath().isEmpty() || !(value > 0) || !Double.isFinite(value))
            throw new JsonParseException("Invalid block id/tag or pressure");
        return new HullPressureDefinition(id, tag, value);
    }
}
