package dev.starfold.universe;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;

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

    /** A planet on its circle about one of the universe's suns. */
    public record Body(
            int star, double radius, double period, double phase, PlanetChunkGenerator planet) {
        public Body {
            if (star < 0
                    || planet == null
                    || !Double.isFinite(radius)
                    || !Double.isFinite(period)
                    || !Double.isFinite(phase)) throw new IllegalArgumentException("Universe body");
        }
    }

    /** Suns and planets are only ever added, at the end: a planet keeps its number, and with it its storage. */
    private final List<GroundEphemeris.Star> stars;

    private final List<Body> bodies;

    /** The cells of an endless universe that have been looked into, whether or not a sun was there. */
    private final java.util.Set<Long> cells = new java.util.HashSet<>();

    private boolean protectHome;

    /** Authored suns need not sit inside the home grid cell. Reserve their entire orbit envelopes, not just their centres. */
    public static boolean clearOfHome(Point position, double reach, GroundEphemeris.Model home) {
        double surface =
                new PlanetShape(PlanetShape.MAX).radius() + Space.OUT + SystemDefinition.MARGIN / 2;
        for (var star : home.everyStar()) {
            double outer = star.position().sub(home.center()).length() < 1e-8 ? home.radius() : 0;
            for (var orbit : home.more())
                if (orbit.center().sub(star.position()).length() < 1e-8)
                    outer = Math.max(outer, orbit.radius());
            if (position.sub(star.position()).length() < reach + outer + surface) return false;
        }
        return true;
    }

    public Universe(List<GroundEphemeris.Star> stars, List<Body> bodies) {
        this.stars = new ArrayList<>(stars);
        this.bodies = new ArrayList<>(bodies);
        for (var body : bodies)
            if (body.star() >= stars.size())
                throw new IllegalArgumentException("Universe body without its sun");
    }

    public synchronized List<GroundEphemeris.Star> stars() {
        return List.copyOf(stars);
    }

    public synchronized List<Body> bodies() {
        return List.copyOf(bodies);
    }

    public synchronized List<PlanetChunkGenerator> planets() {
        return bodies.stream().map(Body::planet).toList();
    }

    public synchronized int explored() {
        return cells.size();
    }

    static long cell(int x, int z) {
        return (long) x << 32 | z & 0xffffffffL;
    }

    /** The cell of the grid a place lies in, counted from the home sun. */
    public static int[] cell(UniverseLayout layout, GroundEphemeris.Model home, Point world) {
        var d = world.sub(home.center());
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
                home.center()
                        .add(
                                new Point(
                                        (x + jx * .5) * layout.gap(),
                                        jy * layout.gap() * .7,
                                        (z + jz * .5) * layout.gap()));
        if (protectHome
                && !clearOfHome(
                        position,
                        home.radius()
                                + (layout.maxPlanets() - 1) * layout.ring()
                                + new PlanetShape(2048).radius()
                                + Space.OUT
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
        int star = stars.size();
        stars.add(new GroundEphemeris.Star(position, radius));
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
            double orbit = home.radius() + place * layout.ring();
            bodies.add(
                    new Body(
                            star,
                            orbit,
                            home.period() * Math.pow(orbit / home.radius(), 1.5),
                            random.nextDouble() * 2 * Math.PI,
                            type.type()
                                    .value()
                                    .planet(random.nextLong(), java.util.Optional.empty())
                                    .identity(type.typeId(), java.util.Optional.empty())));
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
                added |= discover(seed, layout, home, x + dx, z + dz, capacity - bodies.size());
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
        var stars = new ArrayList<GroundEphemeris.Star>();
        var bodies = new ArrayList<Body>();
        for (int cell = 0; stars.size() < count && cell < places.size(); cell++) {
            var position = home.center().add(places.get(cell));
            if (protectHome
                    && !clearOfHome(
                            position,
                            home.radius()
                                    + (layout.maxPlanets() - 1) * layout.ring()
                                    + new PlanetShape(2048).radius()
                                    + Space.OUT
                                    + SystemDefinition.MARGIN / 2,
                            home)) continue;
            int star = stars.size(), sunRadius = 384 + random.nextInt(257);
            int planets =
                    layout.minPlanets()
                            + random.nextInt(layout.maxPlanets() - layout.minPlanets() + 1);
            if (protectHome && home.count() + bodies.size() + planets > CAPACITY) break;
            stars.add(new GroundEphemeris.Star(position, sunRadius));
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
                double radius = home.radius() + place * layout.ring();
                bodies.add(
                        new Body(
                                star,
                                radius,
                                home.period() * Math.pow(radius / home.radius(), 1.5),
                                random.nextDouble() * 2 * Math.PI,
                                type.type()
                                        .value()
                                        .planet(random.nextLong(), java.util.Optional.empty())
                                        .identity(type.typeId(), java.util.Optional.empty())));
            }
        }
        return new Universe(stars, bodies);
    }

    /** The home system's model with this universe's suns and planets after it, in storage order. */
    public synchronized GroundEphemeris.Model model(GroundEphemeris.Model home) {
        var more = new ArrayList<>(home.more());
        var all = new ArrayList<>(home.stars());
        all.addAll(stars);
        for (var body : bodies)
            more.add(
                    new GroundEphemeris.Orbit(
                            stars.get(body.star()).position(),
                            home.axis(),
                            home.radial(),
                            body.radius(),
                            body.period(),
                            body.phase(),
                            home.epoch(),
                            home.b()));
        return new GroundEphemeris.Model(
                home.center(),
                home.axis(),
                home.radial(),
                home.radius(),
                home.period(),
                home.phase(),
                home.epoch(),
                home.a(),
                home.b(),
                home.sun(),
                true,
                more,
                all);
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        var ops = RegistryOps.create(NbtOps.INSTANCE, lookup);
        tag.putInt("format", 1);
        var suns = new ListTag();
        for (var star : stars) {
            var t = new CompoundTag();
            t.putDouble("x", star.position().x());
            t.putDouble("y", star.position().y());
            t.putDouble("z", star.position().z());
            t.putDouble("radius", star.radius());
            suns.add(t);
        }
        var planets = new ListTag();
        for (var body : bodies) {
            var t = new CompoundTag();
            t.putInt("star", body.star());
            t.putDouble("radius", body.radius());
            t.putDouble("period_ticks", body.period());
            t.putDouble("phase", body.phase());
            t.put(
                    "planet",
                    PlanetChunkGenerator.CODEC
                            .codec()
                            .encodeStart(ops, body.planet())
                            .getOrThrow());
            planets.add(t);
        }
        tag.put("stars", suns);
        tag.put("planets", planets);
        if (!cells.isEmpty())
            tag.putLongArray("cells", cells.stream().sorted().mapToLong(Long::longValue).toArray());
        return tag;
    }

    public static Universe load(CompoundTag tag, HolderLookup.Provider lookup) {
        if (!tag.contains("format", 3)
                || tag.getInt("format") != 1
                || !tag.contains("stars", 9)
                || !tag.contains("planets", 9))
            throw new IllegalArgumentException("Universe format");
        var ops = RegistryOps.create(NbtOps.INSTANCE, lookup);
        var stars = new ArrayList<GroundEphemeris.Star>();
        for (var raw : tag.getList("stars", 10)) {
            var t = (CompoundTag) raw;
            for (var key : List.of("x", "y", "z", "radius"))
                if (!t.contains(key, 6)) throw new IllegalArgumentException("Universe sun " + key);
            stars.add(
                    new GroundEphemeris.Star(
                            new Point(t.getDouble("x"), t.getDouble("y"), t.getDouble("z")),
                            t.getDouble("radius")));
        }
        var bodies = new ArrayList<Body>();
        for (var raw : tag.getList("planets", 10)) {
            var t = (CompoundTag) raw;
            if (!t.contains("star", 3)
                    || !t.contains("radius", 6)
                    || !t.contains("period_ticks", 6)
                    || !t.contains("phase", 6)
                    || !t.contains("planet", 10))
                throw new IllegalArgumentException("Universe planet");
            bodies.add(
                    new Body(
                            t.getInt("star"),
                            t.getDouble("radius"),
                            t.getDouble("period_ticks"),
                            t.getDouble("phase"),
                            PlanetChunkGenerator.CODEC
                                    .codec()
                                    .parse(ops, t.getCompound("planet"))
                                    .getOrThrow()));
        }
        var universe = new Universe(stars, bodies);
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
