package dev.starfold.universe;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.chart.SunSettings;
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
    public static final double HOME_ORBIT = 12000 / Math.tan(Math.toRadians(1.5));
    public static final double STAR_GM =
            4 * Math.PI * Math.PI * Math.pow(HOME_ORBIT, 3) / Math.pow(8 * 2400, 2);
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

    public record Path(double radius, double phase) {
        static final Codec<Path> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                Codec.DOUBLE
                                                        .fieldOf("radius")
                                                        .forGetter(Path::radius),
                                                Codec.DOUBLE
                                                        .optionalFieldOf("phase", 0.0)
                                                        .forGetter(Path::phase))
                                        .apply(i, Path::new));

        public Path {
            range(radius, 1, 1e7, "orbit.radius");
            if (!Double.isFinite(phase))
                throw new IllegalArgumentException("orbit.phase must be finite radians");
        }

        public double period(double gm) {
            return 20 * 2 * Math.PI * Math.sqrt(radius * radius * radius / gm);
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

    /** A physical catalog body without a terrain type, generator or surface-storage slot. */
    public record GasBody(
            double radius,
            Path orbit,
            Rotation spin,
            dev.starfold.chart.GasGiant gas,
            double gm,
            List<Planet> moons) {
        public GasBody(double radius, Path orbit, Rotation spin, dev.starfold.chart.GasGiant gas) {
            this(radius, orbit, spin, gas, 80 * radius * radius, List.of());
        }

        static final Codec<GasBody> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                Codec.DOUBLE
                                                        .fieldOf("radius")
                                                        .forGetter(GasBody::radius),
                                                Path.CODEC
                                                        .fieldOf("orbit")
                                                        .forGetter(GasBody::orbit),
                                                Rotation.CODEC
                                                        .fieldOf("spin")
                                                        .forGetter(GasBody::spin),
                                                dev.starfold.chart.GasGiant.CODEC
                                                        .fieldOf("gas")
                                                        .forGetter(GasBody::gas),
                                                Codec.DOUBLE.optionalFieldOf("gm")
                                                        .forGetter(g -> Optional.of(g.gm)),
                                                strict(Planet.MOON.listOf())
                                                        .optionalFieldOf("moons", List.of())
                                                        .forGetter(GasBody::moons))
                                        .apply(
                                                i,
                                                (radius, orbit, spin, gas, gm, moons) ->
                                                        new GasBody(
                                                                radius,
                                                                orbit,
                                                                spin,
                                                                gas,
                                                                gm.orElse(80 * radius * radius),
                                                                moons)));

        public GasBody {
            Objects.requireNonNull(orbit);
            Objects.requireNonNull(spin);
            Objects.requireNonNull(gas).validateRadius(radius);
            range(gm, 1, 1e30, "gas.gm");
            moons = List.copyOf(moons);
            if (moons.stream().anyMatch(m -> !m.moons.isEmpty()))
                throw new IllegalArgumentException("A moon cannot have moons");
        }

        double shell() {
            return radius + gas.outerHeight();
        }
    }

    public record Sun(
            Point position,
            double radius,
            List<Planet> planets,
            List<GasBody> gasGiants,
            double gm) {
        public Sun(Point position, double radius, List<Planet> planets, List<GasBody> gasGiants) {
            this(position, radius, planets, gasGiants, STAR_GM);
        }

        public Sun(Point position, double radius, List<Planet> planets) {
            this(position, radius, planets, List.of());
        }

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
                                                        .forGetter(Sun::planets),
                                                strict(GasBody.CODEC.listOf())
                                                        .optionalFieldOf("gas_giants", List.of())
                                                        .forGetter(Sun::gasGiants),
                                                Codec.DOUBLE.optionalFieldOf("gm")
                                                        .forGetter(s -> Optional.of(s.gm)))
                                        .apply(
                                                i,
                                                (position, radius, planets, gasGiants, gm) ->
                                                        new Sun(
                                                                position,
                                                                radius,
                                                                planets,
                                                                gasGiants,
                                                                gm.orElse(STAR_GM))));

        public Sun {
            planets = List.copyOf(planets);
            gasGiants = List.copyOf(gasGiants);
            if (gasGiants.size() > dev.starfold.chart.GasGiant.MAX_PER_SUN)
                throw new IllegalArgumentException("At most eight gas giants per sun");
            if (position == null || !position.finite() || position.length() > 1e9)
                throw new IllegalArgumentException(
                        "sun.position must be finite and within 1000000000 blocks of origin");
            range(radius, 32, 16384, "sun.radius");
            range(gm, 1, 1e30, "sun.gm");
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
        if (suns.stream().mapToLong(s -> s.gasGiants.size()).sum()
                > dev.starfold.chart.GasGiant.MAX_COUNT)
            throw new IllegalArgumentException("At most 64 authored gas giants");
        long count =
                suns.stream()
                        .mapToLong(
                                s ->
                                        s.planets.stream()
                                                        .mapToLong(p -> 1L + p.moons.size())
                                                        .sum()
                                                + s.gasGiants.stream()
                                                        .mapToLong(g -> g.moons.size())
                                                        .sum())
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
    }

    private static void range(double n, double min, double max, String field) {
        if (!Double.isFinite(n) || n < min || n > max)
            throw new IllegalArgumentException(
                    field + " must be finite in [" + min + ", " + max + "]");
    }

    public static void validName(String name) {
        PlanetIdentity.validName(name);
    }

    public void validate() {
        SystemValidation.validate(this);
    }

    /** This is the saved celestial source, not a reference back into a pack. The running clock copies it once. */
    public record Sky(GroundEphemeris.Model model, List<String> names, List<Integer> groups) {
        private static final Codec<GroundEphemeris.Model> MODEL = SystemSkyCodec.SAVED_MODEL;
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
        return SystemMaterializer.materialize(this, worldSeed);
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
        return SystemMaterializer.derivedSeed(world, index);
    }

    public static void initialize(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(
                (net.neoforged.neoforge.registries.DataPackRegistryEvent.NewRegistry event) ->
                        event.dataPackRegistry(KEY, CODEC));
    }
}
