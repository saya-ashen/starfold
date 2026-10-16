package dev.starfold.universe;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.GroundClock;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** The suns and planets a world's seed made of its universe layout, beyond the home system written in its preset.
 *  Made once, when the world is first loaded, and saved with it: the same seed and layout make the same universe,
 *  and a world keeps its universe whatever becomes of the layout. */
public final class Universe extends SavedData {
    public static final String FILE = "starfold_native_universe_v1";

    /**
     * A bound system grew. The listener publishes the system before invoking updateClock exactly
     * once, then tells observers using the returned clock. Keeping that update in grow preserves
     * the system, clock, observer order. Without observers only the clock needs updating.
     */
    @FunctionalInterface
    public interface Grown {
        void accept(
                ServerLevel level,
                SystemChunkGenerator system,
                java.util.function.Supplier<GroundClock> updateClock);

        /** Standalone growth updates its own clock without publishing to observers. */
        static Grown standalone() {
            return (level, system, updateClock) -> updateClock.get();
        }
    }

    public static Grown grown = Grown.standalone();

    /** A generated surface and its path parameters, with its parent addressed in the local body catalog. */
    public record Body(
            int parent, double radius, double period, double phase, PlanetChunkGenerator planet) {
        public Body {
            if (parent < 0
                    || planet == null
                    || !Double.isFinite(radius)
                    || !Double.isFinite(period)
                    || !Double.isFinite(phase)) throw new IllegalArgumentException("Universe body");
        }
    }

    /** Bodies append in dependency order; surface slots append independently of luminous bodies. */
    private final List<GroundEphemeris.Body> catalog;

    private final List<PlanetChunkGenerator> planets;
    private static final GroundEphemeris.Spin DISCOVERY_SPIN =
            new GroundEphemeris.Spin(
                    false, GroundEphemeris.Y, new org.joml.Quaterniond(), 0, 36000, 0);

    /** The cells of an endless universe that have been looked into, whether or not a sun was there. */
    private final java.util.Set<Long> cells = new java.util.HashSet<>();

    private boolean protectHome;

    /** Authored suns need not sit inside the home grid cell. Reserve their entire orbit envelopes, not just their centres. */
    public static boolean clearOfHome(Point position, double reach, GroundEphemeris.Model home) {
        double surface = new PlanetShape(PlanetShape.MAX).radius();
        for (int i = 0; i < home.bodies().size(); i++) {
            var body = home.bodies().get(i);
            if (!body.light()) continue;
            var centre = home.frame(i, 0).center();
            double outer = 0, band = GroundEphemeris.Body.DEFAULT_REACH;
            for (var child : home.bodies()) {
                if (child.slot() < 0) continue;
                var motion = child.motion();
                double radius = 0;
                int parent = -1;
                while (motion instanceof GroundEphemeris.Orbit orbit) {
                    radius += orbit.radius();
                    parent = orbit.parent();
                    motion = home.bodies().get(parent).motion();
                }
                if (parent == i && radius > outer + (band - child.hullOut())) {
                    outer = radius;
                    band = child.hullOut();
                }
            }
            if (position.sub(centre).length()
                    < reach + outer + (surface + band + SystemDefinition.MARGIN / 2)) return false;
        }
        return true;
    }

    public Universe(List<GroundEphemeris.Body> catalog, List<PlanetChunkGenerator> planets) {
        this.catalog = new ArrayList<>(catalog);
        this.planets = new ArrayList<>(planets);
        if (catalog.isEmpty()) {
            if (!planets.isEmpty())
                throw new IllegalArgumentException("Universe planets without bodies");
        } else if (new GroundEphemeris.Model(catalog).count() != planets.size())
            throw new IllegalArgumentException("Universe surface counts disagree");
    }

    public synchronized List<GroundEphemeris.Body> catalog() {
        return List.copyOf(catalog);
    }

    public synchronized List<GroundEphemeris.Star> stars() {
        return catalog.isEmpty() ? List.of() : new GroundEphemeris.Model(catalog).everyStar();
    }

    public synchronized List<Body> bodies() {
        if (catalog.isEmpty()) return List.of();
        var result = new ArrayList<Body>();
        var model = new GroundEphemeris.Model(catalog);
        for (int slot = 0; slot < planets.size(); slot++) {
            var orbit = model.orbit(slot);
            result.add(
                    new Body(
                            orbit.parent(),
                            orbit.radius(),
                            orbit.period(),
                            orbit.phase(),
                            planets.get(slot)));
        }
        return List.copyOf(result);
    }

    public synchronized List<PlanetChunkGenerator> planets() {
        return List.copyOf(planets);
    }

    private int addSun(Point position, double radius) {
        int parent = catalog.size();
        catalog.add(
                new GroundEphemeris.Body(new GroundEphemeris.Fixed(position), radius, true, -1));
        return parent;
    }

    private void addPlanet(Body body, GroundEphemeris.Model home) {
        var template = home.orbit(0);
        var spin = home.count() > 1 ? home.spin(1) : DISCOVERY_SPIN;
        var orbit =
                new GroundEphemeris.Orbit(
                        body.parent(),
                        template.axis(),
                        template.radial(),
                        body.radius(),
                        body.period(),
                        body.phase(),
                        template.epoch(),
                        spin);
        catalog.add(new GroundEphemeris.Body(orbit, 0, false, planets.size()));
        planets.add(body.planet());
    }

    public synchronized int explored() {
        return cells.size();
    }

    static long cell(int x, int z) {
        return (long) x << 32 | z & 0xffffffffL;
    }

    /** The cell of the grid a place lies in, counted from the home sun. */
    public static int[] cell(UniverseLayout layout, GroundEphemeris.Model home, Point world) {
        var d = world.sub(home.origin());
        return new int[] {
            (int) Math.round(d.x() / layout.gap()), (int) Math.round(d.z() / layout.gap())
        };
    }

    /** Looks into one cell of an endless universe: whatever its seed has there is added, once. A cell is the same
     *  whenever and from wherever it is first reached, because only the world's seed and the cell decide it.
     *  @return whether a sun was added; room says how many more planets the world can hold */
    public synchronized boolean discover(
            long seed, UniverseLayout layout, GroundEphemeris.Model home, int x, int z, int room) {
        if (!layout.endless() || x == 0 && z == 0 || cells.contains(cell(x, z))) return false;
        long mixed =
                (seed ^ 0x43756265506C616EL) + x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL;
        var random = new SplittableRandom(mixed);
        double jx = random.nextDouble() - .5,
                jy = random.nextDouble() - .5,
                jz = random.nextDouble() - .5;
        boolean sun = random.nextDouble() < layout.density();
        int radius = 384 + random.nextInt(257);
        int planets =
                layout.minPlanets() + random.nextInt(layout.maxPlanets() - layout.minPlanets() + 1);
        var position =
                home.origin()
                        .add(
                                new Point(
                                        (x + jx * .5) * layout.gap(),
                                        jy * layout.gap() * .7,
                                        (z + jz * .5) * layout.gap()));
        if (protectHome
                && !clearOfHome(
                        position,
                        home.orbit(0).radius()
                                + (layout.maxPlanets() - 1) * layout.ring()
                                + new PlanetShape(2048).radius()
                                + GroundEphemeris.Body.DEFAULT_REACH
                                + SystemDefinition.MARGIN / 2,
                        home)) {
            cells.add(cell(x, z));
            setDirty();
            return false;
        }
        // A cell that cannot be held yet is left unlooked-into, to be found when there is room.
        if (sun && planets > room) return false;
        cells.add(cell(x, z));
        setDirty();
        if (!sun) return false;
        int weights = layout.types().stream().mapToInt(UniverseLayout.Choice::weight).sum();
        int parent = addSun(position, radius);
        for (int place = 0; place < planets; place++) {
            int pick = random.nextInt(weights);
            var type = layout.types().get(0);
            for (var choice : layout.types()) {
                if (pick < choice.weight()) {
                    type = choice;
                    break;
                }
                pick -= choice.weight();
            }
            double orbit = home.orbit(0).radius() + place * layout.ring();
            addPlanet(
                    new Body(
                            parent,
                            orbit,
                            home.orbit(0).period() * Math.pow(orbit / home.orbit(0).radius(), 1.5),
                            random.nextDouble() * 2 * Math.PI,
                            type.type()
                                    .value()
                                    .planet(random.nextLong(), java.util.Optional.empty())
                                    .identity(type.typeId(), java.util.Optional.empty())),
                    home);
        }
        return true;
    }

    /** Looks into every cell within reach of a cell. @return whether anything was added */
    public synchronized boolean explore(
            long seed,
            UniverseLayout layout,
            GroundEphemeris.Model home,
            int x,
            int z,
            int reach,
            int capacity) {
        boolean added = false;
        for (int dx = -reach; dx <= reach; dx++)
            for (int dz = -reach; dz <= reach; dz++)
                added |= discover(seed, layout, home, x + dx, z + dz, capacity - planets.size());
        return added;
    }

    /** Further suns stand in the cells of a grid a gap wide about the home sun, each somewhere within the middle half
     *  of its cell and up to a third of a gap above or below the plane; the nearest are taken. No two are therefore
     *  nearer than half a gap. Each has its planets a ring
     *  apart, of types drawn by weight, each planet with a seed of its own that chooses its size and its ground. */
    /** As many planets as a world holds. Storage has room for far more; what bounds it is that the model of them all
     *  goes to a client in one packet, of at most a mebibyte, at about 550 bytes a planet. */
    public static final int CAPACITY = 1600;

    /** How many cells about a traveller are looked into. */
    public static final int REACH = 1;

    public static Universe generate(long seed, UniverseLayout layout, GroundEphemeris.Model home) {
        return generate(seed, layout, home, false);
    }

    public static Universe generate(
            long seed, UniverseLayout layout, GroundEphemeris.Model home, boolean protectHome) {
        // An endless universe begins with the cells about home; the rest are found as someone comes
        // near them.
        if (layout.endless()) {
            var endless = new Universe(List.of(), List.of());
            endless.protectHome = protectHome;
            endless.explore(seed, layout, home, 0, 0, REACH, CAPACITY - home.count());
            return endless;
        }
        var random = new SplittableRandom(seed ^ 0x43756265506C616EL);
        int count = layout.minStars() + random.nextInt(layout.maxStars() - layout.minStars() + 1);
        var places = new ArrayList<Point>();
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) {
                double jx = random.nextDouble() - .5,
                        jy = random.nextDouble() - .5,
                        jz = random.nextDouble() - .5;
                if (x == 0 && z == 0) continue;
                places.add(
                        new Point(
                                (x + jx * .5) * layout.gap(),
                                jy * layout.gap() * .7,
                                (z + jz * .5) * layout.gap()));
            }
        places.sort(java.util.Comparator.comparingDouble(Point::length));
        int weights = layout.types().stream().mapToInt(UniverseLayout.Choice::weight).sum();
        var universe = new Universe(List.of(), List.of());
        for (int cell = 0, suns = 0; suns < count && cell < places.size(); cell++) {
            var position = home.origin().add(places.get(cell));
            if (protectHome
                    && !clearOfHome(
                            position,
                            home.orbit(0).radius()
                                    + (layout.maxPlanets() - 1) * layout.ring()
                                    + new PlanetShape(2048).radius()
                                    + GroundEphemeris.Body.DEFAULT_REACH
                                    + SystemDefinition.MARGIN / 2,
                            home)) continue;
            int sunRadius = 384 + random.nextInt(257);
            int planets =
                    layout.minPlanets()
                            + random.nextInt(layout.maxPlanets() - layout.minPlanets() + 1);
            if (protectHome && home.count() + universe.planets.size() + planets > CAPACITY) break;
            int parent = universe.addSun(position, sunRadius);
            suns++;
            for (int place = 0; place < planets; place++) {
                int pick = random.nextInt(weights);
                var type = layout.types().get(0);
                for (var choice : layout.types()) {
                    if (pick < choice.weight()) {
                        type = choice;
                        break;
                    }
                    pick -= choice.weight();
                }
                double radius = home.orbit(0).radius() + place * layout.ring();
                universe.addPlanet(
                        new Body(
                                parent,
                                radius,
                                home.orbit(0).period()
                                        * Math.pow(radius / home.orbit(0).radius(), 1.5),
                                random.nextDouble() * 2 * Math.PI,
                                type.type()
                                        .value()
                                        .planet(random.nextLong(), java.util.Optional.empty())
                                        .identity(type.typeId(), java.util.Optional.empty())),
                        home);
            }
        }
        return universe;
    }

    /** The home system's model with this universe's suns and planets after it, in storage order. */
    public synchronized GroundEphemeris.Model model(GroundEphemeris.Model home) {
        var all = new ArrayList<>(home.bodies());
        int first = all.size();
        for (var body : catalog) {
            GroundEphemeris.Motion motion = body.motion();
            if (motion instanceof GroundEphemeris.Orbit orbit)
                motion =
                        new GroundEphemeris.Orbit(
                                first + orbit.parent(),
                                orbit.axis(),
                                orbit.radial(),
                                orbit.radius(),
                                orbit.period(),
                                orbit.phase(),
                                orbit.epoch(),
                                orbit.spin());
            all.add(
                    new GroundEphemeris.Body(
                            motion,
                            body.radius(),
                            body.light(),
                            body.slot() < 0 ? -1 : home.count() + body.slot(),
                            body.reach()));
        }
        return new GroundEphemeris.Model(all);
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        var ops = RegistryOps.create(NbtOps.INSTANCE, lookup);
        tag.putInt("format", 3);
        tag.put(
                "bodies",
                SystemSkyCodec.BODY.listOf().encodeStart(NbtOps.INSTANCE, catalog()).getOrThrow());
        var entries = new ListTag();
        for (var planet : planets)
            entries.add(PlanetChunkGenerator.CODEC.codec().encodeStart(ops, planet).getOrThrow());
        tag.put("planets", entries);
        if (!cells.isEmpty())
            tag.putLongArray("cells", cells.stream().sorted().mapToLong(Long::longValue).toArray());
        return tag;
    }

    public static Universe load(CompoundTag tag, HolderLookup.Provider lookup) {
        if (!tag.contains("format", 3)
                || tag.getInt("format") != 3
                || !tag.contains("bodies", 9)
                || !tag.contains("planets", 9))
            throw new IllegalArgumentException(
                    "Unsupported universe format; expected 3 (old saves are not supported)");
        var ops = RegistryOps.create(NbtOps.INSTANCE, lookup);
        var catalog =
                SystemSkyCodec.BODY
                        .listOf()
                        .parse(NbtOps.INSTANCE, tag.get("bodies"))
                        .getOrThrow(IllegalArgumentException::new);
        var planets = new ArrayList<PlanetChunkGenerator>();
        for (var raw : tag.getList("planets", 10))
            planets.add(
                    PlanetChunkGenerator.CODEC
                            .codec()
                            .parse(ops, raw)
                            .getOrThrow(IllegalArgumentException::new));
        var universe = new Universe(catalog, planets);
        if (tag.contains("cells", 12))
            for (long cell : tag.getLongArray("cells")) universe.cells.add(cell);
        return universe;
    }

    /** The universe of a system that has a layout: read from the world, or made of the world's seed the first time.
     *  The system's generator is told its planets, so that everything after counts and addresses them. */
    public static Universe bind(ServerLevel level, SystemChunkGenerator system) {
        if (system.universe().isEmpty()) return null;
        var storage = level.getDataStorage();
        var universe =
                storage.computeIfAbsent(
                        new Factory<>(
                                () -> {
                                    if (((dev.starfold.mixin.SavedDataFileAccess) storage)
                                            .starfold$spaceDataFile(FILE)
                                            .exists())
                                        throw new IllegalStateException(
                                                "Existing universe failed to load; refusing to make another over it");
                                    var made =
                                            generate(
                                                    level.getSeed(),
                                                    system.universe().get().value(),
                                                    system.homeModel(),
                                                    system.authored());
                                    made.setDirty();
                                    return made;
                                },
                                Universe::load,
                                null),
                        FILE);
        universe.protectHome = system.authored();
        system.bind(universe.planets());
        return universe;
    }

    /** A traveller is somewhere: the cells about them are looked into, and if that added anything the level is told —
     *  the generator its planets, the table their shapes, the clock their motion and every player all three. */
    public static boolean grow(ServerLevel level, Point world) {
        if (!(level.getChunkSource().getGenerator() instanceof SystemChunkGenerator system)
                || system.universe().isEmpty()) return false;
        var layout = system.universe().get().value();
        if (!layout.endless()) return false;
        var universe = of(level);
        if (universe == null) return false;
        var home = system.homeModel();
        int[] at = cell(layout, home, world);
        if (!universe.explore(
                level.getSeed(), layout, home, at[0], at[1], REACH, CAPACITY - home.count()))
            return false;
        system.bind(universe.planets());
        grown.accept(
                level,
                system,
                () -> {
                    var clock = GroundClock.get(level);
                    clock.clock.model = universe.model(home);
                    clock.setDirty();
                    return clock;
                });
        return true;
    }

    /** The universe this level was loaded with, or null where its system has none. */
    public static Universe of(ServerLevel level) {
        return level.getChunkSource().getGenerator() instanceof SystemChunkGenerator system
                        && system.universe().isPresent()
                ? level.getDataStorage()
                        .get(
                                new Factory<>(
                                        () -> {
                                            throw new IllegalStateException("Universe not bound");
                                        },
                                        Universe::load,
                                        null),
                                FILE)
                : null;
    }
}
