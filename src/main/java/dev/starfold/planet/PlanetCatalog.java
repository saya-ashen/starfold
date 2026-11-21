package dev.starfold.planet;

import dev.starfold.chart.PlanetShape;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable shapes, depths and labels in storage order. Capture {@link #current()} once when an
 * operation needs several fields from the same publication.
 *
 * <p>The contextless geometry APIs still share one process-wide catalog. This makes publication
 * atomic, but does not give a server world and a client connection separate ownership: an older
 * client decode can still replace a newer server catalog in an integrated server. Splitting that
 * ownership requires passing world/connection context through those readers and packet validators.
 */
public final class PlanetCatalog {
    private final PlanetShape[] shapes;
    private final int[] depths;
    private final List<String> names;
    private final List<PlanetIdentity> identities;

    private static volatile PlanetCatalog current = defaults();

    public PlanetCatalog(
            int[] sizes, int[] depths, List<String> names, List<PlanetIdentity> identities) {
        if (sizes.length < 1
                || sizes.length > PlanetShape.MAX_PLANETS
                || depths.length != sizes.length)
            throw new IllegalArgumentException("Planet count " + sizes.length);
        this.shapes = new PlanetShape[sizes.length];
        this.depths = depths.clone();
        for (int id = 0; id < sizes.length; id++) {
            shapes[id] = new PlanetShape(sizes[id]);
            validDepth(shapes[id], this.depths[id]);
        }
        this.names = List.copyOf(names);
        this.identities = List.copyOf(identities);
        if (!names.isEmpty() && names.size() != sizes.length)
            throw new IllegalArgumentException("Planet names/shapes disagree");
        if (!identities.isEmpty() && identities.size() != sizes.length)
            throw new IllegalArgumentException("Planet identities/shapes disagree");
    }

    /** Arrays here are privately owned and never mutated after construction; lists are immutable. */
    private PlanetCatalog(
            PlanetShape[] shapes,
            int[] depths,
            List<String> names,
            List<PlanetIdentity> identities) {
        this.shapes = shapes;
        this.depths = depths;
        this.names = names;
        this.identities = identities;
    }

    private static PlanetCatalog defaults() {
        return new PlanetCatalog(
                new PlanetShape[] {PlanetShape.STANDARD, PlanetShape.STANDARD},
                new int[] {PlanetShape.STANDARD_DEPTH, PlanetShape.STANDARD_DEPTH},
                List.of(),
                List.of());
    }

    private static void validDepth(PlanetShape shape, int depth) {
        if (depth < PlanetShape.STANDARD_DEPTH || depth >= shape.radius())
            throw new IllegalArgumentException("Planet depth " + depth);
    }

    public static PlanetCatalog current() {
        return current;
    }

    /** The complete server or decoded packet catalog becomes visible with one volatile write. */
    public static synchronized void install(PlanetCatalog catalog) {
        current = Objects.requireNonNull(catalog);
    }

    public static synchronized void reset() {
        current = defaults();
    }

    // Compatibility setters deliberately replace only their own fields, as the old test/setup APIs
    // did. Serialize their read-copy-write operations so concurrent partial updates are not lost.
    // Metadata may be absent or have a different length through these legacy setters; production
    // full installs use the validating constructor above.
    public static synchronized void system(int[] sizes, int[] depths) {
        var geometry = new PlanetCatalog(sizes, depths, List.of(), List.of());
        current =
                new PlanetCatalog(
                        geometry.shapes, geometry.depths, current.names, current.identities);
    }

    public static synchronized void assign(int planet, PlanetShape shape, int depth) {
        int id = current.valid(planet);
        validDepth(shape, depth);
        var shapes = current.shapes.clone();
        var depths = current.depths.clone();
        shapes[id] = shape;
        depths[id] = depth;
        current = new PlanetCatalog(shapes, depths, current.names, current.identities);
    }

    public static synchronized void resetShapes() {
        var geometry = defaults();
        current =
                new PlanetCatalog(
                        geometry.shapes, geometry.depths, current.names, current.identities);
    }

    public static synchronized void names(List<String> names) {
        current =
                new PlanetCatalog(
                        current.shapes, current.depths, List.copyOf(names), current.identities);
    }

    public static synchronized void identities(List<PlanetIdentity> identities) {
        current =
                new PlanetCatalog(
                        current.shapes, current.depths, current.names, List.copyOf(identities));
    }

    public int count() {
        return shapes.length;
    }

    public int valid(int planet) {
        if (planet < 0 || planet >= shapes.length) throw new IllegalArgumentException("Planet");
        return planet;
    }

    public PlanetShape shape(int planet) {
        return shapes[valid(planet)];
    }

    public int depth(int planet) {
        return depths[valid(planet)];
    }

    public double coreRadius(int planet) {
        int id = valid(planet);
        return shapes[id].radius() - depths[id] - 1 - .01;
    }

    public int[] sizes() {
        var sizes = new int[shapes.length];
        for (int id = 0; id < sizes.length; id++) sizes[id] = shapes[id].size();
        return sizes;
    }

    public int[] depths() {
        return depths.clone();
    }

    public List<String> names() {
        return names;
    }

    public String name(int planet) {
        return planet >= 0 && planet < names.size()
                ? names.get(planet)
                : PlanetIdentity.defaultName(planet);
    }

    public List<PlanetIdentity> identities() {
        return identities;
    }

    public PlanetIdentity identity(int planet) {
        return planet >= 0 && planet < identities.size()
                ? identities.get(planet)
                : new PlanetIdentity(
                        Optional.empty(), planet == 0 ? "temperate" : "rocky", Optional.empty());
    }
}
