package dev.starfold.dev;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.ship.*;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** Opt-in sleep/wake experiments and storage receipts for one selected hull. */
public final class DevShipPersistenceProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("starfold-probe");
    private static UUID excluded;
    private static final Set<UUID> holds = new HashSet<>();
    private static UUID watched;
    private static boolean awaitingLive;
    private static long since;

    private static void watch(ServerLevel level, UUID ship, boolean live) {
        watched = ship;
        awaitingLive = live;
        since = level.getGameTime();
        LOGGER.info(
                "SHIP_PROBE waiting={} uuid={} tick={}", live ? "live" : "dormant", ship, since);
    }

    public static void tick(ServerLevel level) {
        if (enabled()) {
            ShipDormancy.audit(level);
            DevShipHoldProbe.tick(level);
        }
        if (!enabled() || watched == null) return;
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        boolean live =
                container.getAllSubLevels().stream().anyMatch(h -> h.getUniqueId().equals(watched));
        if (live != awaitingLive || (!live && !SableTickets.held(level, watched).isEmpty())) return;
        LOGGER.info(
                "SHIP_PROBE transition={} uuid={} tick={} elapsed_ticks={}",
                live ? "live" : "dormant",
                watched,
                level.getGameTime(),
                level.getGameTime() - since);
        watched = null;
    }

    public static boolean enabled() {
        return Boolean.getBoolean("starfold.shipProbe");
    }

    public static boolean alwaysTicket(UUID ship) {
        return !enabled() || !ship.equals(excluded);
    }

    public static boolean held(UUID ship) {
        return enabled() && holds.contains(ship);
    }

    public static void clear() {
        excluded = null;
        holds.clear();
        watched = null;
        DevShipHoldProbe.clear();
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("shipprobe")
                .requires(s -> s.hasPermission(2) && enabled())
                .then(
                        Commands.argument("ship", StringArgumentType.string())
                                .then(
                                        Commands.argument(
                                                        "action", StringArgumentType.greedyString())
                                                .executes(
                                                        c ->
                                                                run(
                                                                        c.getSource(),
                                                                        StringArgumentType
                                                                                .getString(
                                                                                        c, "ship"),
                                                                        StringArgumentType
                                                                                .getString(
                                                                                        c,
                                                                                        "action")))));
    }

    private static int run(CommandSourceStack source, String name, String action) {
        var level = source.getServer().getLevel(PlanetLevels.PLANET);
        if (level == null) return fail(source, "No system level");
        var matches =
                ShipSave.get(level).list().stream()
                        .filter(
                                r ->
                                        name.equals(r.uuid().toString())
                                                || name.equals(
                                                        r.id().region() + ":" + r.id().index())
                                                || r.name().filter(name::equals).isPresent())
                        .toList();
        if (matches.size() != 1) return fail(source, "Expected one ship: " + name);
        var record = matches.getFirst();
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return fail(source, "No Sable container");
        var live =
                container.getAllSubLevels().stream()
                        .filter(h -> h.getUniqueId().equals(record.uuid()))
                        .findFirst()
                        .orElse(null);
        var map = container.getHoldingChunkMap();
        switch (action) {
            case "leases off" -> {
                excluded = record.uuid();
                watch(level, record.uuid(), false);
            }
            case "sleep" -> {
                excluded = record.uuid();
                ShipDormancy.requestSleep(record.uuid());
                watch(level, record.uuid(), false);
            }
            case "clock quarter" -> {
                if (!(record.state() instanceof ShipRecord.InSpace space))
                    return fail(source, "Need space place");
                var clock = GroundClock.get(level);
                double rate =
                        clock.clock
                                .model
                                .frame(space.place().body(), clock.clock.tick())
                                .angular()
                                .length();
                if (rate == 0) return fail(source, "Need spinning body");
                double day = 2 * Math.PI / rate;
                long ticks = (long) Math.ceil(day / 4);
                clock.skip(ticks);
                say(
                        source,
                        "SHIP_PROBE clock_advance="
                                + ticks
                                + " day="
                                + day
                                + " now="
                                + clock.clock.tick());
            }
            case "frame changed" -> {
                if (live != null || !(record.state() instanceof ShipRecord.InSpace space))
                    return fail(source, "Need dormant space hull");
                var clock = GroundClock.get(level).clock;
                var motion = ShipParking.evaluate(clock.model, space.place(), clock.tick());
                var shifted =
                        new Space.Motion(
                                motion.position().add(new Point(6000, 0, -2000)),
                                motion.orientation(),
                                motion.velocity(),
                                motion.spin());
                var frame =
                        ShipHost.get()
                                .departureFrame(
                                        level,
                                        shifted,
                                        new Quaterniond().rotateY(Math.PI / 3),
                                        clock.model,
                                        0,
                                        clock.tick());
                say(source, "SHIP_PROBE changed_frame=" + frame);
            }
            case "near current" -> {
                if (!(record.state() instanceof ShipRecord.InSpace space))
                    return fail(source, "Need space place");
                return moveSpace(source, level, space.place(), record.uuid(), false, true);
            }
            case "assert space-place" -> {
                return spacePlace(source, level, record, live);
            }
            case "old columns on", "old columns off" -> {
                return forceOld(source, level, record, map, action.endsWith("on"));
            }
            case "old", "assert old-ticking" -> {
                return oldPlace(source, level, record, map, action.equals("old"));
            }
            case "leases normal" -> {
                if (record.uuid().equals(excluded)) excluded = null;
            }
            case "hold on" -> holds.add(record.uuid());
            case "hold off" -> holds.remove(record.uuid());
            case "inspect" -> inspect(source, level, record, live, map);
            case "remove world-items" -> {
                if (live == null) return fail(source, "Need live hull");
                for (var entity : level.getAllEntities()) {
                    if (!(entity instanceof ItemEntity item)) continue;
                    var tests = SableTickets.aboardTests(live, entity);
                    if (tests.isEmpty() || tests.contains("plot")) continue;
                    say(
                            source,
                            "SHIP_PROBE remove_world_item uuid="
                                    + item.getUUID()
                                    + " stack="
                                    + item.getItem()
                                    + " position="
                                    + item.position());
                    item.discard();
                }
            }
            case "motion" -> {
                return motion(source, level, record, live);
            }
            case "assert hold-window" -> {
                if (!DevShipHoldProbe.passed(level, record.uuid()))
                    return fail(source, "Missing or failed 100/400/1200 server-tick hold readings");
            }
            case "assert checkpoint" -> {
                if (read(map, record) == null)
                    return fail(source, "Locator payload unavailable or UUID mismatch");
            }
            case "away", "near" -> {
                return move(source, level, record, action.equals("away"));
            }
            case "assert dormant" -> {
                if (live != null
                        || !SableTickets.held(level, record.uuid()).isEmpty()
                        || record.locator().isEmpty())
                    return fail(source, "Not dormant: " + record);
            }
            case "assert live" -> {
                if (live == null) return fail(source, "Not live");
            }
            case "assert entity-awake" -> {
                if (live == null
                        || !SableTickets.input(level, live)
                                .reasons()
                                .contains(ShipLeases.Reason.NON_PLAYER_ABOARD))
                    return fail(source, "No world-side entity retention");
            }
            case "assert aboard" -> {
                var player = source.getPlayer();
                if (live == null || player == null || SableFrames.aboard(player) != live)
                    return fail(source, "Player not aboard the original hull");
                var local =
                        live.logicalPose()
                                .transformPositionInverse(
                                        new Vector3d(player.getX(), player.getY(), player.getZ()));
                say(
                        source,
                        "SHIP_CREW uuid="
                                + live.getUniqueId()
                                + " local="
                                + local
                                + " position="
                                + player.position());
            }
            case "assert same-place" -> {
                return samePlace(source, record, live);
            }
            default -> {
                return fail(source, "Unknown shipprobe action: " + action);
            }
        }
        say(source, "SHIP_PROBE PASS " + name + " " + action);
        return 1;
    }

    /** Compare the same release material point even if serialization changed the rotation point. */
    private static int motion(
            CommandSourceStack source, ServerLevel level, ShipRecord record, ServerSubLevel live) {
        if (live == null) return fail(source, "Need live space hull");
        var held = ShipRecords.hold(level, live);
        if (held == null) held = DevShipHoldProbe.release(live.getUniqueId());
        if (held == null || ShipRecords.piloted(level, live))
            return fail(source, "No unpiloted body binding");
        var clock = GroundClock.get(level).clock;
        var frame = ShipHost.get().surroundingsFrame(level);
        var a = held.anchorPlot();
        var p = live.logicalPose().transformPosition(new Vector3d(a.x(), a.y(), a.z()));
        if (!Space.holds(p.x, p.z)) return fail(source, "Hull outside space");
        var now =
                ShipParking.fromLevel(
                        clock.model,
                        frame,
                        held.body(),
                        a,
                        new Point(p.x, p.y, p.z),
                        new Quaterniond(live.logicalPose().orientation()),
                        clock.tick());
        var delta = now.position().sub(held.position());
        double degrees =
                Math.toDegrees(
                        2
                                * Math.acos(
                                        Math.clamp(
                                                Math.abs(now.attitude().dot(held.attitude())),
                                                0,
                                                1)));
        say(
                source,
                "SHIP_HOLD id="
                        + record.id()
                        + " uuid="
                        + record.uuid()
                        + " name="
                        + record.name()
                        + " body="
                        + held.body()
                        + " tick="
                        + clock.tick()
                        + " release_tick="
                        + held.tick()
                        + " local_now="
                        + now.position()
                        + " attitude_now="
                        + now.attitude()
                        + " local_release="
                        + held.position()
                        + " attitude_release="
                        + held.attitude()
                        + " delta="
                        + delta
                        + " blocks="
                        + delta.length()
                        + " degrees="
                        + degrees);
        return 1;
    }

    private static SubLevelData read(SubLevelHoldingChunkMap map, ShipRecord record) {
        if (record.locator().isEmpty()) return null;
        var p = record.locator().get();
        var data =
                ((SableShipHoldingAccess) map)
                        .starfold$readShip(
                                new GlobalSavedSubLevelPointer(
                                        new ChunkPos(p.chunkX(), p.chunkZ()),
                                        p.storageIndex(),
                                        p.subLevelIndex()));
        return data != null && data.uuid().equals(record.uuid()) ? data : null;
    }

    private static void inspect(
            CommandSourceStack source,
            ServerLevel level,
            ShipRecord record,
            ServerSubLevel live,
            SubLevelHoldingChunkMap map) {
        var cached = map.getHoldingSubLevel(record.uuid());
        var data = read(map, record);
        var bounds =
                live != null
                        ? live.boundingBox()
                        : cached != null
                                ? cached.data().bounds()
                                : data == null ? null : data.bounds();
        var footprint = new TreeSet<Long>();
        if (bounds != null)
            SableTickets.columns(
                    footprint,
                    new AABB(
                                    bounds.minX(),
                                    bounds.minY(),
                                    bounds.minZ(),
                                    bounds.maxX(),
                                    bounds.maxY(),
                                    bounds.maxZ())
                            .inflate(1));
        long ticking =
                footprint.stream()
                        .filter(
                                k ->
                                        level.getChunkSource()
                                                .chunkMap
                                                .getDistanceManager()
                                                .inBlockTickingRange(k))
                        .count();
        if (live != null)
            for (var entity : level.getAllEntities()) {
                if (entity instanceof Player || entity.isRemoved()) continue;
                var matches = SableTickets.aboardTests(live, entity);
                if (!matches.isEmpty())
                    say(
                            source,
                            "SHIP_PROBE entity="
                                    + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                                    + " uuid="
                                    + entity.getUUID()
                                    + " position="
                                    + entity.position()
                                    + " aboard_tests="
                                    + new TreeSet<>(matches)
                                    + " keeps_awake="
                                    + !matches.contains("plot")
                                    + (entity instanceof ItemEntity item
                                            ? " stack=" + item.getItem()
                                            : ""));
            }
        var reasons = live == null ? Set.of() : SableTickets.input(level, live).reasons();
        say(
                source,
                "SHIP_PROBE record="
                        + record
                        + " live="
                        + (live != null)
                        + " holding_cache="
                        + (cached != null)
                        + " payload_readable="
                        + (data != null)
                        + " reasons="
                        + reasons
                        + " own_tickets="
                        + new TreeSet<>(SableTickets.held(level, record.uuid()))
                        + " footprint="
                        + footprint
                        + " block_ticking="
                        + ticking
                        + "/"
                        + footprint.size()
                        + " footprint_known="
                        + (bounds != null));
    }

    /** Surface-only observer teleport: no frame reanchor or hull wake request. */
    private static int move(
            CommandSourceStack source, ServerLevel level, ShipRecord record, boolean away) {
        if (record.state() instanceof ShipRecord.InSpace space)
            return moveSpace(source, level, space.place(), record.uuid(), away, false);
        if (!(record.state() instanceof ShipRecord.OnSlot slot) || slot.place().isEmpty())
            return fail(source, "Need a serialized chart place (save-all first)");
        var player = source.getPlayer();
        if (player == null || player.level() != level)
            return fail(source, "Need a player in the system level");
        var p = slot.place().get().position();
        if (!away) watch(level, record.uuid(), true);
        player.teleportTo(p.x() + (away ? 2000 : 0), p.y() + 24, p.z());
        say(source, "SHIP_PROBE observer=" + player.position() + " saved_anchor=" + p);
        return 1;
    }

    private static int moveSpace(
            CommandSourceStack source,
            ServerLevel level,
            ShipRecord.Place place,
            UUID ship,
            boolean away,
            boolean current) {
        var player = source.getPlayer();
        if (player == null) return fail(source, "Need player");
        if (away) {
            player.teleportTo(
                    source.getServer().overworld(),
                    0,
                    300,
                    0,
                    Set.of(),
                    player.getYRot(),
                    player.getXRot());
        } else {
            var clock = GroundClock.get(level).clock;
            var motion = ShipParking.evaluate(clock.model, place, clock.tick());
            var frame =
                    current
                            ? ShipHost.get().surroundingsFrame(level)
                            : ShipHost.get()
                                    .departureFrame(
                                            level,
                                            motion,
                                            new Quaterniond(),
                                            clock.model,
                                            ShipHost.get().surroundingsFrame(level).planet(),
                                            clock.tick());
            var at = frame.level(motion, clock.model, clock.tick()).position();
            watch(level, ship, true);
            player.teleportTo(
                    level,
                    at.x(),
                    at.y() + 24,
                    at.z(),
                    Set.of(),
                    player.getYRot(),
                    player.getXRot());
        }
        say(source, "SHIP_PROBE space_observer=" + player.position() + " away=" + away);
        return 1;
    }

    private static int forceOld(
            CommandSourceStack source,
            ServerLevel level,
            ShipRecord record,
            SubLevelHoldingChunkMap map,
            boolean on) {
        var data = read(map, record);
        if (data == null) return fail(source, "Need saved payload");
        var b = data.bounds();
        var columns = new HashSet<Long>();
        SableTickets.columns(
                columns, new AABB(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ).inflate(1));
        for (long k : columns) level.setChunkForced(ChunkPos.getX(k), ChunkPos.getZ(k), on);
        say(source, "SHIP_PROBE forced_old_columns=" + columns.size() + " on=" + on);
        return 1;
    }

    private static int oldPlace(
            CommandSourceStack source,
            ServerLevel level,
            ShipRecord record,
            SubLevelHoldingChunkMap map,
            boolean move) {
        var data = read(map, record);
        if (data == null) return fail(source, "Need saved payload");
        if (move) {
            var p = data.pose().position();
            var player = source.getPlayer();
            if (player == null) return fail(source, "Need player");
            player.teleportTo(
                    level, p.x, p.y + 24, p.z, Set.of(), player.getYRot(), player.getXRot());
        } else {
            var b = data.bounds();
            var columns = new HashSet<Long>();
            SableTickets.columns(
                    columns, new AABB(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ).inflate(1));
            long ready =
                    columns.stream()
                            .filter(
                                    k ->
                                            PhysicsChunkTicketManager.isChunkLoadedEnough(
                                                    level, ChunkPos.getX(k), ChunkPos.getZ(k)))
                            .count();
            say(source, "SHIP_PROBE old_columns_ready=" + ready + "/" + columns.size());
            if (ready != columns.size() || ready == 0)
                return fail(source, "Old footprint not ticking");
        }
        return 1;
    }

    private static int spacePlace(
            CommandSourceStack source, ServerLevel level, ShipRecord record, ServerSubLevel live) {
        var place = ShipDormancy.lastLoad(level, record.uuid());
        if (live == null || place == null) return fail(source, "Need woken space hull");
        var clock = GroundClock.get(level).clock;
        var a = place.anchorPlot();
        var at = live.logicalPose().transformPosition(new Vector3d(a.x(), a.y(), a.z()));
        var now =
                ShipParking.fromLevel(
                        clock.model,
                        ShipHost.get().surroundingsFrame(level),
                        place.body(),
                        a,
                        new Point(at.x, at.y, at.z()),
                        new Quaterniond(live.logicalPose().orientation()),
                        clock.tick());
        double blocks = now.position().sub(place.position()).length();
        double degrees =
                Math.toDegrees(
                        2
                                * Math.acos(
                                        Math.clamp(
                                                Math.abs(now.attitude().dot(place.attitude())),
                                                0,
                                                1)));
        say(
                source,
                "SHIP_WAKE_CHECK uuid="
                        + record.uuid()
                        + " tick="
                        + clock.tick()
                        + " blocks="
                        + blocks
                        + " degrees="
                        + degrees);
        var measured = ShipDormancy.measured(level, record.uuid());
        say(
                source,
                "SHIP_WAKE_TICKS count="
                        + measured[0]
                        + " max_blocks="
                        + measured[1]
                        + " max_degrees="
                        + measured[2]);
        return blocks < 1 && degrees < .1 && measured[0] > 0 && measured[1] < 1 && measured[2] < .1
                ? 1
                : fail(source, "Wake place changed");
    }

    private static int samePlace(
            CommandSourceStack source, ShipRecord record, ServerSubLevel live) {
        if (live == null
                || !(record.state() instanceof ShipRecord.OnSlot slot)
                || slot.place().isEmpty())
            return fail(source, "Need live hull and chart checkpoint");
        var place = slot.place().get();
        var a = place.anchorPlot();
        var p = live.logicalPose().transformPosition(new Vector3d(a.x(), a.y(), a.z()));
        var expected = place.position();
        double error = p.distance(expected.x(), expected.y(), expected.z());
        double attitude =
                Math.abs(new Quaterniond(live.logicalPose().orientation()).dot(place.attitude()));
        say(source, "SHIP_PROBE material_error=" + error + " attitude_dot=" + attitude);
        if (error > .5 || 1 - attitude > 1e-4) return fail(source, "Hull moved from checkpoint");
        return 1;
    }

    private static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal("SHIP_PROBE FAIL " + text));
        return 0;
    }

    private static void say(CommandSourceStack source, String text) {
        LOGGER.info(text);
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private DevShipPersistenceProbe() {}
}
