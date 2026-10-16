package dev.starfold.ship;

import java.util.*;

/** Pure per-hull reservations. A column is released only after its last ship lets go. */
public final class ShipLeases {
    public enum Reason {
        ALWAYS,
        PILOT,
        PLAYER_ABOARD,
        PLAYER_NEAR,
        NON_PLAYER_ABOARD,
        LEAD,
        DEPENDENCY,
        WAKING,
        PROBE
    }

    public record Input(UUID ship, Set<Long> footprint, Set<Reason> reasons, Set<Long> cargo) {
        public Input {
            Objects.requireNonNull(ship);
            footprint = Set.copyOf(footprint);
            reasons = Set.copyOf(reasons);
            cargo = Set.copyOf(cargo);
        }
    }

    public record Plan(Map<UUID, Set<Long>> ships, Map<Long, Integer> references) {
        public Plan {
            ships = Map.copyOf(ships);
            references = Map.copyOf(references);
        }

        public Set<Long> columns() {
            return references.keySet();
        }

        public Set<Long> columns(UUID ship) {
            return ships.getOrDefault(ship, Set.of());
        }
    }

    /** Plan one dimension at a time; raw column numbers in different levels never share counts. */
    public static Plan plan(Collection<Input> inputs) {
        var ships = new HashMap<UUID, Set<Long>>();
        var references = new HashMap<Long, Integer>();
        for (var input : inputs) {
            var columns = new HashSet<Long>();
            if (!input.reasons().isEmpty()) {
                columns.addAll(input.footprint());
                columns.addAll(input.cargo());
            }
            if (ships.putIfAbsent(input.ship(), Set.copyOf(columns)) != null)
                throw new IllegalArgumentException("Duplicate ship lease");
            for (long column : columns) references.merge(column, 1, Integer::sum);
        }
        return new Plan(ships, references);
    }

    private ShipLeases() {}
}
