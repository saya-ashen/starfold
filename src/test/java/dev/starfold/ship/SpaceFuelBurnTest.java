package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.starfold.Sources;
import org.junit.jupiter.api.Test;

class SpaceFuelBurnTest {
    private static final FuelDefinition DIESEL = FuelDefinition.parse(JsonParser.parseString(
            "{\"fluid\":\"test:diesel\",\"impulse\":1.55,\"mass\":0.775}").getAsJsonObject());

    @Test
    void fullTankFinalSpeedEqualsInitialHudDeltaVAtEveryThrottleBoostAndSubstep() {
        for (double dry : new double[] {24.5, 2260.75})
            for (int power : new int[] {1, 7, 15})
                for (boolean boost : new boolean[] {false, true})
                    for (double step : new double[] {.05, .0125}) {
                        double initial = dry + DIESEL.fuelMass(8000);
                        double mass = initial, speed = 0;
                        int stored = 8000;
                        var budget = new FuelBudget();
                        int steps = 0;
                        while (stored > 0 || budget.remaining() > 0) {
                            double requested = Thrusters.impulse(power, step, boost);
                            double paid = budget.take(requested);
                            speed += budget.deltaV(mass, paid);
                            mass -= paid * budget.massPerImpulse();
                            double needed = requested - paid;
                            if (needed > 0 && stored > 0) {
                                int bought = (int) Math.min(stored, Math.ceil(needed / DIESEL.impulse()));
                                stored -= bought;
                                budget.buy(bought, DIESEL.impulse(), DIESEL.mass());
                                paid = budget.take(needed);
                                speed += budget.deltaV(mass, paid);
                                mass -= paid * budget.massPerImpulse();
                            }
                            assertTrue(++steps < 1000000);
                        }
                        double hud = DIESEL.deltaV(initial, 8000);
                        assertEquals(dry, mass, 1e-9);
                        assertEquals(hud, speed, 1e-8);
                        System.out.printf("delta-v dry=%.2f throttle=%d boost=%s step=%.4f HUD=%.12f final=%.12f ticks=%d%n",
                                dry, power, boost, step, hud, speed, steps);
                    }
    }

    @Test
    void paidStopsConsumeExactlyTheirVelocityBudgetIncludingFinalFraction() {
        var budget = new FuelBudget();
        budget.buy(8000, DIESEL.impulse(), DIESEL.mass());
        double mass = 30.7;
        double initial = DIESEL.deltaV(mass, 8000);
        double requested = 123.45;
        double paid = budget.take(budget.impulseFor(mass, requested));
        assertEquals(requested, budget.deltaV(mass, paid), 1e-10);
        mass -= paid * budget.massPerImpulse();
        assertTrue(budget.remaining() < 8000);
        double rest = budget.take(budget.impulseFor(mass, initial));
        assertEquals(initial - requested, budget.deltaV(mass, rest), 1e-10);
        assertEquals(0, budget.remaining());
        assertEquals(0, budget.take(100));
        assertEquals(0, budget.deltaV(mass, 0));
    }

    @Test
    void purchaseDoesNotEjectMassUntilItsPrepaidImpulseIsUsed() {
        var budget = new FuelBudget();
        budget.buy(1, 1000, 2);
        double mass = 10;
        assertEquals(.002, budget.mass());
        double paid = budget.take(100);
        double dv = budget.deltaV(mass, paid);
        mass -= paid * budget.massPerImpulse();
        assertEquals(.0018, budget.mass(), 1e-15);
        double remaining = budget.deltaV(mass, budget.credit());
        assertEquals(FuelBudget.velocityChange(10, 1000, .002), dv + remaining, 1e-12);
        assertEquals(1, FuelBudget.velocityChange(100, 100, 0));
    }

    @Test
    void productionUsesSameRocketIntegrationAndKeepsChartImpulsePath() throws Exception {
        var source = Sources.text("ThrusterFuel");
        assertTrue(source.contains("if(!Space.holds(at.x(),at.z()))return burn(body,requested,false)"));
        assertTrue(source.contains("burn(body,requested,false,true)*body.getMassTracker().getMass()"));
        assertTrue(source.contains("budget.deltaV(state.currentMass,impulse)"));
        assertTrue(source.contains("state.currentMass-=impulse*budget.massPerImpulse()"));
        assertTrue(source.contains("state.ordinary.mass()+state.interstellar.mass()"));
        assertTrue(Sources.text("Thrusters").contains("impulse=ThrusterFuel.draw(body,impulse)"));
    }
}
