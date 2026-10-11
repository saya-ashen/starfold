package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Shared disk/wire marker schema. Field names and exact NBT types are compatibility contracts. */
public final class NativeWaypointCodec {
    public static CompoundTag encode(NativeWaypoint marker) {
        var tag = new CompoundTag();
        tag.putString("name", marker.name());
        tag.putInt("planet", marker.planet());
        tag.putInt("face", marker.pose().face().ordinal());
        var point = marker.pose().point();
        tag.putDouble("u", point.x());
        tag.putDouble("h", point.y());
        tag.putDouble("v", point.z());
        return tag;
    }

    public static NativeWaypoint decode(CompoundTag tag) {
        require(tag, "name", Tag.TAG_STRING);
        require(tag, "planet", Tag.TAG_INT);
        require(tag, "face", Tag.TAG_INT);
        require(tag, "u", Tag.TAG_DOUBLE);
        require(tag, "h", Tag.TAG_DOUBLE);
        require(tag, "v", Tag.TAG_DOUBLE);
        int face = tag.getInt("face");
        if (face < 0 || face >= Face.values().length) {
            throw new IllegalArgumentException("Waypoint face");
        }
        var point = new Point(tag.getDouble("u"), tag.getDouble("h"), tag.getDouble("v"));
        return new NativeWaypoint(tag.getString("name"), tag.getInt("planet"),
                new NativeGroundTopology.Pose(Face.values()[face], point));
    }

    static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) {
            throw new IllegalArgumentException("Invalid waypoint field: " + key);
        }
    }

    private NativeWaypointCodec() {}
}
