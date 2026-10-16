package dev.starfold.universe;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetIdentity;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.planet.PlanetSystem;

import net.minecraft.core.*;
import net.minecraft.resources.*;

import java.util.*;

/** A hand-written sky. Types are consulted only while making a new world's planets; storage zero is its home. */
public record SystemDefinition(List<Sun> suns, Home home, Optional<Holder<UniverseLayout>> beyond) {
    public static final ResourceKey<Registry<SystemDefinition>> KEY =
            ResourceKey.createRegistryKey(
                    ResourceLocation.fromNamespaceAndPath("starfold", "system"));
    public static final double MARGIN = 256;
    private static final Codec<Point> POINT =
            Codec.DOUBLE
                    .listOf()
                    .comapFlatMap(
                            v ->
                                    v.size() == 3 && v.stream().allMatch(Double::isFinite)
                                            ? DataResult.success(
                                                    new Point(v.get(0), v.get(1), v.get(2)))
                                            : DataResult.error(
                                                    () ->
                                                            "system position needs three finite block coordinates"),
                            p -> List.of(p.x(), p.y(), p.z()));

    public record Path(double radius, double period, double phase) {
        static final Codec<Path> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                Codec.DOUBLE
                                                        .fieldOf("radius")
                                                        .forGetter(Path::radius),
                                                Codec.DOUBLE
                                                        .fieldOf("period")
                                                        .forGetter(Path::period),
                                                Codec.DOUBLE
                                                        .optionalFieldOf("phase", 0.0)
                                                        .forGetter(Path::phase))
                                        .apply(i, Path::new));

        public Path {
            range(radius, 1, 1e7, "orbit.radius");
            range(period, 12000, 1e9, "orbit.period (ticks)");
            if (!Double.isFinite(phase))
                throw new IllegalArgumentException("orbit.phase must be finite radians");
        }
    }

    public record Rotation(double period, double tilt) {
        static final Codec<Rotation> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                Codec.DOUBLE
                                                        .fieldOf("period")
                                                        .forGetter(Rotation::period),
                                                Codec.DOUBLE
                                                        .optionalFieldOf("tilt", 0.0)
                                                        .forGetter(Rotation::tilt))
                                        .apply(i, Rotation::new));

        public Rotation {
            range(period, 24000, 1e9, "spin.period (ticks)");
            range(tilt, -180, 180, "spin.tilt (degrees)");
        }

        GroundEphemeris.Spin spin() {
            double angle = Math.toRadians(tilt);
            return new GroundEphemeris.Spin(
                    true,
                    new Point(Math.sin(angle), Math.cos(angle), 0),
                    new org.joml.Quaterniond(),
                    0,
                    period,
                    0);
        }
    }

    public record Planet(
            Holder<PlanetType> type,
            Optional<Integer> size,
            Optional<Long> seed,
            Optional<String> name,
            Path orbit,
            Optional<Rotation> spin,
            Optional<ResourceLocation> typeId,
            double reach,
            List<Planet> moons) {
        public Planet(
                Holder<PlanetType> type,
                Optional<Integer> size,
                Optional<Long> seed,
                Optional<String> name,
                Path orbit,
                Optional<Rotation> spin,
                Optional<ResourceLocation> typeId,
                double reach) {
            this(type, size, seed, name, orbit, spin, typeId, reach, List.of());
        }

        public Planet(
                Holder<PlanetType> type,
                Optional<Integer> size,
                Optional<Long> seed,
                Optional<String> name,
                Path orbit,
                Optional<Rotation> spin) {
            this(
                    type,
                    size,
                    seed,
                    name,
                    orbit,
                    spin,
                    Optional.empty(),
                    GroundEphemeris.Body.DEFAULT_REACH);
        }

        public Optional<ResourceLocation> typeId() {
            return type.unwrapKey().map(ResourceKey::location).or(() -> typeId);
        }

        private static final MapCodec<Planet> SURFACE =
                RecordCodecBuilder.mapCodec(
                        i ->
                                i.group(
                                                UniverseLayout.savedInPlace(
                                                                PlanetType.KEY, PlanetType.CODEC)
                                                        .fieldOf("type")
                                                        .forGetter(Planet::type),
                                                Codec.INT
                                                        .optionalFieldOf("size")
                                                        .forGetter(Planet::size),
                                                Codec.LONG
                                                        .optionalFieldOf("seed")
                                                        .forGetter(Planet::seed),
                                                Codec.STRING
                                                        .optionalFieldOf("name")
                                                        .forGetter(Planet::name),
                                                Path.CODEC
                                                        .fieldOf("orbit")
                                                        .forGetter(Planet::orbit),
                                                Rotation.CODEC
                                                        .optionalFieldOf("spin")
                                                        .forGetter(Planet::spin),
                                                ResourceLocation.CODEC
                                                        .optionalFieldOf("type_id")
                                                        .forGetter(Planet::typeId),
                                                Codec.doubleRange(
                                                                512,
                                                                GroundEphemeris.Body.DEFAULT_REACH)
                                                        .optionalFieldOf(
                                                                "reach",
                                                                GroundEphemeris.Body.DEFAULT_REACH)
                                                        .forGetter(Planet::reach))
                                        .apply(i, Planet::new));

        private static final Codec<Planet> MOON =
                new Codec<>() {
                    @Override
                    public <T> DataResult<com.mojang.datafixers.util.Pair<Planet, T>> decode(
                            DynamicOps<T> ops, T input) {
                        if (ops.get(input, "moons").result().isPresent())
                            return DataResult.error(() -> "A moon cannot have moons");
                        return SURFACE.codec().decode(ops, input);
                    }

                    @Override
                    public <T> DataResult<T> encode(Planet value, DynamicOps<T> ops, T prefix) {
                        if (!value.moons.isEmpty())
                            return DataResult.error(() -> "A moon cannot have moons");
                        return SURFACE.codec().encode(value, ops, prefix);
                    }
                };

        static final Codec<Planet> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                SURFACE.forGetter((Planet p) -> p),
                                                strict(MOON.listOf())
                                                        .optionalFieldOf("moons", List.of())
                                                        .forGetter(Planet::moons))
                                        .apply(
                                                i,
                                                (p, moons) ->
                                                        new Planet(
                                                                p.type, p.size, p.seed, p.name,
                                                                p.orbit, p.spin, p.typeId, p.reach,
                                                                moons)));

        public Planet {
            moons = List.copyOf(moons);
            if (moons.stream().anyMatch(m -> !m.moons.isEmpty()))
                throw new IllegalArgumentException("A moon cannot have moons");
            range(reach, 512, GroundEphemeris.Body.DEFAULT_REACH, "planet.reach");
            size.ifPresent(
                    s -> {
                        try {
                            new PlanetShape(s);
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException(
                                    "system planet size must be 256..2048 and a multiple of 16", e);
                        }
                    });
            name.ifPresent(SystemDefinition::validName);
        }

        GroundEphemeris.Spin rotation() {
            return spin.map(Rotation::spin)
                    .orElseGet(
                            () ->
                                    new GroundEphemeris.Spin(
                                            false,
                                            GroundEphemeris.Y,
                                            new org.joml.Quaterniond(),
                                            0,
                                            24000,
                                            0));
        }
    }

    public record Sun(Point position, double radius, List<Planet> planets) {
        static final Codec<Sun> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                POINT.fieldOf("position").forGetter(Sun::position),
                                                Codec.DOUBLE
                                                        .fieldOf("radius")
                                                        .forGetter(Sun::radius),
                                                strict(Planet.CODEC.listOf())
                                                        .fieldOf("planets")
                                                        .forGetter(Sun::planets))
                                        .apply(i, Sun::new));

        public Sun {
            planets = List.copyOf(planets);
            if (position == null || !position.finite() || position.length() > 1e9)
                throw new IllegalArgumentException(
                        "sun.position must be finite and within 1000000000 blocks of origin");
            range(radius, 32, 4096, "sun.radius");
            if (planets.isEmpty())
                throw new IllegalArgumentException("Each sun needs at least one planet");
        }
    }

    public record Home(int sun, int planet) {
        static final Codec<Home> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                Codec.INT
                                                        .optionalFieldOf("sun", 0)
                                                        .forGetter(Home::sun),
                                                Codec.INT
                                                        .optionalFieldOf("planet", 0)
                                                        .forGetter(Home::planet))
                                        .apply(i, Home::new));
    }

    public static final Codec<SystemDefinition> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            strict(Sun.CODEC.listOf())
                                                    .fieldOf("suns")
                                                    .forGetter(SystemDefinition::suns),
                                            Home.CODEC
                                                    .optionalFieldOf("home", new Home(0, 0))
                                                    .forGetter(SystemDefinition::home),
                                            UniverseLayout.HOLDER
                                                    .optionalFieldOf("beyond")
                                                    .forGetter(SystemDefinition::beyond))
                                    .apply(i, SystemDefinition::new));
    public static final Codec<Holder<SystemDefinition>> HOLDER =
            UniverseLayout.savedInPlace(KEY, CODEC);

    /** Never construct a smaller system from a list codec's partial result when one entry names an unknown type. */
    private static <A> Codec<A> strict(Codec<A> codec) {
        return new Codec<>() {
            @Override
            public <T> DataResult<com.mojang.datafixers.util.Pair<A, T>> decode(
                    DynamicOps<T> ops, T input) {
                var result = codec.decode(ops, input);
                return result.error().isPresent()
                        ? DataResult.error(() -> result.error().orElseThrow().message())
                        : result;
            }

            @Override
            public <T> DataResult<T> encode(A value, DynamicOps<T> ops, T prefix) {
                return codec.encode(value, ops, prefix);
            }
        };
    }

    public SystemDefinition {
        suns = List.copyOf(suns);
        long count =
                suns.stream()
                        .flatMap(s -> s.planets.stream())
                        .mapToLong(p -> 1L + p.moons.size())
                        .sum();
        if (count < 1 || count > Universe.CAPACITY || count > PlanetStorage.MAX_PLANETS)
            throw new IllegalArgumentException(
                    "system needs 1..1600 planets (synchronised model capacity)");
        if (home.sun < 0
                || home.sun >= suns.size()
                || home.planet < 0
                || home.planet >= suns.get(home.sun).planets.size())
            throw new IllegalArgumentException(
                    "home.sun/home.planet must index an existing sun and planet (zero based)");
        var sun = suns.get(home.sun);
        if (sun.position.length() > 100000 || sun.radius > 1024)
            throw new IllegalArgumentException(
                    "home sun position must be within 100000 blocks of origin and radius at most 1024");
    }

    private static void range(double n, double min, double max, String field) {
        if (!Double.isFinite(n) || n < min || n > max)
            throw new IllegalArgumentException(
                    field + " must be finite in [" + min + ", " + max + "]");
    }

    public static void validName(String name) {
        PlanetIdentity.validName(name);
    }

    /** Seedless validation uses the largest possible size, so choosing another world seed cannot invalidate a pack. */
    public void validate() {
        if (beyond.isPresent()) {
            var layout = beyond.get().value();
            var path = suns.get(home.sun).planets.get(home.planet).orbit;
            double outer = path.radius + (layout.maxPlanets() - 1) * layout.ring();
            double first =
                    640
                            + new PlanetShape(PlanetShape.MAX).radius()
                            + GroundEphemeris.Body.DEFAULT_REACH
                            + MARGIN / 2;
            if (path.radius <= first || layout.gap() < 4 * outer + 16384)
                throw new IllegalArgumentException(
                        "beyond rings need home orbit radius above "
                                + first
                                + " and star_gap at least "
                                + (4 * outer + 16384));
            if (path.period * Math.pow(outer / path.radius, 1.5) > 1e9)
                throw new IllegalArgumentException(
                        "beyond outer orbit period exceeds 1000000000 ticks; shorten the home period or reduce beyond rings");
        }
        double[] reaches = new double[suns.size()];
        for (int s = 0; s < suns.size(); s++) {
            var sun = suns.get(s);
            double[] radii = new double[sun.planets.size()];
            double[] families = new double[radii.length];
            for (int p = 0; p < radii.length; p++) {
                var planet = sun.planets.get(p);
                try {
                    var type = planet.type.value();
                    int size = planet.size.orElse(type.maxSize());
                    type.generation(size);
                    radii[p] = new PlanetShape(size).radius();
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(
                            "sun "
                                    + s
                                    + " planet "
                                    + p
                                    + " type "
                                    + planet.type
                                            .unwrapKey()
                                            .map(k -> k.location().toString())
                                            .orElse("inline")
                                    + ": "
                                    + e.getMessage(),
                            e);
                }
                families[p] = family(planet, radii[p], "sun " + s + " planet " + p);
                double reach = families[p] + MARGIN / 2;
                if (planet.orbit.radius <= sun.radius + reach)
                    throw new IllegalArgumentException(
                            "sun "
                                    + s
                                    + " planet "
                                    + p
                                    + " orbit is too close to its sun; radius must exceed "
                                    + (sun.radius + reach));
                reaches[s] = Math.max(reaches[s], planet.orbit.radius + reach);
                for (int q = 0; q < p; q++) {
                    var other = sun.planets.get(q);
                    double distance = distance(planet.orbit, other.orbit);
                    double required =
                            planet.moons.isEmpty() && other.moons.isEmpty()
                                    ? radii[p] + radii[q] + (planet.reach + other.reach) + MARGIN
                                    : families[p] + families[q] + MARGIN;
                    if (!Double.isFinite(distance) || distance < required)
                        throw new IllegalArgumentException(
                                "sun "
                                        + s
                                        + " planets "
                                        + q
                                        + " and "
                                        + p
                                        + " orbits too close: minimum "
                                        + distance
                                        + ", need "
                                        + required
                                        + "; separate rings, or use equal periods and separated phases");
                }
            }
            // Own-flight saves reject absolute positions beyond this bound; do not offer an
            // unreachable authored planet.
            if (sun.position.length() + reaches[s] > 200000)
                throw new IllegalArgumentException(
                        "sun "
                                + s
                                + " orbit envelope must stay within 200000 blocks of origin (flight save limit)");
            for (int t = 0; t < s; t++)
                if (sun.position.sub(suns.get(t).position).length() < reaches[s] + reaches[t])
                    throw new IllegalArgumentException(
                            "suns "
                                    + t
                                    + " and "
                                    + s
                                    + " outermost orbits overlap; separate positions by at least "
                                    + (reaches[s] + reaches[t]));
        }
    }

    private static double radius(Planet planet) {
        var type = planet.type.value();
        int size = planet.size.orElse(type.maxSize());
        type.generation(size);
        return new PlanetShape(size).radius();
    }

    /** The parent's shell and every moon's entire path must fit inside the sun's spacing envelope. */
    private static double family(Planet planet, double radius, String label) {
        double extent = radius + planet.reach;
        var shells = new double[planet.moons.size()];
        for (int m = 0; m < shells.length; m++) {
            var moon = planet.moons.get(m);
            try {
                shells[m] = radius(moon) + moon.reach;
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        label + " moon " + m + ": " + error.getMessage(), error);
            }
            double required = radius + planet.reach + shells[m] + MARGIN;
            if (moon.orbit.radius < required)
                throw new IllegalArgumentException(
                        label
                                + " moon "
                                + m
                                + " orbit too close to its planet; radius must be at least "
                                + required);
            extent = Math.max(extent, moon.orbit.radius + shells[m]);
            for (int n = 0; n < m; n++) {
                double distance = distance(moon.orbit, planet.moons.get(n).orbit);
                double need = shells[m] + shells[n] + MARGIN;
                if (!Double.isFinite(distance) || distance < need)
                    throw new IllegalArgumentException(
                            label
                                    + " moons "
                                    + n
                                    + " and "
                                    + m
                                    + " orbits too close: minimum "
                                    + distance
                                    + ", need "
                                    + need
                                    + "; separate rings, or use equal periods and separated phases");
            }
        }
        return extent;
    }

    private static double distance(Path a, Path b) {
        return a.period == b.period
                ? Math.sqrt(
                        Math.pow(a.radius - b.radius, 2)
                                + 4
                                        * a.radius
                                        * b.radius
                                        * Math.pow(Math.sin((a.phase - b.phase) / 2), 2))
                : Math.abs(a.radius - b.radius);
    }

    /** This is the saved celestial source, not a reference back into a pack. The running clock copies it once. */
    public record Sky(GroundEphemeris.Model model, List<String> names, List<Integer> groups) {
        private static final Codec<GroundEphemeris.Model> MODEL = SystemSkyCodec.MODEL;
        public static final Codec<Sky> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                MODEL.fieldOf("model").forGetter(Sky::model),
                                                Codec.STRING
                                                        .listOf()
                                                        .fieldOf("names")
                                                        .forGetter(Sky::names),
                                                Codec.INT
                                                        .listOf()
                                                        .fieldOf("groups")
                                                        .forGetter(Sky::groups))
                                        .apply(i, Sky::new));

        public Sky {
            names = List.copyOf(names);
            groups = List.copyOf(groups);
            if (model.count() > Universe.CAPACITY
                    || names.size() != model.count()
                    || groups.isEmpty()
                    || groups.stream().anyMatch(n -> n < 1)
                    || groups.stream().mapToLong(Integer::longValue).sum() != names.size()
                    || groups.size() != model.everyStar().size())
                throw new IllegalArgumentException("Saved system celestial counts disagree");
            names.forEach(SystemDefinition::validName);
        }
    }

    public record Made(
            List<PlanetChunkGenerator> planets, Sky sky, Optional<Holder<UniverseLayout>> beyond) {}

    public Made materialize(long worldSeed) {
        validate();
        var planets = new ArrayList<PlanetChunkGenerator>();
        var bodies = new ArrayList<GroundEphemeris.Body>();
        var names = new ArrayList<String>();
        var groups = new ArrayList<Integer>();
        var parents = new IdentityHashMap<Planet, Integer>();
        var order = new ArrayList<Integer>();
        order.add(home.sun);
        for (int s = 0; s < suns.size(); s++) if (s != home.sun) order.add(s);
        for (int s : order) {
            var sun = suns.get(s);
            int parent = bodies.size();
            bodies.add(
                    new GroundEphemeris.Body(
                            new GroundEphemeris.Fixed(sun.position), sun.radius, true, -1));
            groups.add(sun.planets.stream().mapToInt(p -> 1 + p.moons.size()).sum());
            var places = new ArrayList<Integer>();
            if (s == home.sun) places.add(home.planet);
            for (int p = 0; p < sun.planets.size(); p++) if (!places.contains(p)) places.add(p);
            int offset = 0;
            for (int before = 0; before < s; before++) offset += suns.get(before).planets.size();
            for (int p : places) {
                var planet = sun.planets.get(p);
                long seed = planet.seed.orElse(derivedSeed(worldSeed, offset + p));
                parents.put(planet, bodies.size());
                append(planet, seed, parent, planets, bodies, names);
            }
        }
        int ordinal = planets.size();
        for (var sun : suns)
            for (var planet : sun.planets)
                for (var moon : planet.moons) {
                    long seed = moon.seed.orElse(derivedSeed(worldSeed, ordinal++));
                    append(moon, seed, parents.get(planet), planets, bodies, names);
                }
        var model = new GroundEphemeris.Model(bodies);
        return new Made(List.copyOf(planets), new Sky(model, names, groups), beyond);
    }

    private static void append(
            Planet planet,
            long seed,
            int parent,
            List<PlanetChunkGenerator> planets,
            List<GroundEphemeris.Body> bodies,
            List<String> names) {
        planets.add(
                planet.type
                        .value()
                        .planet(seed, planet.size)
                        .identity(planet.typeId(), planet.name));
        names.add(planet.name.orElse(PlanetSystem.defaultName(names.size())));
        var path = planet.orbit;
        var orbit =
                new GroundEphemeris.Orbit(
                        parent,
                        GroundEphemeris.Y,
                        new Point(0, 0, 1),
                        path.radius,
                        path.period,
                        path.phase,
                        0,
                        planet.rotation());
        bodies.add(new GroundEphemeris.Body(orbit, 0, false, planets.size() - 1, planet.reach));
    }

    /** WorldDimensions.bake runs after holder binding, but before a dedicated server saves or creates levels. */
    public static void validateDimensions(
            net.minecraft.core.Registry<net.minecraft.world.level.dimension.LevelStem> dimensions) {
        for (var entry : dimensions.entrySet())
            if (entry.getValue().generator() instanceof SystemChunkGenerator system
                    && system.authored())
                try {
                    system.validate();
                } catch (RuntimeException error) {
                    throw new IllegalArgumentException(
                            "dimension "
                                    + entry.getKey().location()
                                    + " system: "
                                    + error.getMessage(),
                            error);
                }
    }

    public static long derivedSeed(long world, int index) {
        long seed = world + 0x9E3779B97F4A7C15L * (index + 1L);
        seed = (seed ^ (seed >>> 30)) * 0xBF58476D1CE4E5B9L;
        seed = (seed ^ (seed >>> 27)) * 0x94D049BB133111EBL;
        return seed ^ (seed >>> 31);
    }

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(
                (net.neoforged.neoforge.registries.DataPackRegistryEvent.NewRegistry event) ->
                        event.dataPackRegistry(KEY, CODEC));
    }
}
