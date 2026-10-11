package dev.starfold;

import dev.starfold.mixin.PlanetSpaceDataAccess;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import static dev.starfold.NativeWaypointCodec.require;

/** Player-owned navigation books. Never loads or writes terrain. */
public final class NativeWaypointSave extends SavedData {
    public static final String FILE = "starfold_native_waypoints_v1";
    public static final int MAX_PLAYERS = 1024;
    public static final int MAX_MARKERS = 64;
    private static final int FORMAT = 1;

    private static final class Book {
        final Map<String, NativeWaypoint> points = new TreeMap<>();
        String active = "";
        final Map<Integer, NativeWaypoint> launches = new TreeMap<>();
        int recentLaunch = -1;
    }

    private final Map<UUID, Book> players = new HashMap<>();

    private Book book(UUID id) {
        Objects.requireNonNull(id);
        if (!players.containsKey(id) && players.size() >= MAX_PLAYERS) {
            throw new IllegalStateException("Waypoint player capacity");
        }
        return players.computeIfAbsent(id, ignored -> new Book());
    }

    public Map<String, NativeWaypoint> list(UUID id) {
        var book = players.get(id);
        return book == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(book.points));
    }

    public NativeWaypoint active(UUID id) { return active(id, -1); }

    public NativeWaypoint active(UUID id, int planet) {
        var book = players.get(id);
        return book == null ? null : active(book.points.get(book.active), book.launches.getOrDefault(planet, book.launches.get(book.recentLaunch)));
    }

    public Map<Integer, NativeWaypoint> launches(UUID id) {
        var book = players.get(id);
        return book == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(book.launches));
    }

    static NativeWaypoint active(NativeWaypoint selected, NativeWaypoint launch) { return selected != null ? selected : launch; }

    public void launch(UUID id, NativeWaypoint marker) {
        Objects.requireNonNull(marker);
        var book = book(id);
        book.launches.put(marker.planet(), marker);
        book.recentLaunch = marker.planet();
        setDirty();
    }

    public void add(UUID id, NativeWaypoint marker) {
        Objects.requireNonNull(marker);
        var book = book(id);
        if (marker.name().equals(NativeWaypoint.LAUNCH)) throw new NativeWaypoint.Failure("message.starfold.mark.invalid");
        if (book.points.containsKey(marker.name())) {
            throw new NativeWaypoint.Failure("message.starfold.mark.duplicate");
        }
        if (book.points.size() >= MAX_MARKERS) {
            throw new NativeWaypoint.Failure("message.starfold.mark.limit", MAX_MARKERS);
        }
        book.points.put(marker.name(), marker);
        setDirty();
    }

    public void select(UUID id, String name) {
        // An invalid selection must not allocate a book or consume player capacity.
        var book = players.get(id);
        if (book == null || !book.points.containsKey(name)) {
            throw new NativeWaypoint.Failure("message.starfold.mark.missing", name);
        }
        book.active = name;
        setDirty();
    }

    public void clear(UUID id) {
        var book = players.get(id);
        if (book != null && !book.active.isEmpty()) {
            book.active = "";
            setDirty();
        }
    }

    public void remove(UUID id, String name) {
        var book = players.get(id);
        if (book == null || book.points.remove(name) == null) {
            throw new NativeWaypoint.Failure("message.starfold.mark.missing", name);
        }
        if (book.active.equals(name)) book.active = "";
        setDirty();
    }

    static NativeWaypointSave fresh(boolean exists) {
        if (exists) {
            throw new IllegalStateException("Existing waypoint save unreadable; refusing overwrite");
        }
        return new NativeWaypointSave();
    }

    public static NativeWaypointSave get(ServerLevel level) {
        if (!NativeGroundServer.is(level)) throw new IllegalArgumentException("Native waypoint world");
        var storage = NativePlanetSystem.authority(level).getDataStorage();
        return storage.computeIfAbsent(new Factory<>(
                () -> fresh(((PlanetSpaceDataAccess) storage).starfold$spaceDataFile(FILE).exists()),
                NativeWaypointSave::load, null), FILE);
    }

    static NativeWaypointSave load(CompoundTag tag, HolderLookup.Provider lookup) {
        require(tag, "format", Tag.TAG_INT);
        require(tag, "players", Tag.TAG_COMPOUND);
        if (tag.getInt("format") != FORMAT) throw new IllegalArgumentException("Waypoint format");
        var all = tag.getCompound("players");
        if (all.getAllKeys().size() > MAX_PLAYERS) {
            throw new IllegalArgumentException("Waypoint player capacity");
        }
        var result = new NativeWaypointSave();
        for (String key : all.getAllKeys()) {
            var id = UUID.fromString(key);
            if (!id.toString().equals(key)) throw new IllegalArgumentException("Waypoint UUID");
            require(all, key, Tag.TAG_COMPOUND);
            result.players.put(id, decodeBook(all.getCompound(key)));
        }
        return result;
    }

    private static Book decodeBook(CompoundTag entry) {
        require(entry, "points", Tag.TAG_LIST);
        require(entry, "active", Tag.TAG_STRING);
        if (!(entry.get("points") instanceof ListTag list)
                || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
                || list.size() > MAX_MARKERS) {
            throw new IllegalArgumentException("Waypoint list");
        }
        var book = new Book();
        for (var value : list) {
            var marker = NativeWaypointCodec.decode((CompoundTag) value);
            if (book.points.putIfAbsent(marker.name(), marker) != null) {
                throw new IllegalArgumentException("Duplicate waypoint");
            }
        }
        if (entry.contains("launch")) {
            require(entry, "launch", Tag.TAG_COMPOUND);
            var legacy = NativeWaypointCodec.decode(entry.getCompound("launch"));
            book.launches.put(legacy.planet(), legacy);
            book.recentLaunch = legacy.planet();
        }
        if (entry.contains("launches")) {
            require(entry, "launches", Tag.TAG_LIST);
            require(entry, "recentLaunch", Tag.TAG_INT);
            var launches = (ListTag) entry.get("launches");
            if (!launches.isEmpty() && launches.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("Launch list");
            book.launches.clear();
            for (var value : launches) {
                var marker = NativeWaypointCodec.decode((CompoundTag) value);
                if (book.launches.putIfAbsent(marker.planet(), marker) != null) throw new IllegalArgumentException("Duplicate launch planet");
            }
            book.recentLaunch = entry.getInt("recentLaunch");
            if (book.launches.isEmpty() ? book.recentLaunch != -1 : !book.launches.containsKey(book.recentLaunch)) throw new IllegalArgumentException("Missing recent launch");
        }
        book.active = entry.getString("active");
        if (!book.active.isEmpty() && !book.points.containsKey(book.active)) {
            throw new IllegalArgumentException("Missing active waypoint");
        }
        return book;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putInt("format", FORMAT);
        var all = new CompoundTag();
        players.forEach((id, book) -> {
            var entry = new CompoundTag();
            entry.putString("active", book.active);
            var launches = new ListTag();
            book.launches.values().forEach(marker -> launches.add(NativeWaypointCodec.encode(marker)));
            if (!launches.isEmpty()) {
                entry.put("launches", launches);
                entry.putInt("recentLaunch", book.recentLaunch);
            }
            var list = new ListTag();
            book.points.values().forEach(marker -> list.add(NativeWaypointCodec.encode(marker)));
            entry.put("points", list);
            all.put(id.toString(), entry);
        });
        tag.put("players", all);
        return tag;
    }
}
