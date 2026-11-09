package dev.starfold.space;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.flight.ArrivalState;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaterniond;
import java.util.function.Supplier;

/** Strict saved frame format; ownership and dirty tracking remain with SpaceServer. */
final class SpaceFrameCodec {
    record State(Space.Frame frame, long epoch, ArrivalState arrival) {}

    static State read(CompoundTag tag, Supplier<GroundEphemeris.Model> model) {
        if (!tag.contains("format", 3) || tag.getInt("format") != 1)
            throw new IllegalArgumentException("Space frame format; old saves are not supported");
        for (String key :
                new String[] {
                    "qx", "qy", "qz", "qw", "x", "y", "z", "ox", "oy", "oz", "wx", "wy", "wz"
                })
            if (!tag.contains(key, 6))
                throw new IllegalArgumentException("Space frame field: " + key);
        if (!tag.contains("planet", 10)
                || !tag.contains("epoch", 4)
                || !tag.contains("originEpoch", 4)
                || tag.getLong("epoch") < 0)
            throw new IllegalArgumentException("Space reference fields");
        var frame = new Space.Frame(
                                new Quaterniond(
                                        tag.getDouble("qx"),
                                        tag.getDouble("qy"),
                                        tag.getDouble("qz"),
                                        tag.getDouble("qw")),
                                new Point(
                                        tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")),
                                model.get()
                                        .slot(
                                                dev.starfold.chart.BodyId.read(
                                                        tag.getCompound("planet"))),
                                new Point(
                                        tag.getDouble("ox"),
                                        tag.getDouble("oy"),
                                        tag.getDouble("oz")),
                                new Point(
                                        tag.getDouble("wx"),
                                        tag.getDouble("wy"),
                                        tag.getDouble("wz")),
                                tag.getLong("originEpoch"));
        long epoch = tag.getLong("epoch");
        var arrivalState = ArrivalState.empty();
        if (tag.getBoolean("arrivalLatched")) {
            arrivalState =
                    new ArrivalState(
                            ArrivalState.Phase.ARRIVAL,
                            20,
                            0,
                            0,
                            tag.getDouble("arrivalHeight"),
                            model.get()
                                    .slot(
                                            dev.starfold.chart.BodyId.read(
                                                    tag.getCompound("arrivalPlanet"))),
                            tag.getString("arrivalLead"));
        }
        if (tag.contains("departurePlanet", 10) && tag.contains("departureLead", 8)) {
            var s = arrivalState;
            arrivalState =
                    new ArrivalState(
                            s.phase(),
                            s.ticks(),
                            s.cooldown(),
                            s.leaving(),
                            s.entryHeight(),
                            s.planet(),
                            s.lead(),
                            new ArrivalState.Departure(
                                    model.get()
                                            .slot(
                                                    dev.starfold.chart.BodyId.read(
                                                            tag.getCompound("departurePlanet"))),
                                    tag.getString("departureLead")));
        }
        return new State(frame, epoch, arrivalState);
    }

    static CompoundTag write(CompoundTag tag, Space.Frame frame, long epoch, ArrivalState arrivalState, Supplier<GroundEphemeris.Model> model) {
        tag.putInt("format", 1);
        var q = frame.rotation();
        var p = frame.shift();
        tag.putDouble("qx", q.x);
        tag.putDouble("qy", q.y);
        tag.putDouble("qz", q.z);
        tag.putDouble("qw", q.w);
        tag.putDouble("x", p.x());
        tag.putDouble("y", p.y());
        tag.putDouble("z", p.z());
        tag.put("planet", model.get().id(frame.planet()).tag());
        tag.putLong("epoch", epoch);
        var o = frame.offset();
        var w = frame.drift();
        tag.putDouble("ox", o.x());
        tag.putDouble("oy", o.y());
        tag.putDouble("oz", o.z());
        tag.putDouble("wx", w.x());
        tag.putDouble("wy", w.y());
        tag.putDouble("wz", w.z());
        tag.putLong("originEpoch", frame.epoch());
        tag.putBoolean("arrivalLatched", arrivalState.latched());
        tag.putDouble("arrivalHeight", arrivalState.entryHeight());
        if (arrivalState.latched())
            tag.put("arrivalPlanet", model.get().id(arrivalState.planet()).tag());
        else tag.remove("arrivalPlanet");
        tag.putString("arrivalLead", arrivalState.lead());
        var d = arrivalState.departure();
        tag.remove("departurePlanet");
        tag.remove("departureLead");
        tag.remove("departureTicks");
        if (d != null) {
            tag.put("departurePlanet", model.get().id(d.planet()).tag());
            tag.putString("departureLead", d.lead());
        }
        return tag;
    }

    private SpaceFrameCodec() {}
}
