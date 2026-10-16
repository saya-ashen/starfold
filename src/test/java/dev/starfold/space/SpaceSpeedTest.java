package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.flight.ArrivalState;
import dev.starfold.flight.HudLayout;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ship.HelmPackets;
import dev.starfold.ship.HullLanding;
import dev.starfold.ship.StarterShip;
import dev.starfold.ship.Thrusters;
import dev.starfold.sky.Space;

import org.junit.jupiter.api.Test;

class SpaceSpeedTest {
    @Test
    void squareRootRuleAndClamps() {
        double a = SpaceSpeed.A_REF;
        for (double[] row :
                new double[][] {
                    {1, 600, 1200},
                    {.5, 600 / Math.sqrt(2), 1200 / Math.sqrt(2)},
                    {2, 600 * Math.sqrt(2), 1200 * Math.sqrt(2)},
                    {4, 1200, 2400},
                    {100, 2400, 2400},
                    {0, 200, 200},
                    {.001, 200, 200}
                }) {
            var c = SpaceSpeed.caps(a * row[0]);
            assertEquals(row[1], c.cruise(), 1e-9);
            assertEquals(row[2], c.boost(), 1e-9);
            assertEquals(c.cruise(), c.active(false));
            assertEquals(c.boost(), c.active(true));
        }
        assertEquals(0, SpaceSpeed.acceleration(0, 2228));
        assertEquals(0, SpaceSpeed.acceleration(3350, 0));
        assertEquals(200, SpaceSpeed.cap(Double.NaN));
        assertEquals(2400, SpaceSpeed.cap(Double.POSITIVE_INFINITY));
    }

    @Test
    void referenceComesFromShippedStructure() throws Exception {
        var root = java.nio.file.Path.of("src/main/resources/data/starfold/structure");
        var hull =
                net.minecraft.nbt.NbtIo.readCompressed(
                        root.resolve("starter_ship.nbt"),
                        net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        var weapon =
                net.minecraft.nbt.NbtIo.readCompressed(
                        root.resolve("starter_ship_weapon.nbt"),
                        net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        for (boolean armed : new boolean[] {false, true}) {
            var nbt = armed ? StarterShip.mergeWeapon(hull, weapon) : hull;
            var palette = nbt.getList("palette", 10);
            double mass = 0;
            int jets = 0, docks = 0;
            String bow = null;
            // Authored palette mass rules resolved from Sable's material tags in gravity1; no
            // runtime mass estimate.
            var light =
                    java.util.Set.of(
                            "minecraft:smooth_quartz_stairs",
                            "minecraft:smooth_quartz_slab",
                            "minecraft:polished_deepslate_slab",
                            "minecraft:smooth_stone_slab",
                            "minecraft:chest",
                            "create:shaft",
                            "create:cogwheel",
                            "create:andesite_funnel",
                            "minecraft:spruce_planks",
                            "create:piston_extension_pole");
            var superLight =
                    java.util.Set.of(
                            "minecraft:birch_door",
                            "minecraft:oak_door",
                            "minecraft:oak_trapdoor",
                            "minecraft:iron_trapdoor",
                            "minecraft:waxed_oxidized_copper_trapdoor",
                            "minecraft:ladder",
                            "minecraft:iron_bars",
                            "create:redstone_link");
            for (var tag : nbt.getList("blocks", 10)) {
                var state =
                        palette.getCompound(((net.minecraft.nbt.CompoundTag) tag).getInt("state"));
                String id = state.getString("Name");
                if (id.equals("minecraft:air")
                        || id.equals("minecraft:lever")
                        || id.equals("minecraft:oak_sign")
                        || id.equals("minecraft:oak_wall_sign")) continue;
                mass +=
                        light.contains(id)
                                ? .5
                                : superLight.contains(id)
                                        ? .25
                                        : id.equals("minecraft:iron_block")
                                                ? 4
                                                : id.equals("minecraft:stone") ? 2 : 1;
                if (id.equals("starfold:aircraft_dock")) docks++;
                if (id.equals("starfold:helm"))
                    bow = state.getCompound("Properties").getString("facing");
            }
            assertEquals("west", bow);
            for (var tag : nbt.getList("blocks", 10)) {
                var state =
                        palette.getCompound(((net.minecraft.nbt.CompoundTag) tag).getInt("state"));
                if (state.getString("Name").equals("starfold:thruster")
                        && state.getCompound("Properties").getString("facing").equals("east"))
                    jets++;
            }
            // dev.112 adds the open hangar and internal hatch; propulsion remains unchanged.
            assertEquals(1, docks);
            assertEquals(8, jets);
            assertEquals(armed ? 2277.5 : 2260.75, mass);
            // Keep dev.85's reference calibration fixed as hardware/loadout evolves.
            double a = SpaceSpeed.acceleration(jets * Thrusters.THRUST, mass);
            assertEquals(600 * Math.sqrt(2228 / mass), SpaceSpeed.caps(a).cruise(), 1e-9);
            assertEquals(1200 * Math.sqrt(2228 / mass), SpaceSpeed.caps(a).boost(), 1e-9);
            assertEquals(
                    armed ? 593.4438736515303 : 595.6382429881477,
                    SpaceSpeed.caps(a).cruise(),
                    1e-9);
            assertEquals(
                    armed ? 1186.8877473030607 : 1191.2764859762954,
                    SpaceSpeed.caps(a).boost(),
                    1e-9);
        }
    }

    @Test
    void brakingStopsWithinTwentySecondsAtEveryCap() {
        var nose = new Point(0, 0, 1);
        for (double boost : new double[] {200, 800, 1200, 2400}) {
            var c = new SpaceSpeed.Caps(boost / 2, boost);
            assertEquals(Math.max(40, boost / 20), c.brake());
            var v = nose.scale(boost / 20);
            int ticks = 0;
            while (v.length() * 20 > 1 && ticks < 400) {
                v = SpaceServer.cruise(v, v, nose.scale(-1), true, c);
                ticks++;
            }
            assertTrue(ticks <= 400);
            assertTrue(v.length() * 20 <= 1 + 1e-8);
        }
    }

    @Test
    void highSpeedShedRetainsSameFractionalTimeConstant() {
        var c = SpaceSpeed.caps(4 * SpaceSpeed.A_REF);
        var nose = new Point(0, 0, 1);
        var v = nose.scale(c.boost() / 20);
        for (int i = 1; i <= 200; i++) {
            v = SpaceServer.cruise(v, v.add(nose), nose, false, c);
            assertEquals(1200 + 1200 * Math.pow(1 / 1.05, i), v.length() * 20, 1e-8);
        }
    }

    @Test
    void arrivalNoticeBlendAndGovernorReachEntryWithoutHighSpeedOvershoot() {
        var body = dev.starfold.chart.GroundSolar.defaults().body(0);
        var nose = new Point(0, 0, 1);
        for (double initial : new double[] {800, 1200, 2400}) {
            double h = 1536 + initial * SpaceServer.ALIGN_TIME - initial / 20;
            var s = ArrivalState.cruise(0, "ship", 0);
            var v = nose.scale(initial / 20);
            int ticks = 0;
            while (h > 1536 && ticks++ < 2000) {
                s =
                        s.step(
                                0,
                                "ship",
                                h,
                                v.length() * 20,
                                SpaceServer.align(body, h, v.length() * 20, true),
                                h < 1536 + 300,
                                false,
                                false,
                                true);
                var governed = SpaceServer.govern(body, v, nose, v, h, true);
                v = v.add(governed.sub(v).scale(s.blend()));
                h -= v.z();
            }
            assertTrue(ticks < 2000);
            assertEquals(1, s.blend());
            // The prior tick's envelope can be at most 150/(1-1/120) at the crossing.
            assertTrue(v.length() * 20 <= 150 / (1 - 1.0 / 120) + 1e-8);
            assertEquals(150, SpaceServer.govern(body, v, nose, v, h, true).length() * 20, 1e-8);
            System.out.println(
                    "vmax arrival initial="
                            + initial
                            + " ticks="
                            + ticks
                            + " entrySpeed="
                            + v.length() * 20
                            + " height="
                            + h);
        }
    }

    @Test
    void regionHasMoreThanOneMaximumSpeedTickOfMargin() {
        double step = SpaceSpeed.V_MAX / 20;
        assertEquals(120, step);
        assertTrue(Space.ANCHOR_REACH + step < Space.REACH);
        assertTrue(Math.abs(Space.X) + Space.ANCHOR_REACH + step < 32768);
        assertTrue(Space.Y - Space.ANCHOR_REACH - step > Space.SABLE_MIN_Y);
        assertTrue(Space.Y + Space.ANCHOR_REACH + step < Space.SABLE_MAX_Y);
    }

    @Test
    void absoluteDustExposureAndEarlyDensitySaturation() {
        for (double cap : new double[] {200, 400, 600, 800, 1200, 2400}) {
            assertEquals(0, SpaceDust.rate(60, cap));
            assertEquals(28, SpaceDust.rate(Math.min(cap, 800), cap), 1e-9);
            for (double speed : new double[] {60, 150, 300, 800, 2400})
                if (cap >= 800)
                    assertEquals(SpaceDust.rate(speed), SpaceDust.rate(speed, cap), 1e-9);
        }
        assertEquals(18, SpaceDust.length(300));
        assertEquals(48, SpaceDust.length(800));
    }

    @Test
    void capsAndBoostRoundTripWithFlightDataAndDriveHudMarks() throws Exception {
        for (double a :
                new double[] {0, SpaceSpeed.A_REF / 2, SpaceSpeed.A_REF, SpaceSpeed.A_REF * 4})
            for (boolean boosted : new boolean[] {false, true}) {
                var c = SpaceSpeed.caps(a);
                var buf =
                        new net.minecraft.network.RegistryFriendlyByteBuf(
                                io.netty.buffer.Unpooled.buffer(),
                                net.minecraft.core.RegistryAccess.EMPTY);
                try {
                    var p =
                            new HelmPackets.State(
                                    java.util.UUID.randomUUID(),
                                    net.minecraft.core.BlockPos.ZERO,
                                    net.minecraft.core.Direction.WEST,
                                    true,
                                    true,
                                    0,
                                    true,
                                    0,
                                    0,
                                    0,
                                    HullLanding.Site.UNKNOWN,
                                    c.cruise(),
                                    c.boost(),
                                    boosted);
                    HelmPackets.State.CODEC.encode(buf, p);
                    var q = HelmPackets.State.CODEC.decode(buf);
                    assertEquals(p, q);
                    assertEquals(0, buf.readableBytes());
                    assertEquals(
                            c.cruise() / c.boost(),
                            HudLayout.fill(q.cruiseCap(), q.boostCap()),
                            1e-9);
                    assertEquals(1, HudLayout.fill(q.boostCap(), q.boostCap()));
                } finally {
                    buf.release();
                }
            }
        var client = Sources.text("SpaceNavigationClient");
        assertTrue(client.contains("flight.cruiseCap(),flight.boostCap()"));
        assertTrue(client.contains("boost=flight.boosted()"));
        assertFalse(client.contains("SpaceServer.CRUISE"));
        var widget = Sources.text("FlightWidgets");
        assertTrue(widget.contains("number(s.activeCap())"));
        assertTrue(widget.contains("number(s.cruiseCap())"));
        assertTrue(widget.contains("number(s.boostCap())"));
        var hook = Sources.text("GroundSurfaceChunkMixin");
        assertTrue(hook.contains("SpaceSpeedServer.changed(level,pos)"));
    }
}
