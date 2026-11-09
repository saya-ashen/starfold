package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import dev.starfold.chart.GasGiant;
import dev.starfold.chart.GasPressure;
import dev.starfold.chart.GroundEphemeris;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class GasSkimTest {
    private static GasSkimDefinition parse(String json) {
        return GasSkimDefinition.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void parsesOneGlobalFluidAndFractionalReferenceRate() {
        var definition = parse("{\"fluid\":\"mekanism:deuterium\",\"rate\":0.25}");
        assertEquals(ResourceLocation.parse("mekanism:deuterium"), definition.fluid());
        assertEquals(.25, definition.rate());
    }

    @Test
    void rejectsMalformedAndUnboundedJson() {
        for (var json : List.of("{}", "{\"fluid\":1,\"rate\":1}",
                "{\"fluid\":\"BAD:ID\",\"rate\":1}",
                "{\"fluid\":\"#test:gas\",\"rate\":1}",
                "{\"fluid\":\"test:gas\"}",
                "{\"fluid\":\"test:gas\",\"rate\":\"1\"}",
                "{\"fluid\":\"test:gas\",\"rate\":0}",
                "{\"fluid\":\"test:gas\",\"rate\":-1}",
                "{\"fluid\":\"test:gas\",\"rate\":1e999}",
                "{\"fluid\":\"test:gas\",\"rate\":1073741824}"))
            assertThrows(JsonParseException.class, () -> parse(json), json);
    }

    @Test
    void referencePressureAndMaximumScaleLinearly() {
        var definition = parse("{\"fluid\":\"test:gas\",\"rate\":10}");
        assertEquals(0, definition.amount(0));
        assertEquals(5, definition.amount(.25));
        assertEquals(10, definition.amount(.5));
        assertEquals(20, definition.amount(1));
        var max = parse("{\"fluid\":\"test:gas\",\"rate\":1073741823.5}");
        assertEquals(Integer.MAX_VALUE, max.amount(1));
    }

    @Test
    void fractionalRatesAccumulateWithoutRoundingEveryTickUp() {
        var definition = parse("{\"fluid\":\"test:gas\",\"rate\":0.125}");
        double fraction = 0;
        int collected = 0;
        for (int tick = 0; tick < 80; tick++) {
            double amount = fraction + definition.amount(.25);
            int whole = (int) amount;
            fraction = amount - whole;
            collected += whole;
        }
        assertEquals(5, collected);
        assertEquals(0, fraction);
    }

    @Test
    void intakeUsesDamagePressureIncludingOuterAtmosphereAndSaturation() {
        var policy = new GasGiant(1, 0, "Gas", 0, 0xffffff, 256, 512);
        var layer = new GasPressure.Layer(0, GroundEphemeris.ZERO, 2048, policy);
        var field = new GasPressure.Field(List.of(layer));
        var definition = parse("{\"fluid\":\"test:gas\",\"rate\":10}");
        for (double depth : new double[] {-257, -256, -128, 0, 128, 512, 1024}) {
            var reading = field.worst(new dev.starfold.geometry.BentStripGeometry.Point(2048 - depth, 0, 0), 0).orElseThrow();
            assertEquals(policy.pressure(depth), reading.pressure());
            assertEquals(20 * policy.pressure(depth), definition.amount(reading.pressure()));
        }
        assertTrue(field.worst(new dev.starfold.geometry.BentStripGeometry.Point(2176, 0, 0), 0).orElseThrow().pressure() > 0);
    }

    @Test
    void standaloneHasNoSkimDefinitionAndScoopHasBilingualCraftableAssets() throws Exception {
        try (var paths = Files.walk(Path.of("src/main/resources/data"))) {
            assertFalse(paths.anyMatch(p -> p.toString().contains("/starfold/gas_skim/")));
        }
        for (var lang : List.of("en_us", "zh_cn")) {
            var json = JsonParser.parseString(Files.readString(Path.of(
                    "src/main/resources/assets/starfold/lang/" + lang + ".json"))).getAsJsonObject();
            assertFalse(json.get("block.starfold.gas_scoop").getAsString().isBlank());
        }
        for (var path : List.of("assets/starfold/blockstates/gas_scoop.json",
                "assets/starfold/models/block/gas_scoop.json", "assets/starfold/models/item/gas_scoop.json",
                "data/starfold/recipe/gas_scoop.json", "data/starfold/loot_table/blocks/gas_scoop.json"))
            assertNotNull(JsonParser.parseString(Files.readString(Path.of("src/main/resources/" + path))));
    }
}
