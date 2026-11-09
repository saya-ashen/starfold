package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

class ThrusterFuelTest {
    private static FuelDefinition parse(String json) {
        return FuelDefinition.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void parsesFluidIdsAndTestPackTagExample() throws Exception {
        var direct = parse("{\"fluid\":\"mekanism:hydrogen\",\"impulse\":2500}");
        assertEquals(ResourceLocation.parse("mekanism:hydrogen"), direct.fluid());
        assertFalse(direct.tag());
        assertFalse(direct.interstellar());
        assertEquals(2500, direct.impulse());
        assertEquals(0, direct.mass());
        assertEquals(0, parse("{\"fluid\":\"test:fuel\",\"impulse\":1,\"mass\":0}").mass());
        assertEquals(.775, parse("{\"fluid\":\"test:fuel\",\"impulse\":1.55,\"mass\":0.775}").mass());
        try (var stream = getClass().getResourceAsStream(
                "/data/fuel_test/starfold/thruster_fuel/diesel.json")) {
            assertNotNull(stream);
            var tag = parse(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(tag.tag());
            assertEquals(ResourceLocation.parse("c:fuels/diesel"), tag.fluid());
            assertEquals(1000, tag.impulse());
        }
    }

    @Test
    void rejectsMalformedDefinitionsAtTheJsonBoundary() {
        for (var json : List.of("{}", "{\"fluid\":1,\"impulse\":1}",
                "{\"fluid\":\"BAD:ID\",\"impulse\":1}",
                "{\"fluid\":\"#\",\"impulse\":1}",
                "{\"fluid\":\"minecraft:water\"}",
                "{\"fluid\":\"minecraft:water\",\"impulse\":\"100\"}",
                "{\"fluid\":\"minecraft:water\",\"impulse\":0}",
                "{\"fluid\":\"minecraft:water\",\"impulse\":-1}",
                "{\"fluid\":\"minecraft:water\",\"impulse\":1e999}",
                "{\"fluid\":\"minecraft:water\",\"impulse\":1,\"interstellar\":null}",
                "{\"fluid\":\"minecraft:water\",\"impulse\":1,\"interstellar\":1}",
                "{\"fluid\":\"minecraft:water\",\"impulse\":1,\"interstellar\":\"true\"}",
                "{\"fluid\":\"test:fuel\",\"impulse\":1,\"mass\":null}",
                "{\"fluid\":\"test:fuel\",\"impulse\":1,\"mass\":\"1\"}",
                "{\"fluid\":\"test:fuel\",\"impulse\":1,\"mass\":true}",
                "{\"fluid\":\"test:fuel\",\"impulse\":1,\"mass\":-1}",
                "{\"fluid\":\"test:fuel\",\"impulse\":1,\"mass\":1e999}"))
            assertThrows(com.google.gson.JsonParseException.class, () -> parse(json), json);
    }

    @Test
    void choosesHighestMatchingImpulseRegardlessOfDefinitionOrder() {
        var low = parse("{\"fluid\":\"test:diesel\",\"impulse\":1000}");
        var high = parse("{\"fluid\":\"#test:hydrogen\",\"impulse\":3000}");
        var absent = parse("{\"fluid\":\"test:absent\",\"impulse\":9000}");
        for (var definitions : List.of(List.of(low, high, absent), List.of(absent, high, low))) {
            assertEquals(3000, FuelDefinition.best(definitions, d -> d != absent));
            assertEquals(1000, FuelDefinition.best(definitions, d -> d == low));
            assertEquals(0, FuelDefinition.best(definitions, d -> false));
        }
    }

    @Test
    void interstellarFilterIsOptInAndKeepsEveryFuelUsableAtHome() {
        var low = parse("{\"fluid\":\"test:diesel\",\"impulse\":9000}");
        var high = parse("{\"fluid\":\"test:deuterium\",\"impulse\":3000,\"interstellar\":true}");
        var explicitFalse = parse("{\"fluid\":\"test:water\",\"impulse\":100,\"interstellar\":false}");
        assertFalse(explicitFalse.interstellar());
        assertTrue(high.interstellar());
        assertFalse(FuelDefinition.restricted(List.of(), true));
        assertFalse(FuelDefinition.restricted(List.of(low, explicitFalse), true));
        for (var definitions : List.of(List.of(low, high), List.of(high, low))) {
            assertFalse(FuelDefinition.restricted(definitions, false));
            assertEquals(9000, FuelDefinition.best(definitions, d -> true, false));
            assertTrue(FuelDefinition.restricted(definitions, true));
            assertEquals(3000, FuelDefinition.best(definitions, d -> true, true));
            assertEquals(0, FuelDefinition.best(definitions, d -> d == low, true));
        }
    }

    @Test
    void boundaryIncludesAllHomeSunsAndUsesTheHomeExtentNotTheGrownUniverse() {
        var home = dev.starfold.planet.PlanetSystem.model(new int[] {2, 1, 1, 1});
        var origin = home.origin();
        var x = new dev.starfold.geometry.BentStripGeometry.Point(1, 0, 0);
        assertFalse(ThrusterFuel.outside(home, origin.add(x.scale(home.extent()))));
        assertTrue(ThrusterFuel.outside(home, origin.add(x.scale(home.extent() + 1))));
        assertTrue(ThrusterFuel.outside(home, origin.sub(x.scale(home.extent() + 1))));
        for (int i : home.catalogIndices())
            assertFalse(ThrusterFuel.outside(home, home.frame(i, 0).center()));
        var bodies = new java.util.ArrayList<>(home.bodies());
        bodies.add(new dev.starfold.chart.GroundEphemeris.Body(
                new dev.starfold.chart.GroundEphemeris.Fixed(origin.add(x.scale(home.extent() * 3))),
                1024, true, -1, dev.starfold.chart.GroundEphemeris.Appearance.LIGHT));
        var grown = new dev.starfold.chart.GroundEphemeris.Model(bodies);
        var beyond = origin.add(x.scale(home.extent() + 1));
        assertFalse(ThrusterFuel.outside(grown, beyond));
        assertTrue(ThrusterFuel.outside(home, beyond));
    }

    @Test
    void emptyDefinitionsAreFreeButAnUnmatchedDefinitionStillRequiresFuel() throws Exception {
        assertFalse(FuelDefinition.required(List.of()));
        assertEquals(3350, ThrusterFuel.draw(null, 3350));
        assertEquals(0, ThrusterFuel.draw(null, 0));
        assertEquals(-1, ThrusterFuel.remaining(null));
        assertTrue(FuelDefinition.required(List.of(
                parse("{\"fluid\":\"missing:diesel\",\"impulse\":1000}"))));
        // Main data must never opt a standalone install into fuel consumption.
        try (var paths = Files.walk(Path.of("src/main/resources/data"))) {
            assertFalse(paths.anyMatch(p -> p.toString().contains("/starfold/thruster_fuel/")));
        }
    }

    @Test
    void optionalDriveGateCoversEveryBoundaryTagAndHardwareCombination() throws Exception {
        for (boolean outside : new boolean[] {false, true})
            for (boolean required : new boolean[] {false, true})
                for (boolean present : new boolean[] {false, true})
                    assertEquals(!(outside && required && !present),
                            ThrusterFuel.driveAllowed(outside, required, present));
        var tag = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/data/starfold/tags/block/interstellar_drives.json"))).getAsJsonObject();
        assertTrue(tag.getAsJsonArray("values").asList().stream()
                .anyMatch(value -> value.getAsString().equals("starfold:interstellar_drive")));
        assertFalse(tag.get("replace").getAsBoolean());
    }

    @Test
    void deltaVUsesMassPerBucketAndLeavesOtherFuelsAsPayload() {
        var fuel = parse("{\"fluid\":\"test:fuel\",\"impulse\":1.55,\"mass\":0.775}");
        assertEquals(6.2, fuel.fuelMass(8000), 1e-12);
        assertEquals(2000 * Math.log(30.7 / 24.5), fuel.deltaV(30.7, 8000), 1e-10);
        assertEquals(2000 * Math.log(40.7 / 34.5), fuel.deltaV(40.7, 8000), 1e-10);
        assertEquals(0, fuel.deltaV(24.5, 0));
        assertEquals(8000 / 24.5, parse("{\"fluid\":\"test:fuel\",\"impulse\":1}").deltaV(24.5, 8000));
        // Burning in stages gives the same ideal reserve; no arbitrary speed cap enters the formula.
        assertEquals(fuel.deltaV(30.7, 8000),
                fuel.deltaV(30.7, 4000) + fuel.deltaV(27.6, 4000), 1e-10);
    }

    @Test
    void throttleStepAndBoostScaleConsumptionLinearly() {
        assertEquals(.1675, FuelDefinition.consumption(Thrusters.THRUST, .05, 1000), 1e-12);
        for (int power = 0; power <= 15; power++)
            for (double step : new double[] {0, .01, .05}) {
                double ordinary = FuelDefinition.consumption(Thrusters.THRUST * power / 15, step, 1000);
                assertEquals(Thrusters.impulse(power, step, false) / 1000, ordinary, 1e-12);
                assertEquals(ordinary * Thrusters.BOOST,
                        Thrusters.impulse(power, step, true) / 1000, 1e-12);
            }
    }

    @Test
    void wholeMillibucketPrepaymentIsConservedAcrossNozzlesAndSubsteps() {
        var budget = new FuelBudget();
        assertEquals(0, budget.take(20));
        budget.buy(1, 1000);
        assertEquals(100, budget.take(100));
        assertEquals(.9, budget.remaining(), 1e-12);
        assertEquals(0, budget.take(0));
        assertEquals(.9, budget.remaining(), 1e-12);
        for (int nozzle = 0; nozzle < 9; nozzle++) assertEquals(100, budget.take(100));
        assertEquals(0, budget.remaining(), 1e-12);
        assertEquals(0, budget.take(100));
    }

    @Test
    void finalFuelOnlyFundsTheImpulseItCanSupplyAndLowerGradeCanFinishTheStep() {
        var budget = new FuelBudget();
        budget.buy(2, 3000);
        double requested = 6500;
        double high = budget.take(requested);
        assertEquals(6000, high);
        budget.buy(1, 1000);
        assertEquals(500, budget.take(requested - high));
        assertEquals(.5, budget.remaining());
        assertEquals(500, budget.take(1000));
        assertEquals(0, budget.take(1));
    }

    @Test
    void helmReadoutRoundTripsEnabledEmptyAndDisabledWithoutFuelDefinitions() {
        for (double fuel : new double[] {-1, 0, .75, 8000})
            for (double deltaV : new double[] {-1, 0, 1240.5}) {
            var packet = new HelmPackets.State(UUID.randomUUID(), BlockPos.ZERO, Direction.NORTH,
                    true, true, 0, false, 0, 0, 0, HullLanding.Site.UNKNOWN, false, fuel, deltaV);
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                HelmPackets.State.CODEC.encode(buffer, packet);
                assertEquals(packet, HelmPackets.State.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void fuelReadoutIsBilingualAndDoesNotNeedAScopeTier() throws Exception {
        for (var lang : List.of("en_us", "zh_cn")) {
            var json = JsonParser.parseString(Files.readString(Path.of(
                    "src/main/resources/assets/starfold/lang/" + lang + ".json"))).getAsJsonObject();
            var label = json.get("hud.starfold.instrument_fuel").getAsString();
            assertTrue(label.contains("%s"));
            assertTrue(label.contains("mB"));
            var deltaV = json.get("hud.starfold.instrument_delta_v").getAsString();
            assertTrue(deltaV.contains("Δv %s"));
            assertTrue(deltaV.endsWith(lang.equals("en_us") ? "blocks/s" : "格/s"));
        }
        var source = Files.readString(Path.of("src/main/java/dev/starfold/space/SpaceNavigationClient.java"));
        assertTrue(source.indexOf("FlightWidgets.fuel(g, helm.fuel(), helm.deltaV())")
                < source.indexOf("if (snapshot == null || level != mc.level) return;", source.indexOf("private static void render")));
    }
}
