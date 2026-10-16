package dev.starfold.ship;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;

import net.minecraft.resources.ResourceLocation;

import org.joml.Quaterniond;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Identity and location only; Sable owns the hull's blocks and storage pose. */
public record ShipRecord(
        ShipId id,
        UUID uuid,
        ResourceLocation dimension,
        Optional<String> name,
        State state,
        Optional<Locator> locator) {
    public ShipRecord {
        Objects.requireNonNull(id);
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(name);
        Objects.requireNonNull(state);
        Objects.requireNonNull(locator);
    }

    public sealed interface State permits OnSlot, InSpace {}

    /** The chart stays where Sable saved it; its pose is not reprojected through universe space. */
    public record OnSlot(int slot, Optional<ChartPlace> place) implements State {
        public OnSlot {
            if (slot < 0) throw new IllegalArgumentException("Ship slot");
            Objects.requireNonNull(place);
        }

        public OnSlot(int slot) {
            this(slot, Optional.empty());
        }
    }

    /** The serialized material anchor in a stationary surface chart. */
    public record ChartPlace(Point position, Point anchorPlot, Quaterniond attitude, long tick) {
        public ChartPlace {
            GroundEphemeris.point(position);
            GroundEphemeris.point(anchorPlot);
            GroundEphemeris.quaternion(attitude);
            GroundEphemeris.time(tick);
            attitude = new Quaterniond(attitude);
        }

        @Override
        public Quaterniond attitude() {
            return new Quaterniond(attitude);
        }
    }

    public record InSpace(Place place) implements State {
        public InSpace {
            Objects.requireNonNull(place);
        }
    }

    /** A material plot point and hull attitude fixed in the named body's turning frame. */
    public record Place(
            int body, Point anchorPlot, Point position, Quaterniond attitude, long tick) {
        public Place {
            if (body < 0) throw new IllegalArgumentException("Ship body");
            GroundEphemeris.point(anchorPlot);
            GroundEphemeris.point(position);
            GroundEphemeris.quaternion(attitude);
            GroundEphemeris.time(tick);
            attitude = new Quaterniond(attitude);
        }

        @Override
        public Quaterniond attitude() {
            return new Quaterniond(attitude);
        }
    }

    /** Sable's holding address, not a plot slot or a universe position. */
    public record Locator(int chunkX, int chunkZ, short storageIndex, short subLevelIndex) {}
}
