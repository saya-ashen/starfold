package dev.starfold.planet;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundSolar;
import dev.starfold.chart.PlanetShape;

import net.minecraft.server.level.ServerLevel;

/** Explicit saved generator opt-in. Merely installing this mod never adds another planet. */
public final class PlanetSystem {
    /** What a level's generator tells about the planets it holds: implemented by the generator of a whole system. */
    public interface Saved {
        int count();

        /** The home system and, where the level has one, its universe beyond. */
        GroundEphemeris.Model model(ServerLevel level);

        PlanetShape shape(int planet);

        /** Written by hand in a star system file: its clock starts at tick 0, so the written phases are what is seen. */
        boolean authored();

        /** A world that begins beside a ship begins in mid-morning. */
        boolean startsWithShip();

        /** Whether the level has a universe beyond its home system, which grows as it is travelled. */
        boolean beyond(ServerLevel level);
    }

    public static int planet(net.minecraft.world.entity.Entity entity) {
        return PlanetStorage.planet(entity.getX(), entity.getZ());
    }

    public static GroundEphemeris.Model model() {
        return model(2);
    }

    /** Every planet after a sun's first circles further out, a ring apart, turning as Kepler would have it; suns
     *  stand a gap apart in a row. Until the layout of a universe is generated, this is where everything is. */
    public static final double RING = 12288, STAR_GAP = 49152;

    public static GroundEphemeris.Model model(int count) {
        return model(new int[] {count});
    }

    /** A universe of one sun for each entry, with that many planets about it, in storage order. The first two
     *  planets, when present, share the innermost ring. */
    public static GroundEphemeris.Model model(int[] groups) {
        int count = 0;
        for (int group : groups) {
            if (group < 1) throw new IllegalArgumentException("Star without planets");
            count += group;
        }
        if (groups.length < 1 || count > PlanetStorage.MAX_PLANETS)
            throw new IllegalArgumentException("System planet count " + count);
        var m = GroundSolar.defaults();
        var path = m.orbit(0);
        var still =
                new GroundEphemeris.Spin(
                        false, GroundEphemeris.Y, new org.joml.Quaterniond(), 0, 36000, 0);
        var bodies = new java.util.ArrayList<GroundEphemeris.Body>();
        // Neighbouring suns stand a gap apart: the outermost rings of both, and room between them,
        // must fit in it.
        for (int star = 1; star < groups.length; star++) {
            double reach = path.radius() + (groups[star] - 1) * RING,
                    before =
                            path.radius()
                                    + Math.max(0, groups[star - 1] - (star == 1 ? 2 : 1)) * RING;
            if (reach + before + 8192 > STAR_GAP)
                throw new IllegalArgumentException(
                        "Suns "
                                + (star - 1)
                                + " and "
                                + star
                                + " have too many planets for the gap between them");
        }
        for (int star = 0, id = 0; star < groups.length; star++) {
            var centre =
                    m.origin()
                            .add(
                                    new dev.starfold.geometry.BentStripGeometry.Point(
                                            star * STAR_GAP, 0, 0));
            int parent = bodies.size();
            bodies.add(
                    new GroundEphemeris.Body(
                            new GroundEphemeris.Fixed(centre), m.primary().radius(), true, -1));
            for (int place = 0; place < groups[star]; place++, id++) {
                boolean pair = star == 0 && place < 2;
                double radius = path.radius() + (pair ? 0 : star == 0 ? place - 1 : place) * RING;
                var orbit =
                        new GroundEphemeris.Orbit(
                                parent,
                                path.axis(),
                                path.radial(),
                                radius,
                                path.period() * Math.pow(radius / path.radius(), 1.5),
                                pair ? (place == 0 ? Math.PI : 0) : id * 2.1,
                                path.epoch(),
                                id == 0 ? path.spin() : still);
                bodies.add(new GroundEphemeris.Body(orbit, 0, false, id));
            }
        }
        return new GroundEphemeris.Model(bodies);
    }

    /** How many planets this level's saved system has, and how they are shared out among its suns. */
    public static int count(ServerLevel current) {
        return ((Saved) current.getChunkSource().getGenerator()).count();
    }

    /** The model of everything this level holds: its home system and, where it has one, its universe beyond. */
    public static GroundEphemeris.Model model(ServerLevel current) {
        return ((Saved) current.getChunkSource().getGenerator()).model(current);
    }

    /** A, B, C … in storage order. */
    private static volatile java.util.List<PlanetIdentity> identities = java.util.List.of();

    /** Back to the two standard planets nobody has named, as before any world was loaded. */
    public static void reset() {
        identities(java.util.List.of());
        names(java.util.List.of());
        PlanetShape.reset();
    }

    public static void identities(java.util.List<PlanetIdentity> values) {
        identities = java.util.List.copyOf(values);
    }

    public static java.util.List<PlanetIdentity> identities() {
        return identities;
    }

    public static PlanetIdentity identity(int planet) {
        var values = identities;
        return planet >= 0 && planet < values.size()
                ? values.get(planet)
                : new PlanetIdentity(
                        java.util.Optional.empty(),
                        planet == 0 ? "temperate" : "rocky",
                        java.util.Optional.empty());
    }

    private static volatile java.util.List<String> names = java.util.List.of();

    public static void names(java.util.List<String> saved) {
        names = java.util.List.copyOf(saved);
    }

    public static java.util.List<String> names() {
        return names;
    }

    public static String name(int planet) {
        var saved = names;
        return planet >= 0 && planet < saved.size() ? saved.get(planet) : defaultName(planet);
    }

    public static String defaultName(int planet) {
        return PlanetIdentity.defaultName(planet);
    }

    /** The saved shape of a planet of this level. */
    public static PlanetShape shape(ServerLevel current, int planet) {
        PlanetShape.valid(planet);
        return ((Saved) current.getChunkSource().getGenerator()).shape(planet);
    }

    private PlanetSystem() {}
}
