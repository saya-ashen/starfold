package dev.starfold.space;

import dev.starfold.flight.ArrivalState;
import dev.starfold.flight.FlightMode;
import dev.starfold.flight.HudLayout;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ship.HullLanding;

import java.util.List;

/** One immutable read-only sample per rendered world frame. Positions/vectors are immutable Points. */
public record FlightSnapshot(
        int tier,
        FlightMode mode,
        int planet,
        double speed,
        int relativeTo,
        double deltaV,
        boolean boosted,
        double closing,
        Target target,
        List<Target> planets,
        Point velocityDirection,
        SpaceNavigation.Marker velocityMarker,
        boolean retrograde,
        Point referenceDirection,
        int referencePlanet,
        Thrust thrust,
        double pointerX,
        double pointerY,
        double clearance,
        double vertical,
        double horizontal,
        int launchPlanet,
        Arrival arrival,
        Landing landing,
        Home home,
        ArrivalState.Phase phase,
        double blend,
        int notice,
        GasTarget gas) {
    public FlightSnapshot(
            int tier,
            FlightMode mode,
            int planet,
            double speed,
            int relativeTo,
            double deltaV,
            boolean boosted,
            double closing,
            Target target,
            List<Target> planets,
            Point velocityDirection,
            SpaceNavigation.Marker velocityMarker,
            boolean retrograde,
            Point referenceDirection,
            int referencePlanet,
            Thrust thrust,
            double pointerX,
            double pointerY,
            double clearance,
            double vertical,
            double horizontal,
            int launchPlanet,
            Arrival arrival,
            Landing landing,
            Home home,
            ArrivalState.Phase phase,
            double blend,
            int notice) {
        this(
                tier,
                mode,
                planet,
                speed,
                relativeTo,
                deltaV,
                boosted,
                closing,
                target,
                planets,
                velocityDirection,
                velocityMarker,
                retrograde,
                referenceDirection,
                referencePlanet,
                thrust,
                pointerX,
                pointerY,
                clearance,
                vertical,
                horizontal,
                launchPlanet,
                arrival,
                landing,
                home,
                phase,
                blend,
                notice,
                null);
    }

    /** Non-landable target: null layer means unknown/stale server hull sample, never safe zero. */
    public record GasTarget(
            dev.starfold.chart.CelestialTarget celestial,
            String name,
            SpaceNavigation.Marker marker,
            double distance,
            double closing,
            CelestialNavigation.GasReading layer,
            int rank,
            int count,
            String blockerName) {}

    double speedRange() {
        return Math.max(200, Math.max(speed, deltaV));
    }

    public record Target(
            int planet,
            SpaceNavigation.Marker marker,
            double distance,
            double closing,
            double angle,
            int rank,
            int count,
            int blocker,
            String gasBlockerName) {
        public Target(
                int planet,
                SpaceNavigation.Marker marker,
                double distance,
                double closing,
                double angle,
                int rank,
                int count,
                int blocker) {
            this(planet, marker, distance, closing, angle, rank, count, blocker, null);
        }

        public boolean blocked() {
            return blocker >= 0 || gasBlockerName != null;
        }

        Target(
                int planet,
                SpaceNavigation.Marker marker,
                double distance,
                double closing,
                double angle,
                int rank,
                int count) {
            this(planet, marker, distance, closing, angle, rank, count, -1);
        }

        public Target(
                int planet,
                SpaceNavigation.Marker marker,
                double distance,
                double closing,
                double angle) {
            this(planet, marker, distance, closing, angle, 0, 0, -1);
        }

        public String eta() {
            return HudLayout.eta(distance, closing);
        }
    }

    public record Lift(double up, boolean insufficient) {}

    public record Thrust(
            boolean forward,
            boolean back,
            boolean left,
            boolean right,
            boolean up,
            boolean down,
            Lift lift) {
        public Thrust(
                boolean forward,
                boolean back,
                boolean left,
                boolean right,
                boolean up,
                boolean down) {
            this(forward, back, left, right, up, down, null);
        }
    }

    public record Arrival(
            double height,
            double closing,
            double limit,
            double totalLimit,
            SpaceNavigation.Marker entry,
            double alignment,
            Thrust disabled) {}

    public record Landing(
            double limit,
            Point drift,
            double roll,
            double pitch,
            double width,
            double length,
            HullLanding.Site site) {
        Landing(double limit, Point drift, double roll, double pitch, double width, double length) {
            this(limit, drift, roll, pitch, width, length, HullLanding.Site.UNKNOWN);
        }
    }

    public record Home(
            String name,
            int planet,
            double distance,
            double angle,
            SpaceNavigation.Marker marker) {}

    public FlightSnapshot {
        planets = List.copyOf(planets);
    }
}
