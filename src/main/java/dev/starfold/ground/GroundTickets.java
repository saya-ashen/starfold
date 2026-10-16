package dev.starfold.ground;

import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.probe.ArrivalProbe;
import dev.starfold.sky.Space;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;

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
    // Retained empty per-level bookkeeping; no stream values are inserted here.
    private static final Map<ServerLevel, Map<UUID, Object>> streams = new HashMap<>();

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

    public static void update(ServerLevel level) {
        var access = PlanetAccess.of(level);
        var desired = new HashSet<Long>();
        var loaded = new HashSet<Long>();
        int radius = Math.clamp(level.getServer().getPlayerList().getSimulationDistance(), 2, 32);
        var worldStreams = streams.computeIfAbsent(level, l -> new HashMap<UUID, Object>());
        var present = new HashSet<UUID>();
        var region = new HashSet<Long>();
        for (var player : level.players()) {
            if (Space.in(player)) {
                region.addAll(
                        ImageStream.regionPositions(
                                new ChunkPos(player.blockPosition()), ImageStream.radius(player)));
                continue;
            }
            if (GroundHost.get().controlled(player)
                    && access.inChart(player.blockPosition())
                    && player.getY() < GroundCoordinates.FLOOR + 2048) {
                // Nine approach owners only. Region tickets let the chunk pipeline stream them
                // before collision handoff; views and HUD never synchronously generate them.
                int cx = player.blockPosition().getX() >> 4,
                        cz = player.blockPosition().getZ() >> 4;
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) {
                        var query = new BlockPos((cx + dx) * 16 + 8, 128, (cz + dz) * 16 + 8);
                        if (!access.inChart(query)) continue;
                        var owner = access.address(query).owner();
                        long key = access.key(owner.getX() >> 4, owner.getZ() >> 4);
                        if (key >= 0) desired.add(key);
                    }
            }
            if (!access.inChart(player.blockPosition())
                    || player.getY() > GroundCoordinates.FLOOR + 320) continue;
            int root = access.face(player.getX()),
                    x = player.blockPosition().getX() >> 4,
                    z = player.blockPosition().getZ() >> 4;
            for (int a = -radius; a <= radius; a++)
                for (int b = -radius; b <= radius; b++) {
                    var pos = new BlockPos((x + a) * 16 + 8, 128, (z + b) * 16 + 8);
                    if (!access.inChart(pos)) continue;
                    var owner = access.address(pos).owner();
                    if (access.face(owner.getX()) == root) continue;
                    long key = access.key(owner.getX() >> 4, owner.getZ() >> 4);
                    if (key >= 0) desired.add(key);
                }
        }
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
        if (ArrivalProbe.ENABLED && !previousSpace.equals(retained))
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE REGION_TICKETS tick {} wanted {} held {} removed {}",
                            level.getGameTime(),
                            region.size(),
                            retained.size(),
                            previousSpace.stream().filter(key -> !retained.contains(key)).count());
        loaded.addAll(ImageStream.owners(level));
        worldStreams.keySet().retainAll(present);
        var priorViews = views.getOrDefault(level, Set.of());
        for (long key : priorViews)
            if (!loaded.contains(key))
                level.getChunkSource().removeRegionTicket(VIEW, access.chunk(key), 0, key);
        for (long key : loaded)
            if (!priorViews.contains(key))
                level.getChunkSource().addRegionTicket(VIEW, access.chunk(key), 0, key);
        views.put(level, Set.copyOf(loaded));
        var previous = tickets.getOrDefault(level, Set.of());
        for (long key : previous)
            if (!desired.contains(key))
                level.getChunkSource().removeRegionTicket(TYPE, access.chunk(key), 2, key);
        for (long key : desired)
            if (!previous.contains(key))
                level.getChunkSource().addRegionTicket(TYPE, access.chunk(key), 2, key);
        tickets.put(level, Set.copyOf(desired));
    }

    public static void clear() {
        GroundHost.get().clearArrival();
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
        streams.clear();
    }

    private GroundTickets() {}
}
