package dev.starfold.flight;

/** Pure tick state. Identity is the nearest planet and persistent lead-body id, never the lock. */
public record ArrivalState(
        Phase phase,
        int ticks,
        int cooldown,
        int leaving,
        double entryHeight,
        int planet,
        String lead,
        Departure departure) {
    /** Flight-long identity: neither elapsed time, altitude nor another nearest planet clears it. */
    public record Departure(int planet, String lead) {}

    public ArrivalState(
            Phase phase,
            int ticks,
            int cooldown,
            int leaving,
            double entryHeight,
            int planet,
            String lead) {
        this(phase, ticks, cooldown, leaving, entryHeight, planet, lead, null);
    }

    private ArrivalState withDeparture(Departure d) {
        return new ArrivalState(phase, ticks, cooldown, leaving, entryHeight, planet, lead, d);
    }

    public static ArrivalState departed(int planet, String lead) {
        return cruise(planet, lead, 0).withDeparture(new Departure(planet, lead));
    }

    public ArrivalState lock(int planet, String lead) {
        return departure != null && departure.planet == planet && departure.lead.equals(lead)
                ? withDeparture(null)
                : this;
    }

    public enum Phase {
        CRUISE,
        NOTICE,
        TURN_IN,
        ARRIVAL
    }

    static final int ARRIVE_NOTICE = 80, BLEND_TICKS = 20, RETRY = 60;

    public static ArrivalState cruise(int planet, String lead, int cooldown) {
        return new ArrivalState(Phase.CRUISE, 0, cooldown, 0, 0, planet, lead);
    }

    public static ArrivalState empty() {
        return cruise(-1, "", 0);
    }

    public boolean latched() {
        return phase == Phase.TURN_IN || phase == Phase.ARRIVAL;
    }

    public double blend() {
        return phase == Phase.ARRIVAL
                ? 1
                : phase == Phase.TURN_IN ? Math.clamp(ticks / (double) BLEND_TICKS, 0, 1) : 0;
    }

    public int seconds() {
        return phase == Phase.NOTICE ? (ARRIVE_NOTICE - ticks + 19) / 20 : 0;
    }

    ArrivalState restored() {
        return (latched()
                        ? new ArrivalState(
                                Phase.ARRIVAL, BLEND_TICKS, 0, 0, entryHeight, planet, lead)
                        : cruise(planet, lead, 0))
                .withDeparture(departure);
    }

    public ArrivalState step(
            int planet,
            String lead,
            double height,
            double closing,
            boolean start,
            boolean backstop,
            boolean abort,
            boolean chart,
            boolean aligned) {
        return step(planet, lead, height, closing, start, backstop, abort, chart, aligned, -1);
    }

    public ArrivalState step(
            int planet,
            String lead,
            double height,
            double closing,
            boolean start,
            boolean backstop,
            boolean abort,
            boolean chart,
            boolean aligned,
            int lockedPlanet) {
        var d = departure;
        if (d != null && (chart || !d.lead.equals(lead))) d = null;
        boolean intended =
                lockedPlanet >= 0 ? lockedPlanet == planet : d == null || d.planet != planet;
        // Only notice admission is gated; backstop, latch, timers and abort remain authoritative.
        return advance(
                        planet,
                        lead,
                        height,
                        closing,
                        start && intended,
                        backstop,
                        abort,
                        chart,
                        aligned)
                .withDeparture(d);
    }

    private ArrivalState advance(
            int planet,
            String lead,
            double height,
            double closing,
            boolean start,
            boolean backstop,
            boolean abort,
            boolean chart,
            boolean aligned) {
        if (chart) return empty();
        if (this.planet != planet || !this.lead.equals(lead))
            return cruise(planet, lead, 0)
                    .step(planet, lead, height, closing, start, backstop, abort, false, aligned);
        if (abort) return cruise(planet, lead, RETRY);
        if (latched()) {
            int away = closing < 0 ? leaving + 1 : 0;
            if (away >= RETRY || height > entryHeight + 500) return cruise(planet, lead, RETRY);
            int age = Math.min(BLEND_TICKS, ticks + 1);
            return new ArrivalState(
                    age >= BLEND_TICKS && aligned ? Phase.ARRIVAL : phase,
                    age,
                    0,
                    away,
                    entryHeight,
                    planet,
                    lead);
        }
        if (cooldown > 0) return cruise(planet, lead, cooldown - 1);
        if (phase == Phase.NOTICE) {
            if (backstop) return new ArrivalState(Phase.TURN_IN, 0, 0, 0, height, planet, lead);
            if (!start) return cruise(planet, lead, RETRY);
            if (ticks + 1 >= ARRIVE_NOTICE)
                return new ArrivalState(Phase.TURN_IN, 0, 0, 0, height, planet, lead);
            return new ArrivalState(Phase.NOTICE, ticks + 1, 0, 0, 0, planet, lead);
        }
        if (backstop) return new ArrivalState(Phase.TURN_IN, 0, 0, 0, height, planet, lead);
        return start ? new ArrivalState(Phase.NOTICE, 0, 0, 0, 0, planet, lead) : this;
    }

    /** Smooth acceleration during the first second; proportional ease over the final ten degrees. */
    public static double turnScale(double blend, double remainingDegrees) {
        double t = Math.clamp(blend, 0, 1);
        return t * t * (3 - 2 * t) * Math.clamp(remainingDegrees / 10, 0, 1);
    }
}
