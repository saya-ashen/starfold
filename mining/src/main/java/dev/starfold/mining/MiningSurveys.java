package dev.starfold.mining;

import dev.starfold.api.MiningMapApi;
import dev.starfold.api.MiningMapApi.Ore;
import dev.starfold.api.MiningMapApi.Survey;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.waypoint.Waypoint;
import dev.starfold.waypoint.WaypointPackets;
import dev.starfold.waypoint.WaypointSave;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-authored, owner-private completed surveys. Reads only the supplied scan results, never
 * terrain. All server entry points run on the server thread. This class has no dependency on Atlas.
 */
public final class MiningSurveys {
    private static final String KEY = "starfold_mining:surveys_v1";

    public enum Result {
        SAVED,
        SAVED_WITHOUT_WAYPOINT,
        OWNER_OFFLINE,
        INVALID_SURVEY,
        DATA_ERROR
    }

    /** Called once by the mining mod constructor, with its mod event bus. */
    public static void initialize(IEventBus bus) {
        bus.addListener(
                (RegisterPayloadHandlersEvent event) ->
                        event.registrar("1")
                                .optional()
                                .playToClient(
                                        MiningSurveyPayload.TYPE,
                                        MiningSurveyPayload.CODEC,
                                        (packet, context) ->
                                                context.enqueueWork(
                                                        () ->
                                                                MiningMapApi.acceptClientSurveys(
                                                                        packet.surveys()))));
        NeoForge.EVENT_BUS.addListener(
                (PlayerEvent.PlayerLoggedInEvent event) -> {
                    if (event.getEntity() instanceof ServerPlayer player) sync(player);
                });
        NeoForge.EVENT_BUS.addListener(
                (PlayerEvent.PlayerRespawnEvent event) -> {
                    if (event.getEntity() instanceof ServerPlayer player) sync(player);
                });
        NeoForge.EVENT_BUS.addListener(
                (PlayerEvent.PlayerChangedDimensionEvent event) -> {
                    if (event.getEntity() instanceof ServerPlayer player) sync(player);
                });
        NeoForge.EVENT_BUS.addListener(
                (PlayerEvent.Clone event) -> {
                    if (event.getEntity() instanceof ServerPlayer player
                            && event.getOriginal().getPersistentData().contains(KEY))
                        player.getPersistentData()
                                .put(
                                        KEY,
                                        event.getOriginal()
                                                .getPersistentData()
                                                .getCompound(KEY)
                                                .copy());
                });
        if (FMLEnvironment.dist == Dist.CLIENT) ClientEvents.initialize();
    }

    /**
     * Record a completed 5x5 scan, including scans shortened by the world floor. Depths are 1..512
     * blocks below pos; scannedBlocks is the actual number of examined positions. Call only after
     * the scan completes, never from a client packet or an incomplete scan tick.
     * OWNER_OFFLINE/INVALID_SURVEY/DATA_ERROR leave player NBT untouched. Offline work is not
     * queued: the backend must retain/retry that completion if it supports finishing with an
     * offline owner. At 32 sites the oldest snapshot is replaced; ordinary waypoints remain in the
     * player's book.
     */
    public static Result completed(
            ServerLevel level,
            BlockPos pos,
            UUID owner,
            List<Ore> ores,
            int scannedDepth,
            int scannedBlocks) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Survey completion requires server thread");
        if (owner == null || !PlanetLevels.is(level) || !PlanetAccess.of(level).inChart(pos))
            return Result.INVALID_SURVEY;
        var player = level.getServer().getPlayerList().getPlayer(owner);
        if (player == null) return Result.OWNER_OFFLINE;
        Survey next;
        try {
            var access = PlanetAccess.of(level);
            var pose = access.body(new Point(pos.getX() + .5, pos.getY(), pos.getZ() + .5));
            int slot = access.storagePlanet(pos);
            next =
                    new Survey(
                            level.dimension().location().toString(),
                            slot,
                            PlanetSystem.model(level).index(slot),
                            pose.face().ordinal(),
                            pose.point().x(),
                            pose.point().z(),
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            System.currentTimeMillis(),
                            scannedDepth,
                            scannedBlocks,
                            "",
                            ores);
        } catch (IllegalArgumentException | NullPointerException error) {
            return Result.INVALID_SURVEY;
        }
        try {
            var surveys = new ArrayList<>(read(player));
            Survey scan = next;
            var previous = surveys.stream().filter(s -> sameSite(s, scan)).findFirst().orElse(null);
            var save = WaypointSave.get(level);
            String marker = previous == null ? "" : previous.markerName();
            var existing = save.list(owner).get(marker);
            if (existing == null || !matches(existing, next)) marker = "";
            if (marker.isEmpty()) {
                String candidate =
                        "mining_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                try {
                    save.add(
                            owner,
                            new Waypoint(
                                    candidate,
                                    next.planetSlot(),
                                    new PlanetShape.Pose(
                                            Face.VALUES[next.face()],
                                            new Point(next.u(), 0, next.v()))));
                    marker = candidate;
                } catch (Waypoint.Failure error) {
                    // A full/duplicate waypoint book does not discard a successful survey.
                    player.sendSystemMessage(
                            Component.literal(
                                    "Survey saved; navigation waypoint could not be added (waypoint"
                                            + " limit "
                                            + Waypoint.MAX_MARKERS
                                            + "). Free a waypoint and rescan to enable"
                                            + " navigation."));
                }
            }
            next = withDisplay(next, next.body(), marker);
            Survey saved = next;
            surveys.removeIf(s -> sameSite(s, saved));
            if (surveys.size() == MiningMapApi.MAX_SITES) {
                surveys.remove(0);
                player.sendSystemMessage(
                        Component.literal(
                                "Survey history is full ("
                                        + MiningMapApi.MAX_SITES
                                        + "); the oldest snapshot was replaced. Its ordinary"
                                        + " waypoint is still in your book."));
            }
            surveys.add(next);
            write(player, surveys);
            // The ordinary waypoint list is independent of flight-session readiness.
            if (player.connection.hasChannel(WaypointPackets.Book.TYPE)) {
                var markers = new ArrayList<>(save.list(owner).values());
                markers.addAll(save.launches(owner).values());
                PacketDistributor.sendToPlayer(
                        player, new WaypointPackets.Book(List.copyOf(markers)));
            }
            sync(player);
            return marker.isEmpty() ? Result.SAVED_WITHOUT_WAYPOINT : Result.SAVED;
        } catch (IllegalArgumentException | IllegalStateException error) {
            dataError(player, error);
            return Result.DATA_ERROR;
        }
    }

    private static boolean sameSite(Survey a, Survey b) {
        return a.dimension().equals(b.dimension())
                && a.x() == b.x()
                && a.y() == b.y()
                && a.z() == b.z();
    }

    private static boolean matches(Waypoint marker, Survey survey) {
        return marker.planet() == survey.planetSlot()
                && marker.pose().face().ordinal() == survey.face()
                && marker.pose().point().x() == survey.u()
                && marker.pose().point().z() == survey.v();
    }

    static Survey withDisplay(Survey s, int body, String marker) {
        return new Survey(
                s.dimension(),
                s.planetSlot(),
                body,
                s.face(),
                s.u(),
                s.v(),
                s.x(),
                s.y(),
                s.z(),
                s.completedAt(),
                s.scannedDepth(),
                s.scannedBlocks(),
                marker,
                s.ores());
    }

    /**
     * Sends only this player's snapshots; there is deliberately no request carrying an owner UUID.
     */
    public static void sync(ServerPlayer player) {
        if (!player.connection.hasChannel(MiningSurveyPayload.TYPE)) return;
        try {
            var surveys = new ArrayList<Survey>();
            for (var saved : read(player)) {
                var key =
                        net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.DIMENSION,
                                net.minecraft.resources.ResourceLocation.parse(saved.dimension()));
                var level = player.getServer().getLevel(key);
                int body = -1;
                String marker = "";
                if (level != null && PlanetLevels.is(level)) {
                    var model = PlanetSystem.model(level);
                    if (saved.planetSlot() < model.count()) body = model.index(saved.planetSlot());
                    var point =
                            WaypointSave.get(level).list(player.getUUID()).get(saved.markerName());
                    if (point != null && matches(point, saved)) marker = point.name();
                }
                surveys.add(withDisplay(saved, body, marker));
            }
            PacketDistributor.sendToPlayer(player, new MiningSurveyPayload(List.copyOf(surveys)));
        } catch (IllegalArgumentException | IllegalStateException error) {
            // Clear stale client display but preserve damaged persisted data for recovery.
            PacketDistributor.sendToPlayer(player, new MiningSurveyPayload(List.of()));
            dataError(player, error);
        }
    }

    private static void dataError(ServerPlayer player, RuntimeException error) {
        LoggerFactory.getLogger("starfold_mining")
                .warn("Could not save/read private surveys for {}", player.getUUID(), error);
        player.sendSystemMessage(
                Component.literal(
                        "Mining survey data could not be read or saved. Existing discoveries were"
                                + " preserved."));
    }

    private static List<Survey> read(ServerPlayer player) {
        var data = player.getPersistentData();
        if (!data.contains(KEY)) return List.of();
        require(data, KEY, Tag.TAG_COMPOUND);
        var root = data.getCompound(KEY);
        require(root, "format", Tag.TAG_INT);
        require(root, "sites", Tag.TAG_LIST);
        if (root.getInt("format") != 1
                || !(root.get("sites") instanceof ListTag sites)
                || sites.size() > MiningMapApi.MAX_SITES
                || !sites.isEmpty() && sites.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid survey history");
        var result = new ArrayList<Survey>();
        for (var value : sites) {
            var site = decode((CompoundTag) value);
            if (result.stream().anyMatch(s -> sameSite(s, site)))
                throw new IllegalArgumentException("Duplicate survey site");
            result.add(site);
        }
        return List.copyOf(result);
    }

    private static void write(ServerPlayer player, List<Survey> surveys) {
        var sites = new ListTag();
        for (var survey : surveys) sites.add(encode(survey));
        var root = new CompoundTag();
        root.putInt("format", 1);
        root.put("sites", sites);
        player.getPersistentData().put(KEY, root);
    }

    private static CompoundTag encode(Survey s) {
        var tag = new CompoundTag();
        tag.putString("dimension", s.dimension());
        tag.putInt("planet", s.planetSlot());
        tag.putInt("face", s.face());
        tag.putDouble("u", s.u());
        tag.putDouble("v", s.v());
        tag.putInt("x", s.x());
        tag.putInt("y", s.y());
        tag.putInt("z", s.z());
        tag.putLong("time", s.completedAt());
        tag.putInt("depth", s.scannedDepth());
        tag.putInt("scanned", s.scannedBlocks());
        tag.putString("marker", s.markerName());
        var ores = new ListTag();
        for (var ore : s.ores()) {
            var entry = new CompoundTag();
            entry.putString("block", ore.blockId());
            entry.putInt("count", ore.count());
            entry.putInt("min", ore.minDepth());
            entry.putInt("max", ore.maxDepth());
            ores.add(entry);
        }
        tag.put("ores", ores);
        return tag;
    }

    private static Survey decode(CompoundTag tag) {
        for (String field : List.of("dimension", "marker")) require(tag, field, Tag.TAG_STRING);
        for (String field : List.of("planet", "face", "x", "y", "z", "depth", "scanned"))
            require(tag, field, Tag.TAG_INT);
        require(tag, "u", Tag.TAG_DOUBLE);
        require(tag, "v", Tag.TAG_DOUBLE);
        require(tag, "time", Tag.TAG_LONG);
        require(tag, "ores", Tag.TAG_LIST);
        if (!(tag.get("ores") instanceof ListTag list)
                || list.size() > MiningMapApi.MAX_ORES
                || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid survey ores");
        var ores = new ArrayList<Ore>();
        for (var value : list) {
            var entry = (CompoundTag) value;
            require(entry, "block", Tag.TAG_STRING);
            for (String field : List.of("count", "min", "max")) require(entry, field, Tag.TAG_INT);
            ores.add(
                    new Ore(
                            entry.getString("block"),
                            entry.getInt("count"),
                            entry.getInt("min"),
                            entry.getInt("max")));
        }
        return new Survey(
                tag.getString("dimension"),
                tag.getInt("planet"),
                -1,
                tag.getInt("face"),
                tag.getDouble("u"),
                tag.getDouble("v"),
                tag.getInt("x"),
                tag.getInt("y"),
                tag.getInt("z"),
                tag.getLong("time"),
                tag.getInt("depth"),
                tag.getInt("scanned"),
                tag.getString("marker"),
                ores);
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type))
            throw new IllegalArgumentException("Missing/invalid survey field: " + key);
    }

    /**
     * Kept behind a physical-dist guard so dedicated servers never resolve client event classes.
     */
    private static final class ClientEvents {
        static void initialize() {
            NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn
                                    event) -> MiningMapApi.clearClientSurveys());
            NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut
                                    event) -> MiningMapApi.clearClientSurveys());
        }
    }

    private MiningSurveys() {}
}
