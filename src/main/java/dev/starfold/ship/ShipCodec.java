package dev.starfold.ship;

import dev.starfold.geometry.BentStripGeometry.Point;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import org.joml.Quaterniond;

import java.util.Optional;

/** Typed ship places and holding addresses; no hull contents are stored here. */
final class ShipCodec {
    private ShipCodec() {}

    static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Ship field " + key);
    }

    static CompoundTag write(ShipRecord record) {
        var tag = new CompoundTag();
        tag.putInt("region", record.id().region());
        tag.putInt("index", record.id().index());
        tag.putUUID("uuid", record.uuid());
        tag.putString("dimension", record.dimension().toString());
        record.name().ifPresent(name -> tag.putString("name", name));
        if (record.state() instanceof ShipRecord.OnSlot slot) {
            tag.putString("state", "on_slot");
            tag.putInt("slot", slot.slot());
            slot.place().ifPresent(place -> tag.put("chart_place", writeChartPlace(place)));
        } else {
            tag.putString("state", "in_space");
            tag.put("place", writePlace(((ShipRecord.InSpace) record.state()).place()));
        }
        record.locator().ifPresent(locator -> tag.put("locator", writeLocator(locator)));
        return tag;
    }

    static ShipRecord read(CompoundTag tag) {
        require(tag, "region", Tag.TAG_INT);
        require(tag, "index", Tag.TAG_INT);
        require(tag, "uuid", Tag.TAG_INT_ARRAY);
        if (!tag.hasUUID("uuid")) throw new IllegalArgumentException("Ship UUID");
        require(tag, "dimension", Tag.TAG_STRING);
        require(tag, "state", Tag.TAG_STRING);
        if (tag.contains("name")) require(tag, "name", Tag.TAG_STRING);
        if (tag.contains("locator")) require(tag, "locator", Tag.TAG_COMPOUND);
        ShipRecord.State state =
                switch (tag.getString("state")) {
                    case "on_slot" -> {
                        require(tag, "slot", Tag.TAG_INT);
                        if (tag.contains("chart_place"))
                            require(tag, "chart_place", Tag.TAG_COMPOUND);
                        yield new ShipRecord.OnSlot(
                                tag.getInt("slot"),
                                tag.contains("chart_place")
                                        ? Optional.of(
                                                readChartPlace(tag.getCompound("chart_place")))
                                        : Optional.empty());
                    }
                    case "in_space" -> {
                        require(tag, "place", Tag.TAG_COMPOUND);
                        yield new ShipRecord.InSpace(readPlace(tag.getCompound("place")));
                    }
                    default -> throw new IllegalArgumentException("Ship state");
                };
        return new ShipRecord(
                new ShipId(tag.getInt("region"), tag.getInt("index")),
                tag.getUUID("uuid"),
                ResourceLocation.parse(tag.getString("dimension")),
                tag.contains("name") ? Optional.of(tag.getString("name")) : Optional.empty(),
                state,
                tag.contains("locator")
                        ? Optional.of(readLocator(tag.getCompound("locator")))
                        : Optional.empty());
    }

    static CompoundTag writePlace(ShipRecord.Place place) {
        var tag = new CompoundTag();
        tag.putInt("body", place.body());
        tag.put("anchor_plot", point(place.anchorPlot()));
        tag.put("position", point(place.position()));
        var attitude = point(new Point(place.attitude().x, place.attitude().y, place.attitude().z));
        attitude.putDouble("w", place.attitude().w);
        tag.put("attitude", attitude);
        tag.putLong("tick", place.tick());
        return tag;
    }

    static ShipRecord.Place readPlace(CompoundTag tag) {
        require(tag, "body", Tag.TAG_INT);
        require(tag, "anchor_plot", Tag.TAG_COMPOUND);
        require(tag, "position", Tag.TAG_COMPOUND);
        require(tag, "attitude", Tag.TAG_COMPOUND);
        require(tag, "tick", Tag.TAG_LONG);
        var q = tag.getCompound("attitude");
        var xyz = readPoint(q);
        require(q, "w", Tag.TAG_DOUBLE);
        return new ShipRecord.Place(
                tag.getInt("body"),
                readPoint(tag.getCompound("anchor_plot")),
                readPoint(tag.getCompound("position")),
                new Quaterniond(xyz.x(), xyz.y(), xyz.z(), q.getDouble("w")),
                tag.getLong("tick"));
    }

    private static CompoundTag writeChartPlace(ShipRecord.ChartPlace place) {
        var tag = new CompoundTag();
        tag.put("position", point(place.position()));
        tag.put("anchor_plot", point(place.anchorPlot()));
        var q = place.attitude();
        var attitude = point(new Point(q.x, q.y, q.z));
        attitude.putDouble("w", q.w);
        tag.put("attitude", attitude);
        tag.putLong("tick", place.tick());
        return tag;
    }

    private static ShipRecord.ChartPlace readChartPlace(CompoundTag tag) {
        require(tag, "position", Tag.TAG_COMPOUND);
        require(tag, "anchor_plot", Tag.TAG_COMPOUND);
        require(tag, "attitude", Tag.TAG_COMPOUND);
        require(tag, "tick", Tag.TAG_LONG);
        var q = tag.getCompound("attitude");
        var xyz = readPoint(q);
        require(q, "w", Tag.TAG_DOUBLE);
        return new ShipRecord.ChartPlace(
                readPoint(tag.getCompound("position")),
                readPoint(tag.getCompound("anchor_plot")),
                new Quaterniond(xyz.x(), xyz.y(), xyz.z(), q.getDouble("w")),
                tag.getLong("tick"));
    }

    private static CompoundTag point(Point point) {
        var tag = new CompoundTag();
        tag.putDouble("x", point.x());
        tag.putDouble("y", point.y());
        tag.putDouble("z", point.z());
        return tag;
    }

    private static Point readPoint(CompoundTag tag) {
        require(tag, "x", Tag.TAG_DOUBLE);
        require(tag, "y", Tag.TAG_DOUBLE);
        require(tag, "z", Tag.TAG_DOUBLE);
        return new Point(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
    }

    private static CompoundTag writeLocator(ShipRecord.Locator locator) {
        var tag = new CompoundTag();
        tag.putInt("chunk_x", locator.chunkX());
        tag.putInt("chunk_z", locator.chunkZ());
        tag.putShort("storage_index", locator.storageIndex());
        tag.putShort("sub_level_index", locator.subLevelIndex());
        return tag;
    }

    private static ShipRecord.Locator readLocator(CompoundTag tag) {
        require(tag, "chunk_x", Tag.TAG_INT);
        require(tag, "chunk_z", Tag.TAG_INT);
        require(tag, "storage_index", Tag.TAG_SHORT);
        require(tag, "sub_level_index", Tag.TAG_SHORT);
        return new ShipRecord.Locator(
                tag.getInt("chunk_x"),
                tag.getInt("chunk_z"),
                tag.getShort("storage_index"),
                tag.getShort("sub_level_index"));
    }
}
