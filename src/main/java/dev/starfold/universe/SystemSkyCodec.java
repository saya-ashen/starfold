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
    public static final int FORMAT = 8;
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
    private static final Codec<GroundEphemeris.Appearance> APPEARANCE =
            Codec.STRING.comapFlatMap(
                    name -> {
                        try {
                            return DataResult.success(GroundEphemeris.Appearance.valueOf(name));
                        } catch (IllegalArgumentException e) {
                            return DataResult.error(() -> "Unknown body appearance " + name);
                        }
                    },
                    GroundEphemeris.Appearance::name);
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
                                                    .forGetter(GroundEphemeris.Body::reach),
                                            APPEARANCE
                                                    .fieldOf("appearance")
                                                    .forGetter(GroundEphemeris.Body::appearance),
                                            dev.starfold.worldgen.PlanetAtmosphere.CODEC
                                                    .fieldOf("atmosphere")
                                                    .forGetter(GroundEphemeris.Body::atmosphere),
                                            dev.starfold.worldgen.PlanetEnvironment.TEMPERATURE_OFFSET
                                                    .optionalFieldOf("temperature_offset", 0.0)
                                                    .forGetter(GroundEphemeris.Body::temperatureOffset),
                                            dev.starfold.chart.GasGiant.CODEC
                                                    .optionalFieldOf("gas")
                                                    .forGetter(GroundEphemeris.Body::gas))
                                    .apply(
                                            i,
                                            (motion,
                                                    radius,
                                                    light,
                                                    slot,
                                                    reach,
                                                    appearance,
                                                    atmosphere,
                                                    temperatureOffset,
                                                    gas) ->
                                                    new GroundEphemeris.Body(
                                                            motion,
                                                            radius,
                                                            light,
                                                            slot,
                                                            reach,
                                                            appearance,
                                                            null,
                                                            atmosphere,
                                                            temperatureOffset,
                                                            gas)));
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

    /** Disk catalogs carry lifetime identities; the existing wire codec deliberately does not. */
    public static final int SAVE_FORMAT = 9;

    public static final Codec<java.util.List<GroundEphemeris.Body>> CATALOG =
            Codec.PASSTHROUGH.flatXmap(
                    dynamic -> {
                        try {
                            var raw = dynamic.convert(net.minecraft.nbt.NbtOps.INSTANCE).getValue();
                            if (!(raw instanceof net.minecraft.nbt.ListTag list))
                                throw new IllegalArgumentException("Body catalog list");
                            var ids = new java.util.ArrayList<dev.starfold.chart.BodyId>();
                            for (var entry : list) {
                                var tag = (net.minecraft.nbt.CompoundTag) entry;
                                ids.add(
                                        dev.starfold.chart.BodyId.CODEC
                                                .parse(
                                                        net.minecraft.nbt.NbtOps.INSTANCE,
                                                        tag.getCompound("id"))
                                                .getOrThrow());
                            }
                            if (new java.util.HashSet<>(ids).size() != ids.size())
                                throw new IllegalArgumentException("Duplicate body identity");
                            var bodies = new java.util.ArrayList<GroundEphemeris.Body>();
                            for (int n = 0; n < list.size(); n++) {
                                var tag = list.getCompound(n).copy();
                                var motion = tag.getCompound("motion");
                                if (motion.getString("type").equals("orbit")) {
                                    int parent =
                                            ids.indexOf(
                                                    dev.starfold.chart.BodyId.CODEC
                                                            .parse(
                                                                    net.minecraft.nbt.NbtOps
                                                                            .INSTANCE,
                                                                    motion.getCompound("parent"))
                                                            .getOrThrow());
                                    if (parent < 0 || parent >= n)
                                        throw new IllegalArgumentException(
                                                "Unknown or forward body parent");
                                    motion.putInt("parent", parent);
                                }
                                bodies.add(
                                        BODY.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag)
                                                .getOrThrow()
                                                .identified(ids.get(n)));
                            }
                            return DataResult.success(java.util.List.copyOf(bodies));
                        } catch (RuntimeException e) {
                            return DataResult.error(
                                    () -> "Invalid saved body catalog: " + e.getMessage());
                        }
                    },
                    bodies -> {
                        try {
                            var list = new net.minecraft.nbt.ListTag();
                            for (var body : bodies) {
                                var tag =
                                        (net.minecraft.nbt.CompoundTag)
                                                BODY.encodeStart(
                                                                net.minecraft.nbt.NbtOps.INSTANCE,
                                                                body)
                                                        .getOrThrow();
                                tag.put("id", body.id().tag());
                                if (body.motion() instanceof GroundEphemeris.Orbit orbit)
                                    tag.getCompound("motion")
                                            .put("parent", bodies.get(orbit.parent()).id().tag());
                                list.add(tag);
                            }
                            return DataResult.success(
                                    new com.mojang.serialization.Dynamic<>(
                                            net.minecraft.nbt.NbtOps.INSTANCE, list));
                        } catch (RuntimeException e) {
                            return DataResult.error(
                                    () -> "Invalid saved body catalog: " + e.getMessage());
                        }
                    });
    public static final Codec<GroundEphemeris.Model> SAVED_MODEL =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Codec.INT
                                                    .validate(
                                                            v ->
                                                                    v == SAVE_FORMAT
                                                                            ? DataResult.success(v)
                                                                            : DataResult.error(
                                                                                    () ->
                                                                                            "Unsupported celestial saved format"))
                                                    .fieldOf("format")
                                                    .forGetter(m -> SAVE_FORMAT),
                                            CATALOG.fieldOf("bodies")
                                                    .forGetter(GroundEphemeris.Model::bodies))
                                    .apply(
                                            i,
                                            (version, bodies) ->
                                                    new GroundEphemeris.Model(bodies)));

    private SystemSkyCodec() {}
}
