package dev.starfold.ground;

import dev.starfold.DevelopmentHooks;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetCatalog;
import dev.starfold.sky.Space;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** The near chart's other-face owners must actually tick, not merely be loaded display images. */
public final class GroundTickets {
    private static final TicketType<Long> TYPE =
            TicketType.create("starfold_native_chart", Long::compare);
    private static final Map<ServerLevel, Set<Long>> tickets = new HashMap<>();
    private static final TicketType<Long> VIEW =
            TicketType.create("starfold_native_view", Long::compare);
    private static final Map<ServerLevel, Set<Long>> views = new HashMap<>();
    private static final TicketType<Long> SPACE =
            TicketType.create("starfold_space_view", Long::compare);
    private static final Map<ServerLevel, Set<Long>> space = new HashMap<>();
    private static final Map<ServerLevel, Map<Window, Collection<Long>>> windows = new HashMap<>();

    record Window(PlanetCatalog catalog, int x, int z, int face, int radius) {
        Collection<Long> owners() {
            return face < 0
                    ? ImageStream.regionPositions(new ChunkPos(x, z), radius)
                    : otherFaceOwners(x, z, face, radius);
        }
    }

    public static boolean contains(ServerLevel level, ChunkPos pos) {
        return tickets.getOrDefault(level, Set.of())
                .contains(PlanetAccess.of(level).key(pos.x, pos.z));
    }

    /** Retire the empty region gradually: dropping the whole view also drops its generation halo.
     * Desired tickets win immediately, including re-entry; clear still releases everything at stop. */
    public static Set<Long> retiring(Set<Long> previous, Set<Long> desired) {
        var retained = new LinkedHashSet<>(previous);
        retained.addAll(desired);
        int removed = 0;
        for (var iterator = retained.iterator(); iterator.hasNext() && removed < 8; )
            if (!desired.contains(iterator.next())) {
                iterator.remove();
                removed++;
            }
        return retained;
    }

    /** One second of horizontal travel, capped at four chunks; retain the entire current halo. */
    public static AABB prefetch(AABB box, double vx, double vz) {
        double speed = Math.hypot(vx, vz);
        if (!Double.isFinite(speed) || speed == 0) return box;
        double distance = Math.min(speed * 20, 64);
        return box.expandTowards(vx / speed * distance, 0, vz / speed * distance);
    }

    public static void update(ServerLevel level) {
        var access = PlanetAccess.of(level);
        var desired = new HashSet<Long>();
        int radius = Math.clamp(level.getServer().getPlayerList().getSimulationDistance(), 2, 32);
        var region = new HashSet<Long>();
        var cached = windows.computeIfAbsent(level, ignored -> new HashMap<>());
        var used = new HashSet<Window>();
        var catalog = PlanetCatalog.current();
        for (var player : level.players()) {
            var pos = player.blockPosition();
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            if (Space.in(player)) {
                var window = new Window(catalog, cx, cz, -1, ImageStream.radius(player));
                if (used.add(window)) region.addAll(cached.computeIfAbsent(window, Window::owners));
                continue;
            }
            if (GroundHost.get().aloft(player) || !access.inChart(pos)) continue;
            if (GroundHost.get().controlled(player) && player.getY() < GroundCoordinates.FLOOR + 2048) {
                // Controlled flight clears deltaMovement. Its last tick's chart displacement
                // still supplies a direction; the cap also bounds seam/teleport discontinuities.
                var ahead =
                        prefetch(
                                new AABB(
                                        (cx - 1) * 16,
                                        0,
                                        (cz - 1) * 16,
                                        (cx + 2) * 16 - 1,
                                        0,
                                        (cz + 2) * 16 - 1),
                                player.getX() - player.xo,
                                player.getZ() - player.zo);
                // Existing tickets stream approach owners without synchronously reading terrain.
                for (int x = (int) Math.floor(ahead.minX) >> 4;
                        x <= ((int) Math.floor(ahead.maxX) >> 4);
                        x++)
                    for (int z = (int) Math.floor(ahead.minZ) >> 4;
                            z <= ((int) Math.floor(ahead.maxZ) >> 4);
                            z++) {
                        var query = new BlockPos(x * 16 + 8, 128, z * 16 + 8);
                        if (!access.inChart(query)) continue;
                        var owner = access.address(query).owner();
                        long key = access.key(owner.getX() >> 4, owner.getZ() >> 4);
                        if (key >= 0) desired.add(key);
                    }
            }
            if (player.getY() > GroundCoordinates.FLOOR + 320) continue;
            var window = new Window(catalog, cx, cz, access.face(player.getX()), radius);
            if (used.add(window)) desired.addAll(cached.computeIfAbsent(window, Window::owners));
        }
        cached.keySet().retainAll(used);
        if (cached.isEmpty()) windows.remove(level);
        // Owners shown as vanilla image chunks in an observer's chart. Distance zero holds FULL
        // chunks,
        // not entity-ticking chunks across the whole view.
        var previousSpace = space.getOrDefault(level, Set.of());
        var retained = retiring(previousSpace, region);
        for (long key : previousSpace)
            if (!retained.contains(key))
                level.getChunkSource().removeRegionTicket(SPACE, new ChunkPos(key), 0, key);
        for (long key : region)
            if (!previousSpace.contains(key))
                level.getChunkSource().addRegionTicket(SPACE, new ChunkPos(key), 0, key);
        space.put(level, retained);
        if (DevelopmentHooks.arrival.enabled() && !previousSpace.equals(retained))
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE REGION_TICKETS tick {} wanted {} held {} removed {}",
                            level.getGameTime(),
                            region.size(),
                            retained.size(),
                            previousSpace.stream().filter(key -> !retained.contains(key)).count());
        var loaded = ImageStream.owners(level);
        var priorViews = views.getOrDefault(level, Set.of());
        for (long key : priorViews)
            if (!loaded.contains(key))
                level.getChunkSource().removeRegionTicket(VIEW, access.chunk(key), 0, key);
        for (long key : loaded)
            if (!priorViews.contains(key))
                level.getChunkSource().addRegionTicket(VIEW, access.chunk(key), 0, key);
        views.put(level, loaded);
        var previous = tickets.getOrDefault(level, Set.of());
        for (long key : previous)
            if (!desired.contains(key))
                level.getChunkSource().removeRegionTicket(TYPE, access.chunk(key), 2, key);
        for (long key : desired)
            if (!previous.contains(key))
                level.getChunkSource().addRegionTicket(TYPE, access.chunk(key), 2, key);
        tickets.put(level, desired);
    }

    private static Set<Long> otherFaceOwners(int x, int z, int face, int radius) {
        var access = PlanetAccess.SHARED;
        var owners = new HashSet<Long>();
        for (int a = -radius; a <= radius; a++)
            for (int b = -radius; b <= radius; b++) {
                var pos = new BlockPos((x + a) * 16 + 8, 128, (z + b) * 16 + 8);
                if (!access.inChart(pos)) continue;
                var owner = access.address(pos).owner();
                if (access.face(owner.getX()) == face) continue;
                long key = access.key(owner.getX() >> 4, owner.getZ() >> 4);
                if (key >= 0) owners.add(key);
            }
        return owners;
    }

    public static void clear() {
        GroundHost.get().clearArrival();
        windows.clear();
        for (var e : space.entrySet())
            for (long key : e.getValue())
                e.getKey().getChunkSource().removeRegionTicket(SPACE, new ChunkPos(key), 0, key);
        space.clear();
        for (var e : tickets.entrySet())
            for (long key : e.getValue())
                e.getKey()
                        .getChunkSource()
                        .removeRegionTicket(TYPE, PlanetAccess.of(e.getKey()).chunk(key), 2, key);
        tickets.clear();
        for (var e : views.entrySet())
            for (long key : e.getValue())
                e.getKey()
                        .getChunkSource()
                        .removeRegionTicket(VIEW, PlanetAccess.of(e.getKey()).chunk(key), 0, key);
        views.clear();
    }

    private GroundTickets() {}
}
