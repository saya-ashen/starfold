package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.GroundSeams;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import java.util.*;

class ShipLeasesTest {
    private static ShipLeases.Input input(UUID id, AABB raw, List<GroundSeams.Transport> seams) {
        var footprint = new HashSet<Long>();
        SableTickets.columns(footprint, raw.inflate(1));
        var cargo = new HashSet<Long>();
        SableTickets.columns(cargo, raw.inflate(32));
        for (var seam : seams) SableTickets.columns(cargo, raw.inflate(32), seam);
        return new ShipLeases.Input(id, footprint, Set.of(ShipLeases.Reason.ALWAYS), cargo);
    }

    /** The old update's inclusive floor/shift loops, including the whole 32-block halo. */
    private static void oldColumns(Set<Long> columns, AABB box) {
        for (int x = (int) Math.floor(box.minX) >> 4; x <= ((int) Math.floor(box.maxX) >> 4); x++)
            for (int z = (int) Math.floor(box.minZ) >> 4;
                    z <= ((int) Math.floor(box.maxZ) >> 4);
                    z++) columns.add(ChunkPos.asLong(x, z));
    }

    @Test
    void alwaysOnPlanEqualsOldColumnsForHaloSeamsAndOverlappingHulls() {
        var boxes =
                List.of(
                        new AABB(-16.01, 0, -.01, 0, 1, 16),
                        new AABB(5115, 230, 2043, 5125, 240, 2053),
                        new AABB(5116, 235, 2044, 5126, 245, 2054),
                        new AABB(1000, -64, 1000, 3000, 512, 4000));
        for (int destinations : new int[] {0, 1, 4}) {
            for (int turn = 0; turn < 4; turn++) {
                var seams =
                        List.of(
                                        new GroundSeams.Transport(
                                                new Point(5120, 235, 2048),
                                                new Point(18432, 235, 1024),
                                                turn),
                                        new GroundSeams.Transport(
                                                new Point(5120, 235, 2048),
                                                new Point(9000, 235, -6000),
                                                (turn + 1) % 4),
                                        new GroundSeams.Transport(
                                                new Point(5120, 235, 2048),
                                                new Point(-8000, 235, 2000),
                                                (turn + 2) % 4),
                                        new GroundSeams.Transport(
                                                new Point(5120, 235, 2048),
                                                new Point(18000, 235, 6000),
                                                (turn + 3) % 4))
                                .subList(0, destinations);
                var old = new HashSet<Long>();
                var inputs = new ArrayList<ShipLeases.Input>();
                for (var raw : boxes) {
                    oldColumns(old, raw.inflate(32));
                    for (var seam : seams) {
                        var box = raw.inflate(32);
                        var a = seam.apply(new Point(box.minX, box.minY, box.minZ));
                        var b = seam.apply(new Point(box.maxX, box.maxY, box.maxZ));
                        oldColumns(old, new AABB(a.x(), a.y(), a.z(), b.x(), b.y(), b.z()));
                    }
                    inputs.add(input(UUID.randomUUID(), raw, seams));
                }
                assertEquals(
                        old,
                        ShipLeases.plan(inputs).columns(),
                        "turn=" + turn + " destinations=" + destinations);
                // Empty container/removed hulls yield no inputs in both implementations.
                assertTrue(ShipLeases.plan(List.of()).columns().isEmpty());
            }
        }
    }

    @Test
    void releasingOneShipKeepsSharedColumnsAndDimensionsStayIndependent() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var first =
                new ShipLeases.Input(
                        a, Set.of(1L, 2L), Set.of(ShipLeases.Reason.PILOT), Set.of(2L, 3L));
        var second =
                new ShipLeases.Input(b, Set.of(2L, 4L), Set.of(ShipLeases.Reason.WAKING), Set.of());
        var plan = ShipLeases.plan(List.of(first, second));
        assertEquals(Map.of(1L, 1, 2L, 2, 3L, 1, 4L, 1), plan.references());
        var asleep = new ShipLeases.Input(a, first.footprint(), Set.of(), first.cargo());
        var released = ShipLeases.plan(List.of(asleep, second));
        assertEquals(Set.of(2L, 4L), released.columns());
        assertEquals(1, released.references().get(2L));
        assertTrue(released.columns(a).isEmpty());
        var otherDimension = ShipLeases.plan(List.of(first));
        assertEquals(Set.of(1L, 2L, 3L), otherDimension.columns());
        assertThrows(IllegalArgumentException.class, () -> ShipLeases.plan(List.of(first, first)));
        assertThrows(UnsupportedOperationException.class, () -> plan.columns(a).add(99L));
        assertThrows(UnsupportedOperationException.class, () -> plan.references().put(99L, 1));
    }

    @Test
    void eachRealReasonRetainsCurrentNextAndCargoColumnsButNoReasonRetainsNone() {
        var id = UUID.randomUUID();
        for (var reason : ShipLeases.Reason.values()) {
            var input = new ShipLeases.Input(id, Set.of(1L, 2L), Set.of(reason), Set.of(3L));
            assertEquals(Set.of(1L, 2L, 3L), ShipLeases.plan(List.of(input)).columns(id));
        }
        assertTrue(
                ShipLeases.plan(List.of(new ShipLeases.Input(id, Set.of(1L), Set.of(), Set.of(3L))))
                        .columns()
                        .isEmpty());
        var box = new AABB(0, 0, 0, 1000, 20, 10);
        assertTrue(SableTickets.near(box, new Vec3(1384, 20, 10), 384));
        assertFalse(SableTickets.near(box, new Vec3(1384.001, 20, 10), 384));
        assertFalse(SableTickets.near(box, new Vec3(1000, 405, 10), 384));
    }

    @Test
    void plotResidentsNeverKeepWorldCargoAwakeEvenWhenTracked() {
        for (boolean tracking : new boolean[] {false, true})
            for (boolean inside : new boolean[] {false, true})
                for (boolean plot : new boolean[] {false, true}) {
                    var matches = new HashSet<String>();
                    if (tracking) matches.add("tracking");
                    if (inside) matches.add("inside");
                    if (plot) matches.add("plot");
                    assertEquals(!plot && (tracking || inside), SableTickets.worldCargo(matches));
                }
    }

    @Test
    void productionRetainsAlwaysAndOnlyDevCanSuppressItWithoutUnloading() throws Exception {
        var host = Sources.text("ShipHost");
        assertTrue(
                host.contains(
                        "default boolean alwaysTicket(java.util.UUID ship) { return true; }"));
        var tickets = Sources.text("SableTickets");
        assertTrue(
                tickets.contains(
                        "if (ShipHost.get().alwaysTicket(body.getUniqueId())) reasons.add(ShipLeases.Reason.ALWAYS)"));
        assertTrue(tickets.contains("raw.inflate(32)"));
        assertTrue(tickets.contains("for (int i = 0; i < 4; i++)"));
        assertTrue(tickets.contains("(i & 1) == 0 ? box.minX : box.maxX"));
        assertTrue(tickets.contains("(i & 2) == 0 ? box.minZ : box.maxZ"));
        assertTrue(tickets.contains("TicketType.create(\"starfold_sable_cargo\", Long::compare)"));
        assertTrue(tickets.contains("addRegionTicket(TYPE, new ChunkPos(key), 2, key)"));
        assertTrue(tickets.contains("removeRegionTicket(TYPE, new ChunkPos(key), 2, key)"));
        assertTrue(tickets.contains("SUB_LEVEL_TRACKING_RANGE.get() + 64"));
        assertTrue(tickets.contains("ShipLeases.Reason.NON_PLAYER_ABOARD"));
        assertTrue(tickets.contains("ShipLeases.Reason.PLAYER_ABOARD"));
        assertTrue(tickets.contains("ShipLeases.Reason.PLAYER_NEAR"));
        assertTrue(tickets.contains("HelmServer.hasPilot(body)"));
        assertTrue(
                tickets.indexOf("if (reasons.contains(ShipLeases.Reason.ALWAYS)) return reasons;")
                        < tickets.indexOf("HelmServer.hasPilot(body)"),
                "Default tickets must not add aboard-cache reads");
        var probe = Sources.text("DevShipPersistenceProbe");
        assertTrue(probe.contains("Boolean.getBoolean(\"starfold.shipProbe\")"));
        assertTrue(probe.contains("!enabled() || !ship.equals(excluded)"));
        assertTrue(probe.contains("getHoldingSubLevel(record.uuid())"));
        assertTrue(probe.contains("inBlockTickingRange(k)"));
        assertTrue(probe.contains("holds.clear()"));
        for (var text : List.of(tickets, probe))
            for (var forbidden :
                    List.of(
                            "moveToUnloaded(",
                            "snatchAndLoad(",
                            "removeSubLevel(",
                            "addForceLoadTicket("))
                assertFalse(text.contains(forbidden), forbidden);
        assertTrue(Sources.text("StarfoldServer").contains("DevShipPersistenceProbe.clear()"));
    }
}
