package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.ground.GroundTopology;
import dev.starfold.worldgen.PlanetEcologyTest;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

/** Replays the request/serve/mesh schedule around face seams with the real policy classes.
 * Rates are modelled (tiles per tick); this is not a Minecraft, GPU or frame-time measurement. */
class SeamStreamingRateTest {
    /** Deliberately below SERVE_TILES: assumes the time budget only lets two captures through per tick. */
    private static final int SERVED_PER_TICK = 2;

    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    private static int[][] seams() {
        return new int[][] {{0, 1024}, {2047, 1024}, {1024, 0}, {1024, 2047}};
    }

    private static Point outward(int[] uv) {
        return new Point(
                uv[0] == 0 ? -1 : uv[0] == 2047 ? 1 : 0,
                0,
                uv[1] == 0 ? -1 : uv[1] == 2047 ? 1 : 0);
    }

    /** One client tick followed by one server tick, through the real request builder and subscription queue. */
    private static void exchange(
            int tick,
            int[] cursor,
            Collection<Long> resident,
            Set<Long> have,
            GroundViewPackets.Subscription queue,
            int perTick) {
        if (tick % GroundStreaming.REQUEST_TICKS == 0) {
            var keys = GroundStreaming.request(resident, cursor[0]++, have::contains);
            if (!keys.isEmpty()) {
                var tiles = new ArrayList<GroundViewPackets.Tile>();
                for (long key : keys)
                    tiles.add(new GroundViewPackets.Tile(key, have.contains(key) ? 1 : -1));
                assertTrue(queue.replace(new GroundViewPackets.Request(1, tiles), tick));
            }
        }
        for (int sent = 0; sent < perTick; ) {
            var tile = queue.poll();
            if (tile == null) break;
            if (tile.known() >= 0) continue;
            have.add(tile.key());
            sent++;
        }
    }

    @Test
    void coldWindowOnEveryDirectedSeamArrivesInSecondsNotHalfAMinute() {
        for (var face : Face.values())
            for (int[] uv : seams()) {
                var resident =
                        GroundStreaming.footprint(
                                new GroundTopology.Pose(
                                        face, new Point(uv[0] + .5, 20, uv[1] + .5)),
                                8);
                assertEquals(289, resident.size());
                var have = new HashSet<Long>();
                var queue = new GroundViewPackets.Subscription();
                int[] cursor = {0};
                int tick = 0;
                // The 5x5 under the player must be complete after the very first request.
                var near =
                        GroundStreaming.footprint(
                                new GroundTopology.Pose(
                                        face, new Point(uv[0] + .5, 20, uv[1] + .5)),
                                2);
                for (; !have.containsAll(resident) && tick < 2000; tick++) {
                    exchange(tick, cursor, resident, have, queue, SERVED_PER_TICK);
                    if (tick == GroundStreaming.REQUEST_TICKS + 3)
                        assertTrue(have.containsAll(near));
                }
                assertTrue(tick <= 200, "cold seam window took " + tick + " ticks");
            }
    }

    @Test
    void sprintFlightThroughEverySeamKeepsTheNearFieldDelivered() {
        double speed = 21.6 / 20;
        for (var face : Face.values())
            for (int[] uv : seams()) {
                var dir = outward(uv);
                var start = new Point(uv[0] + .5, 20, uv[1] + .5).add(dir.scale(-320));
                var stream = new GroundStreaming();
                var have = new HashSet<Long>();
                var queue = new GroundViewPackets.Subscription();
                int[] cursor = {0};
                int worst = 0;
                for (int tick = 0; tick < 800; tick++) {
                    // Warm up standing still, then cross the edge and continue 320 blocks into the
                    // next face.
                    var pose =
                            GroundTopology.canonical(
                                            face,
                                            start.add(dir.scale(speed * Math.max(0, tick - 200))))
                                    .pose();
                    stream.update(pose, 8);
                    have.retainAll(stream.resident());
                    exchange(tick, cursor, stream.resident(), have, queue, SERVED_PER_TICK);
                    if (tick < 200) continue;
                    assertTrue(
                            have.containsAll(GroundStreaming.footprint(pose, 5)),
                            "near field missing at tick " + tick);
                    int missing = 0;
                    for (long key : stream.required()) if (!have.contains(key)) missing++;
                    worst = Math.max(worst, missing);
                }
                // Never further behind than the two newest 17-tile rows at the far edge of the
                // window.
                assertTrue(worst <= 34, "delivery fell " + worst + " tiles behind");
            }
    }

    /** Mesh up to `perTick` tiles per tick while volumes arrive; returns total builds for the whole window. */
    private static int builds(
            Set<Long> resident, boolean waitForNeighbours, long never, int perTick) {
        var have = new HashSet<Long>();
        var built = new HashSet<Long>();
        var dirty = new HashSet<Long>();
        var queue = new GroundViewPackets.Subscription();
        int[] cursor = {0};
        int builds = 0, lastArrival = -1000;
        for (int tick = 0; tick < 4000; tick++) {
            int before = have.size();
            var arrived = new HashSet<>(have);
            exchange(tick, cursor, resident, have, queue, SERVED_PER_TICK);
            have.remove(never);
            for (long key : have)
                if (!arrived.contains(key)) {
                    dirty.add(key);
                    for (long n : GroundStreaming.neighbours(key)) dirty.add(n);
                }
            if (have.size() != before) lastArrival = tick;
            boolean stalled = tick - lastArrival >= GroundStreaming.STALL_TICKS;
            java.util.function.LongPredicate candidate =
                    k -> have.contains(k) && (!built.contains(k) || dirty.contains(k));
            boolean idle = false;
            for (int i = 0; i < perTick; i++) {
                long key =
                        waitForNeighbours
                                ? GroundStreaming.next(
                                        resident, candidate, have::contains, resident, stalled)
                                : resident.stream().filter(candidate::test).findFirst().orElse(-1L);
                if (key >= 0) {
                    built.add(key);
                    dirty.remove(key);
                    builds++;
                } else {
                    idle = true;
                    break;
                }
            }
            if (idle && have.size() >= resident.size() - (never >= 0 ? 1 : 0) && stalled) break;
        }
        var expected = new HashSet<>(resident);
        expected.remove(never);
        assertEquals(expected, built);
        return builds;
    }

    @Test
    void everyVolumeIsMeshedOnceInsteadOfOncePerArrivingNeighbour() {
        for (var face : Face.values())
            for (int[] uv : seams()) {
                var resident =
                        GroundStreaming.footprint(
                                new GroundTopology.Pose(
                                        face, new Point(uv[0] + .5, 20, uv[1] + .5)),
                                8);
                // Exactly one build per volume whether the mesher is slower or faster than
                // delivery.
                assertEquals(289, builds(resident, true, -1, 1));
                assertEquals(289, builds(resident, true, -1, 4));
                // The first-come order rebuilt finished meshes whenever a later neighbour arrived.
                assertTrue(builds(resident, false, -1, 4) >= 500);
            }
    }

    @Test
    void aVolumeThatNeverArrivesOnlyDelaysItsNeighboursUntilTheStreamStalls() {
        var resident =
                GroundStreaming.footprint(
                        new GroundTopology.Pose(Face.TOP, new Point(2040, 20, 1024)), 4);
        long never = new ArrayList<>(resident).get(12);
        // All other tiles still mesh; only the four neighbours need the stalled fallback.
        assertEquals(resident.size() - 1, builds(resident, true, never, 1));
        var have = new HashSet<>(resident);
        have.remove(never);
        long waiting = GroundStreaming.neighbours(never)[0];
        assertTrue(
                GroundStreaming.next(List.of(waiting), k -> true, have::contains, resident, false)
                        < 0);
        assertEquals(
                waiting,
                GroundStreaming.next(List.of(waiting), k -> true, have::contains, resident, true));
    }

    @Test
    void edgeNeighboursAreMutualAcrossEverySeamAndCorner() {
        for (int face = 0; face < 6; face++)
            for (int a = 0; a < 128; a++)
                for (int[] uv : new int[][] {{0, a}, {127, a}, {a, 0}, {a, 127}, {64, a}}) {
                    long key = uv[0] + 128L * (uv[1] + 128L * face);
                    var neighbours = GroundStreaming.neighbours(key);
                    assertEquals(4, Arrays.stream(neighbours).distinct().count());
                    for (long n : neighbours) {
                        assertNotEquals(key, n);
                        assertTrue(
                                Arrays.stream(GroundStreaming.neighbours(n))
                                        .anyMatch(back -> back == key),
                                key + " <-> " + n);
                    }
                }
    }

    @Test
    void requestsFavourMissingThenEditedTilesAndStillRotateRefreshes() {
        var ordered =
                new ArrayList<>(
                        GroundStreaming.footprint(
                                new GroundTopology.Pose(Face.EAST, new Point(1024, 20, .5)), 8));
        var have = new HashSet<>(ordered.subList(100, 289));
        long edited = ordered.get(250);
        var first = GroundStreaming.request(ordered, 0, have::contains, k -> k == edited);
        assertEquals(
                ordered.subList(0, GroundStreaming.REQUEST_MISSING),
                first.subList(0, GroundStreaming.REQUEST_MISSING));
        assertEquals(edited, first.get(GroundStreaming.REQUEST_MISSING));
        assertTrue(first.size() <= GroundStreaming.REQUEST_TILES);
        assertDoesNotThrow(
                () ->
                        new GroundViewPackets.Request(
                                1,
                                first.stream()
                                        .map(k -> new GroundViewPackets.Tile(k, -1))
                                        .toList()));
        var refreshed = new HashSet<Long>();
        int requests =
                (ordered.size() + GroundStreaming.REQUEST_REFRESH - 1)
                        / GroundStreaming.REQUEST_REFRESH;
        for (int i = 0; i < requests; i++) {
            var keys = GroundStreaming.request(ordered, i, k -> true, k -> false);
            assertTrue(keys.size() <= GroundStreaming.REQUEST_REFRESH);
            refreshed.addAll(keys);
        }
        assertEquals(new HashSet<>(ordered), refreshed);
    }

    @Test
    void requestCadenceSurvivesOneTickOfJitterButNotAFlood() {
        var queue = new GroundViewPackets.Subscription();
        var request = new GroundViewPackets.Request(1, List.of(new GroundViewPackets.Tile(1, -1)));
        assertTrue(queue.replace(request, 100));
        assertTrue(queue.replace(request, 100 + GroundStreaming.REQUEST_TICKS - 1));
        assertFalse(queue.replace(request, 100 + GroundStreaming.REQUEST_TICKS));
        assertEquals(1, queue.size());
    }

    @Test
    void snapshotKnowsItsHighestOccupiedLayerAndComparesSurfacesWithoutCopies() {
        var values = new int[GroundChunkSnapshot.VALUES];
        int air = Block.getId(Blocks.AIR.defaultBlockState()),
                stone = Block.getId(Blocks.STONE.defaultBlockState());
        Arrays.fill(values, air);
        assertEquals(
                GroundChunkSnapshot.MIN_Y - 1,
                GroundChunkSnapshot.fromStates(0, 0, 0, 1, values).top());
        for (int i = 0; i < 256 * (10 + 64 + 1); i++) values[i] = stone;
        values[5 + 16 * (7 + 16 * (200 + 64))] = Block.getId(Blocks.TORCH.defaultBlockState());
        var image = GroundChunkSnapshot.fromStates(0, 0, 0, 1, values);
        assertEquals(200, image.top());
        assertEquals(Blocks.TORCH.defaultBlockState(), image.state(5, 200, 7));
        assertEquals(Blocks.STONE.defaultBlockState(), image.state(15, 10, 15));
        assertTrue(image.state(0, 11, 0).isAir());
        var a = GroundViewCache.surfaceImage(image);
        var b = GroundViewCache.surfaceImage(image);
        assertTrue(GroundOwnership.same(a, a));
        assertTrue(GroundOwnership.same(a, b));
        assertFalse(GroundOwnership.same(a, null));
        assertFalse(GroundOwnership.same(null, a));
        values[0 + 16 * (0 + 16 * (201 + 64))] = stone;
        assertFalse(
                GroundOwnership.same(
                        a,
                        GroundViewCache.surfaceImage(
                                GroundChunkSnapshot.fromStates(0, 0, 0, 1, values))));
    }

    @Test
    void runtimeIsWiredToTheSharedSchedule() throws Exception {
        var renderer = Sources.text("GroundRenderer");
        var server = Sources.text("GroundViewServer");
        var client = Sources.text("GroundViewClient");
        assertTrue(renderer.contains("GroundStreaming.next(GroundClient.shape(),wanted,"));
        assertTrue(renderer.contains("GroundViewPolicy.NEAR_BUDGET_NS"));
        assertTrue(renderer.contains("while(cursor<limit)"));
        assertTrue(server.contains("served<GroundStreaming.SERVE_TILES"));
        assertTrue(server.contains("GroundStreaming.SERVE_BUDGET_NS"));
        assertTrue(client.contains("now-lastRequest<GroundStreaming.REQUEST_TICKS"));
        assertTrue(client.contains("invalidateEdit(key,packet.pos())"));
        assertTrue(client.contains("lastArrival=ticks"));
    }
}
