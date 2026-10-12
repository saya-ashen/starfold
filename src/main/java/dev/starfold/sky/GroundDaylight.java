package dev.starfold.sky;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundGap;
import dev.starfold.chart.GroundSolar;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Day and night on a planet are where its sun stands over a place, not the hour of the level's clock: one face has
 *  noon while the opposite has midnight. What the server decides by daylight asks here. */
public final class GroundDaylight {
    /** Biomes whose monsters do not wait for night and do not burn by day, whatever their sky shows. */
    public static final net.minecraft.tags.TagKey<net.minecraft.world.level.biome.Biome> SUNLESS =
            net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.BIOME,
                    net.minecraft.resources.ResourceLocation.parse("starfold:sunless"));

    public static boolean sunless(net.minecraft.world.level.LevelReader level, BlockPos pos) {
        return level.getBiome(pos).is(SUNLESS);
    }

    /** How much of a sun reaches an eye past a planet: none behind the planet, all beside it, and all where the planet is the farther of the two. */
    public static double sunlight(
            dev.starfold.geometry.BentStripGeometry.Point eye,
            dev.starfold.geometry.BentStripGeometry.Point center,
            dev.starfold.geometry.BentStripGeometry.Point sun,
            double radius) {
        var d = sun.sub(eye);
        double far = d.length();
        if (far < 1e-9) return 1;
        d = d.scale(1 / far);
        var c = center.sub(eye);
        double along = c.dot(d);
        if (along <= 0 || along >= far) return 1;
        return GroundGap.smooth(radius * .95, radius * 1.1, c.sub(d.scale(along)).length());
    }

    /** How high the sun that lights a place stands over it: 1 overhead, 0 on the horizon, -1 underfoot. */
    public static double elevation(ServerLevel level, BlockPos pos) {
        if (Space.holds(pos.getX(), pos.getZ())) return 1;
        return elevation(level, pos, GroundClock.get(level).clock.tick());
    }

    public static double elevation(ServerLevel level, BlockPos pos, long tick) {
        if (Space.holds(pos.getX(), pos.getZ())) return 1;
        var access = PlanetAccess.of(level);
        var clock = GroundClock.get(level).clock;
        var owner = access.address(pos).owner();
        int planet = access.storagePlanet(owner);
        if (planet >= clock.model.count()) return 1;
        var cell = access.cell(owner);
        var shape = access.shape(owner);
        var frame = clock.model.at(planet, tick);
        var up = frame.vector(shape.direction(cell.face(), cell.u() + .5, cell.v() + .5));
        var world = frame.center().add(up.scale(shape.radius()));
        var sun = clock.model.star(world).position().sub(world);
        return sun.length() < 1e-9 ? 1 : up.dot(sun.scale(1 / sun.length()));
    }

    /** The face of the home planet a world begins on: the one whose middle has the most daylight in the world's first
     *  twenty minutes. As in the overworld a world then begins at dawn, with the day ahead of it; one that began
     *  at noon would have half as long before its first night. It is the same face ever after. */
    public static final int FIRST_DAY = 24000;

    public static int homeFace(GroundEphemeris.Model model, PlanetShape shape) {
        int best = 0;
        double most = -1;
        for (var face : CubeSurfaceTopology.Face.values()) {
            double light = 0;
            for (int tick = 0; tick <= FIRST_DAY; tick += 600)
                light += GroundSolar.daylight(elevation(model, shape, face, tick));
            if (light > most) {
                most = light;
                best = face.ordinal();
            }
        }
        return best;
    }

    /** The first moment the sun is clear of the horizon over the middle of the home face: when a world's clock begins. */
    public static long sunrise(GroundEphemeris.Model model, PlanetShape shape) {
        var face = CubeSurfaceTopology.Face.values()[homeFace(model, shape)];
        for (int tick = 0; tick <= FIRST_DAY; tick += 100)
            if (elevation(model, shape, face, tick) >= .15) return tick;
        return 0;
    }

    /** First rising sun at 45 degrees over the home face: experience starts mid-morning, not vanilla /time. */
    public static long midMorning(GroundEphemeris.Model model, PlanetShape shape) {
        var face = CubeSurfaceTopology.Face.values()[homeFace(model, shape)];
        for (long tick = sunrise(model, shape); tick <= LONGEST; tick += 100)
            if (elevation(model, shape, face, tick) >= Math.sqrt(.5)
                    && elevation(model, shape, face, tick + 100)
                            > elevation(model, shape, face, tick)) return tick;
        return sunrise(model, shape); // authored polar worlds may never reach 45 degrees
    }

    public static double elevation(
            GroundEphemeris.Model model,
            PlanetShape shape,
            CubeSurfaceTopology.Face face,
            double tick) {
        var frame = model.at(0, tick);
        var up = frame.vector(shape.direction(face, shape.half(), shape.half()));
        var world = frame.center().add(up.scale(shape.radius()));
        var sun = model.star(world).position().sub(world);
        return up.dot(sun.scale(1 / Math.max(1e-9, sun.length())));
    }

    /** 0 at night, 1 by day, between them at dusk and dawn; 1 where a place is no planet's. */
    public static double daylight(ServerLevel level, BlockPos pos) {
        try {
            if (PlanetLevels.is(level) && Space.holds(pos.getX(), pos.getZ())) {
                var clock = GroundClock.get(level).clock;
                return SkyHost.get().sunlight(level, pos, clock);
            }
            return GroundSolar.daylight(elevation(level, pos));
        } catch (IllegalArgumentException | IllegalStateException outside) {
            return 1;
        }
    }

    /** How much of the sky's light is lost at a place, as vanilla counts it: 0 by day, 11 at night. */
    public static int darken(ServerLevel level, BlockPos pos) {
        return darken(daylight(level, pos));
    }

    static int darken(double daylight) {
        return (int) Math.round((1 - daylight) * 11);
    }

    public static boolean day(ServerLevel level, BlockPos pos) {
        return darken(level, pos) < 4;
    }

    /** How long until the sun is next clear of the horizon over a place where it is night: what a night slept through
     *  skips. Where it never climbs so high, until it is day there; 0 where no day comes within {@link #LONGEST}. */
    public static final int LONGEST = 240000;

    public static long dawn(ServerLevel level, BlockPos pos) {
        if (Space.holds(pos.getX(), pos.getZ())) return 0;
        long day = 0;
        try {
            long now = GroundClock.get(level).clock.tick();
            for (long ahead = 100; ahead <= LONGEST; ahead += 100) {
                double light = GroundSolar.daylight(elevation(level, pos, now + ahead));
                if (light >= 1) return ahead;
                if (day == 0 && darken(light) < 4) day = ahead;
            }
        } catch (IllegalArgumentException | IllegalStateException outside) {
        }
        return day;
    }

    /** Whether a place keeps a planet's own day and night. */
    public static boolean kept(net.minecraft.world.level.Level level, BlockPos pos) {
        return level instanceof ServerLevel server
                && PlanetLevels.is(server)
                && PlanetSystem.shared(server)
                && PlanetAccess.of(server).inChart(pos);
    }

    /** Those asleep have slept the night through: the planets turn on to the dawn of where the first of them lies
     *  that has one.
     *  Not while a vessel is under way, which flies by the clock. */
    public static void sleptThrough(ServerLevel level) {
        if (!PlanetLevels.is(level) || !PlanetSystem.shared(level) || SkyHost.get().underWay())
            return;
        for (var player : level.players())
            if (player.isSleeping() && kept(level, player.blockPosition())) {
                long ahead = dawn(level, player.blockPosition());
                if (ahead > 0) {
                    GroundClock.get(level).skip(ahead);
                    return;
                }
            }
    }

    private GroundDaylight() {}
}
