package dev.starfold.universe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;

import org.joml.Quaterniond;

/** One typed body spelling for JSON, NBT saves and packet model snapshots. */
public final class SystemSkyCodec {
    public static final int FORMAT = 5;
    private static final Codec<Integer> VERSION =
            Codec.INT.validate(
                    v ->
                            v == FORMAT
                                    ? DataResult.success(v)
                                    : DataResult.error(
                                            () ->
                                                    "Unsupported celestial format "
                                                            + v
                                                            + "; expected "
                                                            + FORMAT));
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
    private static final MapCodec<GroundEphemeris.Fixed> FIXED =
            RecordCodecBuilder.mapCodec(
                    i ->
                            i.group(
                                            POINT.fieldOf("position")
                                                    .forGetter(GroundEphemeris.Fixed::position))
                                    .apply(i, GroundEphemeris.Fixed::new));
    private static final MapCodec<GroundEphemeris.Orbit> ORBIT =
            RecordCodecBuilder.mapCodec(
                    i ->
                            i.group(
                                            Codec.INT
                                                    .fieldOf("parent")
                                                    .forGetter(GroundEphemeris.Orbit::parent),
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
    private static final Codec<GroundEphemeris.Motion> MOTION =
            Codec.STRING.dispatch(
                    "type",
                    m -> m instanceof GroundEphemeris.Fixed ? "fixed" : "orbit",
                    type ->
                            switch (type) {
                                case "fixed" -> FIXED;
                                case "orbit" -> ORBIT;
                                default ->
                                        throw new IllegalArgumentException(
                                                "Unknown celestial motion " + type);
                            });
    public static final Codec<GroundEphemeris.Body> BODY =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            MOTION.fieldOf("motion")
                                                    .forGetter(GroundEphemeris.Body::motion),
                                            Codec.DOUBLE
                                                    .fieldOf("radius")
                                                    .forGetter(GroundEphemeris.Body::radius),
                                            Codec.BOOL
                                                    .fieldOf("light")
                                                    .forGetter(GroundEphemeris.Body::light),
                                            Codec.INT
                                                    .fieldOf("slot")
                                                    .forGetter(GroundEphemeris.Body::slot),
                                            Codec.DOUBLE
                                                    .fieldOf("reach")
                                                    .forGetter(GroundEphemeris.Body::reach))
                                    .apply(i, GroundEphemeris.Body::new));
    public static final Codec<GroundEphemeris.Model> MODEL =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            VERSION.fieldOf("format").forGetter(m -> FORMAT),
                                            BODY.listOf()
                                                    .fieldOf("bodies")
                                                    .forGetter(GroundEphemeris.Model::bodies))
                                    .apply(
                                            i,
                                            (version, bodies) ->
                                                    new GroundEphemeris.Model(bodies)));

    private SystemSkyCodec() {}
}
