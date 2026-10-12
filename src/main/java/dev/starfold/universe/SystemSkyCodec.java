package dev.starfold.universe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundSolar;
import dev.starfold.geometry.BentStripGeometry.Point;

import org.joml.Quaterniond;

/** Typed numbers survive both JSON world-preset copies and level.dat. Converting raw NBT through JSON loses 0.0's type. */
public final class SystemSkyCodec {
    private static final Codec<Point> POINT =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Codec.DOUBLE.fieldOf("x").forGetter(Point::x),
                                            Codec.DOUBLE.fieldOf("y").forGetter(Point::y),
                                            Codec.DOUBLE.fieldOf("z").forGetter(Point::z))
                                    .apply(i, Point::new));
    private static final Codec<Quaterniond> QUATERNION =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Codec.DOUBLE.fieldOf("x").forGetter(q -> q.x),
                                            Codec.DOUBLE.fieldOf("y").forGetter(q -> q.y),
                                            Codec.DOUBLE.fieldOf("z").forGetter(q -> q.z),
                                            Codec.DOUBLE.fieldOf("w").forGetter(q -> q.w))
                                    .apply(i, Quaterniond::new));
    private static final Codec<GroundEphemeris.Spin> SPIN =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Codec.BOOL
                                                    .fieldOf("enabled")
                                                    .forGetter(GroundEphemeris.Spin::enabled),
                                            POINT.fieldOf("axis")
                                                    .forGetter(GroundEphemeris.Spin::axis),
                                            QUATERNION
                                                    .fieldOf("reference")
                                                    .forGetter(GroundEphemeris.Spin::reference),
                                            Codec.DOUBLE
                                                    .fieldOf("phase")
                                                    .forGetter(GroundEphemeris.Spin::phase),
                                            Codec.DOUBLE
                                                    .fieldOf("period")
                                                    .forGetter(GroundEphemeris.Spin::period),
                                            Codec.LONG
                                                    .fieldOf("epoch")
                                                    .forGetter(GroundEphemeris.Spin::epoch),
                                            QUATERNION
                                                    .fieldOf("frozen")
                                                    .forGetter(GroundEphemeris.Spin::frozen))
                                    .apply(i, GroundEphemeris.Spin::new));
    private static final Codec<GroundEphemeris.Orbit> ORBIT =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            POINT.fieldOf("center")
                                                    .forGetter(GroundEphemeris.Orbit::center),
                                            POINT.fieldOf("axis")
                                                    .forGetter(GroundEphemeris.Orbit::axis),
                                            POINT.fieldOf("radial")
                                                    .forGetter(GroundEphemeris.Orbit::radial),
                                            Codec.DOUBLE
                                                    .fieldOf("radius")
                                                    .forGetter(GroundEphemeris.Orbit::radius),
                                            Codec.DOUBLE
                                                    .fieldOf("period")
                                                    .forGetter(GroundEphemeris.Orbit::period),
                                            Codec.DOUBLE
                                                    .fieldOf("phase")
                                                    .forGetter(GroundEphemeris.Orbit::phase),
                                            Codec.LONG
                                                    .fieldOf("epoch")
                                                    .forGetter(GroundEphemeris.Orbit::epoch),
                                            SPIN.fieldOf("spin")
                                                    .forGetter(GroundEphemeris.Orbit::spin))
                                    .apply(i, GroundEphemeris.Orbit::new));
    private static final Codec<GroundEphemeris.Star> STAR =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            POINT.fieldOf("position")
                                                    .forGetter(GroundEphemeris.Star::position),
                                            Codec.DOUBLE
                                                    .fieldOf("radius")
                                                    .forGetter(GroundEphemeris.Star::radius))
                                    .apply(i, GroundEphemeris.Star::new));
    private static final Codec<GroundSolar.Sun> SUN =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Codec.BOOL
                                                    .fieldOf("enabled")
                                                    .forGetter(GroundSolar.Sun::enabled),
                                            POINT.fieldOf("position")
                                                    .forGetter(GroundSolar.Sun::position),
                                            Codec.DOUBLE
                                                    .fieldOf("radius")
                                                    .forGetter(GroundSolar.Sun::radius))
                                    .apply(i, GroundSolar.Sun::new));
    public static final Codec<GroundEphemeris.Model> MODEL =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            POINT.fieldOf("center")
                                                    .forGetter(GroundEphemeris.Model::center),
                                            POINT.fieldOf("axis")
                                                    .forGetter(GroundEphemeris.Model::axis),
                                            POINT.fieldOf("radial")
                                                    .forGetter(GroundEphemeris.Model::radial),
                                            Codec.DOUBLE
                                                    .fieldOf("radius")
                                                    .forGetter(GroundEphemeris.Model::radius),
                                            Codec.DOUBLE
                                                    .fieldOf("period")
                                                    .forGetter(GroundEphemeris.Model::period),
                                            Codec.DOUBLE
                                                    .fieldOf("phase")
                                                    .forGetter(GroundEphemeris.Model::phase),
                                            Codec.LONG
                                                    .fieldOf("epoch")
                                                    .forGetter(GroundEphemeris.Model::epoch),
                                            SPIN.fieldOf("a").forGetter(GroundEphemeris.Model::a),
                                            SPIN.fieldOf("b").forGetter(GroundEphemeris.Model::b),
                                            SUN.fieldOf("sun")
                                                    .forGetter(GroundEphemeris.Model::sun),
                                            Codec.BOOL
                                                    .fieldOf("multiple")
                                                    .forGetter(GroundEphemeris.Model::multiple),
                                            ORBIT.listOf()
                                                    .fieldOf("more")
                                                    .forGetter(GroundEphemeris.Model::more),
                                            STAR.listOf()
                                                    .fieldOf("stars")
                                                    .forGetter(GroundEphemeris.Model::stars))
                                    .apply(i, GroundEphemeris.Model::new));

    private SystemSkyCodec() {}
}
