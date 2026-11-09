package dev.starfold.ship;

/** Prepaid sub-mB impulse shared by all nozzles on one hull. */
final class FuelBudget {
    private double credit, unit, massPerImpulse;

    void buy(int amount, double impulse) {
        buy(amount, impulse, 0);
    }

    void buy(int amount, double impulse, double massPerBucket) {
        credit += amount * impulse;
        unit = impulse;
        massPerImpulse = massPerBucket / 1000 / impulse;
    }

    double credit() {
        return credit;
    }

    double massPerImpulse() {
        return massPerImpulse;
    }

    double mass() {
        return credit * massPerImpulse;
    }

    double impulseFor(double currentMass, double deltaV) {
        return massPerImpulse == 0 ? currentMass * deltaV
                : -currentMass * Math.expm1(-deltaV * massPerImpulse) / massPerImpulse;
    }

    double deltaV(double currentMass, double impulse) {
        return velocityChange(currentMass, impulse, impulse * massPerImpulse);
    }

    static double velocityChange(double currentMass, double impulse, double burnedMass) {
        return burnedMass == 0 ? impulse / currentMass
                : impulse / burnedMass * -Math.log1p(-burnedMass / currentMass);
    }

    double take(double requested) {
        double applied = Math.min(requested, credit);
        credit -= applied;
        return applied;
    }

    double remaining() {
        return credit == 0 ? 0 : credit / unit;
    }
}
