package dev.starfold.space;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.ArrivalState;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.GroundServer;
import dev.starfold.ground.ObserverFrames;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.probe.ArrivalProbe;
import dev.starfold.ship.HelmCommand;
import dev.starfold.ship.HelmServer;
import dev.starfold.ship.SableFrames;
import dev.starfold.ship.ShipParking;
import dev.starfold.ship.ShipRecords;
import dev.starfold.ship.SpacePackets;
import dev.starfold.ship.Thrusters;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import org.joml.Quaterniond;

/** The region and its celestial clock belong to this level. */
public final class SpaceServer extends SavedData {
    public static final String FILE = "starfold_native_space_v2";

    /** Cruise has no down; near a planet the deck must be ready before hand-over. Degrees per second. */
    static final double PILOT_TURN = 45,
            MOUSE_TURN = 180,
            ALIGN_TURN = 30,
            ALIGN_TIME = 12,
            ASSIST = 1.5;

    static final double VACUUM = 3,
            SHED = 1,
            BRAKE = 40,
            BRAKE_FLOOR = 1,
            ARRIVE = 150,
            ARRIVE_TIME = 6,
            NEAR_TIME = 3;
    static final double MISS_IN = 500, MISS_OUT = 1000;
    static final int WISH_TICKS = 5;

    private record Wish(
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            float pitch,
            float roll,
            double mouseYaw,
            double mousePitch,
            long tick) {}

    private static final java.util.Map<net.minecraft.server.level.ServerPlayer, Wish> wishes =
            new java.util.WeakHashMap<>();

    private record Target(
            ServerLevel level, dev.ryanhcode.sable.sublevel.ServerSubLevel body, int planet) {}

    private static final java.util.Map<net.minecraft.server.level.ServerPlayer, Target> targets =
            new java.util.WeakHashMap<>();
    private Space.Frame frame;
    private long epoch;
    private Point holdTurn = GroundEphemeris.ZERO;
    private java.util.UUID turningLead;
    private ArrivalState arrivalState = ArrivalState.empty();
    private dev.ryanhcode.sable.sublevel.ServerSubLevel inboundBody;
    private int inboundPlanet = -1;
    private boolean inbound, arriving;
    private static final java.util.Map<net.minecraft.server.level.ServerPlayer, ServerLevel>
            observers = new java.util.WeakHashMap<>();

    private record View(ServerLevel level, SpacePackets.View packet) {}

    private static final java.util.Map<net.minecraft.server.level.ServerPlayer, View> views =
            new java.util.WeakHashMap<>();
    private static final java.util.Map<net.minecraft.world.entity.LivingEntity, ServerLevel>
            weightless = new java.util.WeakHashMap<>();
    private static final net.minecraft.resources.ResourceLocation WEIGHTLESS =
            net.minecraft.resources.ResourceLocation.parse("starfold:space_weightless");

    public static void initialize() {
        if (ArrivalProbe.ENABLED)
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
                            event.getDispatcher()
                                    .register(
                                            net.minecraft.commands.Commands.literal("arrivalprobe")
                                                    .requires(s -> s.hasPermission(2))
                                                    .then(
                                                            net.minecraft.commands.Commands
                                                                    .argument(
                                                                            "planet",
                                                                            com.mojang.brigadier
                                                                                    .arguments
                                                                                    .IntegerArgumentType
                                                                                    .integer(0))
                                                                    .then(
                                                                            net.minecraft.commands
                                                                                    .Commands
                                                                                    .argument(
                                                                                            "face",
                                                                                            com
                                                                                                    .mojang
                                                                                                    .brigadier
                                                                                                    .arguments
                                                                                                    .IntegerArgumentType
                                                                                                    .integer(
                                                                                                            0,
                                                                                                            5))
                                                                                    .executes(
                                                                                            c -> {
                                                                                                probeArrival(
                                                                                                        c.getSource()
                                                                                                                .getPlayerOrException(),
                                                                                                        com
                                                                                                                .mojang
                                                                                                                .brigadier
                                                                                                                .arguments
                                                                                                                .IntegerArgumentType
                                                                                                                .getInteger(
                                                                                                                        c,
                                                                                                                        "planet"),
                                                                                                        com
                                                                                                                .mojang
                                                                                                                .brigadier
                                                                                                                .arguments
                                                                                                                .IntegerArgumentType
                                                                                                                .getInteger(
                                                                                                                        c,
                                                                                                                        "face"));
                                                                                                return 1;
                                                                                            })))));
        StarfoldNetwork.playS2C(SpacePackets.Frame.TYPE, SpacePackets.Frame.CODEC);
        StarfoldNetwork.playC2S(SpacePackets.Attitude.TYPE, SpacePackets.Attitude.CODEC);
        StarfoldNetwork.serverReceiver(
                SpacePackets.Attitude.TYPE,
                (packet, c) -> wish((net.minecraft.server.level.ServerPlayer) c.player(), packet));
        StarfoldNetwork.playS2C(SpacePackets.View.TYPE, SpacePackets.View.CODEC);
        StarfoldNetwork.playC2S(SpacePackets.Target.TYPE, SpacePackets.Target.CODEC);
        StarfoldNetwork.serverReceiver(
                SpacePackets.Target.TYPE,
                (packet, c) ->
                        target(
                                (net.minecraft.server.level.ServerPlayer) c.player(),
                                packet.planet()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent
                                event) ->
                        sync((net.minecraft.server.level.ServerPlayer) event.getEntity()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) ->
                        sync((net.minecraft.server.level.ServerPlayer) event.getEntity()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent
                                event) ->
                        sync((net.minecraft.server.level.ServerPlayer) event.getEntity()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
                                event) -> {
                    observers.remove(event.getEntity());
                    views.remove(event.getEntity());
                    wishes.remove(event.getEntity());
                    targets.remove(event.getEntity());
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> {
                    observers.clear();
                    views.clear();
                    wishes.clear();
                    targets.clear();
                    weightless.clear();
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.level.LevelEvent.Unload event) -> {
                    if (event.getLevel() instanceof ServerLevel level) SpaceApproach.release(level);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.LevelTickEvent.Pre event) -> {
                    if (event.getLevel() instanceof ServerLevel level) tick(level);
                });
    }

    /** Development fixture: relocate only the occupied region's far-away reference, never load a chart. */
    private static void probeArrival(
            net.minecraft.server.level.ServerPlayer player, int planet, int face) {
        if (!ArrivalProbe.ENABLED || !Space.in(player))
            throw new IllegalStateException("Arrival fixture requires region pilot");
        var level = player.serverLevel();
        var body = SableFrames.aboard(player);
        if (body == null) throw new IllegalStateException("No pilot body");
        var clock = GroundClock.get(level).clock;
        var shape = PlanetShape.of(planet);
        var target = clock.model.at(planet, clock.tick() + 500);
        var offset =
                target.vector(
                                shape.direction(
                                        CubeSurfaceTopology.Face.values()[face],
                                        face == 1 ? 1700 : shape.size() / 2.0,
                                        face == 1 ? 900 : shape.size() / 2.0))
                        .scale(shape.radius() + 7500);
        var at = body.logicalPose().position();
        var value = get(level);
        var old = value.frame;
        value.frame =
                new Space.Frame(
                        old.rotation(),
                        old.shift(),
                        planet,
                        offset.sub(
                                old.universeVector(new Point(at.x, at.y, at.z).sub(old.shift()))),
                        new Point(0, 0, 0),
                        clock.tick());
        value.epoch = clock.tick();
        value.setDirty();
        SpaceApproach.release(level);
        for (var p : level.players()) if (Space.in(p)) send(p);
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE ARRIVAL_SETUP planet {} face {} height 7500 game {}",
                        planet,
                        face,
                        level.getGameTime());
    }

    public static void probeSpeed(net.minecraft.server.level.ServerPlayer player, double speed) {
        if (!Boolean.getBoolean("starfold.helmProbe")
                || !Space.in(player)
                || !HelmServer.active(player))
            throw new IllegalStateException("Speed fixture requires development region pilot");
        var value = get(player.serverLevel());
        var f = value.frame.rebased(GroundClock.get(player.serverLevel()).clock.tick());
        var nose = HelmServer.forward(SableFrames.aboard(player));
        value.frame =
                new Space.Frame(
                        f.rotation(),
                        f.shift(),
                        f.planet(),
                        f.offset(),
                        f.universeVector(nose).scale(speed / 20),
                        f.epoch());
        value.setDirty();
        send(player);
    }

    /** End chart ownership without changing position or velocity, then announce passive coordinates. */
    public static void sync(net.minecraft.server.level.ServerPlayer player) {
        targets.remove(player);
        if (Space.in(player)) {
            GroundFlightServer.region(player);
            ObserverFrames.announce(player);
            observers.put(player, player.serverLevel());
        } else observers.remove(player);
        send(player);
        view(player);
        if (Space.in(player)) GroundServer.regionSky(player);
    }

    public static SpacePackets.Frame packet(net.minecraft.server.level.ServerPlayer player) {
        return new SpacePackets.Frame(
                Space.in(player) ? frame(player.serverLevel()) : null,
                Space.in(player) ? get(player.serverLevel()).epoch : 0,
                Space.in(player) && get(player.serverLevel()).arriving,
                Space.in(player) ? get(player.serverLevel()).arrivalState : ArrivalState.empty());
    }

    private static void send(net.minecraft.server.level.ServerPlayer player) {
        send(player, false);
    }

    private static void send(net.minecraft.server.level.ServerPlayer player, boolean turning) {
        var packet = packet(player);
        if (!SableFrames.carrying(player) && player.connection.hasChannel(SpacePackets.Frame.TYPE))
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, packet);
    }

    /** Repeated metadata tolerates packets arriving while a client is between Levels; never captures a flight. */
    public static long view(net.minecraft.server.level.ServerPlayer player) {
        if (!Space.in(player)) {
            views.remove(player);
            if (player.connection.hasChannel(SpacePackets.View.TYPE))
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        player, new SpacePackets.View(0, GroundSpace.DEFAULT_MASK));
            return 0;
        }
        var mask = GroundClock.get(player.serverLevel()).mask;
        var v = views.get(player);
        boolean fresh =
                v == null || v.level() != player.serverLevel() || !v.packet().mask().equals(mask);
        if (fresh) {
            long id = java.util.UUID.randomUUID().getMostSignificantBits();
            v = new View(player.serverLevel(), new SpacePackets.View(id == 0 ? 1 : id, mask));
            views.put(player, v);
        }
        if (fresh) send(player);
        if (fresh || player.level().getGameTime() % 20 == 0) {
            if (player.connection.hasChannel(SpacePackets.View.TYPE))
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, v.packet());
            SpaceHost.get().targetSync(player, v.packet().session());
        }
        return v.packet().session();
    }

    static void tick(ServerLevel level) {
        targets.entrySet()
                .removeIf(
                        e ->
                                e.getValue().level() == level
                                        && !validTarget(e.getKey(), e.getValue()));
        for (var player : level.players())
            if (Space.in(player) ? observers.get(player) != level : observers.containsKey(player))
                sync(player);
        // Only those previously made free need their gravity restored after leaving the region.
        weightless
                .entrySet()
                .removeIf(
                        e -> {
                            var living = e.getKey();
                            if (e.getValue() != level) return false;
                            if (!living.isRemoved() && living.level() == level && Space.in(living))
                                return false;
                            weight(
                                    living.getAttribute(
                                            net.minecraft.world.entity.ai.attributes.Attributes
                                                    .GRAVITY),
                                    false);
                            return true;
                        });
        var cabins = cabins(level);
        for (var entity : regionEntities(level))
            if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                boolean free = Space.in(entity) && !weighted(entity, cabins);
                weight(
                        living.getAttribute(
                                net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY),
                        free);
                if (free) weightless.put(living, level);
                else weightless.remove(living);
            }
    }

    /** Membership ignores height, including entities above or below the terrain storage band. */
    private static java.util.List<net.minecraft.world.entity.Entity> regionEntities(
            ServerLevel level) {
        return dev.starfold.ground.GroundSeams.entities(
                level,
                new net.minecraft.world.phys.AABB(
                        Space.X - Space.REACH,
                        Double.NEGATIVE_INFINITY,
                        Space.Z - Space.REACH,
                        Space.X + Space.REACH,
                        Double.POSITIVE_INFINITY,
                        Space.Z + Space.REACH));
    }

    /** Visit each cabin's local entities once, rather than visiting every hull for each space mob. */
    private static java.util.Set<net.minecraft.world.entity.Entity> cabins(ServerLevel level) {
        var found = new java.util.HashSet<net.minecraft.world.entity.Entity>();
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container != null)
            for (var candidate : container.getAllSubLevels())
                if (candidate instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel body
                        && !body.isRemoved()) {
                    var bounds = SableFrames.cabinBounds(body);
                    if (bounds == null
                            || bounds.maxX < Space.X - Space.REACH
                            || bounds.minX > Space.X + Space.REACH
                            || bounds.maxZ < Space.Z - Space.REACH
                            || bounds.minZ > Space.Z + Space.REACH) continue;
                    for (var entity : dev.starfold.ground.GroundSeams.entities(level, bounds))
                        if (Space.in(entity) && SableFrames.inside(body, entity)) found.add(entity);
                }
        return found;
    }

    /** Weight uses contact NOW or cabin feet, never the passage/handover grace. */
    private static boolean weighted(
            net.minecraft.world.entity.Entity entity,
            java.util.Set<net.minecraft.world.entity.Entity> cabins) {
        return dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(entity) != null
                || cabins.contains(entity);
    }

    /** Multiplying the final attribute by zero leaves its base and every other mod's modifiers intact. */
    public static void weight(
            net.minecraft.world.entity.ai.attributes.AttributeInstance gravity, boolean free) {
        if (gravity == null) return;
        if (free) {
            if (!gravity.hasModifier(WEIGHTLESS))
                gravity.addTransientModifier(
                        new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                                WEIGHTLESS,
                                -1,
                                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation
                                        .ADD_MULTIPLIED_TOTAL));
        } else gravity.removeModifier(WEIGHTLESS);
    }

    public SpaceServer(Space.Frame frame) {
        this.frame = frame;
    }

    static SpaceServer fresh(boolean exists) {
        if (exists)
            throw new IllegalStateException(
                    "Existing space frame failed to load; refusing overwrite");
        var value = new SpaceServer(Space.Frame.IDENTITY);
        value.setDirty();
        return value;
    }

    public static SpaceServer load(CompoundTag tag, HolderLookup.Provider lookup) {
        for (String key :
                new String[] {
                    "qx", "qy", "qz", "qw", "x", "y", "z", "ox", "oy", "oz", "wx", "wy", "wz"
                })
            if (!tag.contains(key, 6))
                throw new IllegalArgumentException("Space frame field: " + key);
        if (!tag.contains("planet", 3)
                || !tag.contains("epoch", 4)
                || !tag.contains("originEpoch", 4)
                || tag.getLong("epoch") < 0)
            throw new IllegalArgumentException("Space reference fields");
        var value =
                new SpaceServer(
                        new Space.Frame(
                                new Quaterniond(
                                        tag.getDouble("qx"),
                                        tag.getDouble("qy"),
                                        tag.getDouble("qz"),
                                        tag.getDouble("qw")),
                                new Point(
                                        tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")),
                                tag.getInt("planet"),
                                new Point(
                                        tag.getDouble("ox"),
                                        tag.getDouble("oy"),
                                        tag.getDouble("oz")),
                                new Point(
                                        tag.getDouble("wx"),
                                        tag.getDouble("wy"),
                                        tag.getDouble("wz")),
                                tag.getLong("originEpoch")));
        value.epoch = tag.getLong("epoch");
        if (tag.getBoolean("arrivalLatched")) {
            value.arrivalState =
                    new ArrivalState(
                            ArrivalState.Phase.ARRIVAL,
                            20,
                            0,
                            0,
                            tag.getDouble("arrivalHeight"),
                            tag.getInt("arrivalPlanet"),
                            tag.getString("arrivalLead"));
            value.arriving = true;
        }
        if (tag.contains("departurePlanet", 3) && tag.contains("departureLead", 8)) {
            var s = value.arrivalState;
            value.arrivalState =
                    new ArrivalState(
                            s.phase(),
                            s.ticks(),
                            s.cooldown(),
                            s.leaving(),
                            s.entryHeight(),
                            s.planet(),
                            s.lead(),
                            new ArrivalState.Departure(
                                    tag.getInt("departurePlanet"), tag.getString("departureLead")));
        }
        return value;
    }

    private static SpaceServer get(ServerLevel level) {
        var storage = level.getDataStorage();
        return storage.computeIfAbsent(
                new Factory<>(
                        () ->
                                fresh(
                                        ((dev.starfold.mixin.SavedDataFileAccess) storage)
                                                .starfold$spaceDataFile(FILE)
                                                .exists()),
                        SpaceServer::load,
                        null),
                FILE);
    }

    public static Space.Frame frame(ServerLevel level) {
        return get(level).frame;
    }

    /** A latecomer uses the occupied region's frame; changing it would move every body already there. */
    public static Space.Frame entering(
            ServerLevel level,
            Space.Motion universeMotion,
            Quaterniond levelOrientation,
            GroundEphemeris.Model model,
            int planet,
            long tick) {
        var value = get(level);
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container != null)
            for (var body : container.getAllSubLevels()) {
                var at = body.logicalPose().position();
                if (!body.isRemoved() && Space.holds(at.x, at.z)) return value.frame;
            }
        value.inbound = false;
        value.arriving = false;
        value.arrivalState = ArrivalState.empty();
        value.inboundBody = null;
        value.inboundPlanet = -1;
        value.frame = Space.Frame.keeping(universeMotion, levelOrientation, model, planet, tick);
        value.epoch = tick;
        value.setDirty();
        for (var player : level.players()) if (Space.in(player)) send(player);
        return value.frame;
    }

    /** Called only after an actual chart-to-region crossing, never on reload or target changes. */
    public static void departed(ServerLevel level, int planet, String id) {
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (!(container instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer server))
            return;
        var lead = lead(level, server);
        if (lead == null || !lead.getUniqueId().toString().equals(id)) return;
        var value = get(level);
        value.arrivalState = ArrivalState.departed(planet, id);
        value.setDirty();
    }

    /** A successful handover ends this body's flight even if other region bodies remain. */
    public static void enteredChart(ServerLevel level, String id) {
        var value = get(level);
        var s = value.arrivalState;
        if (s.lead().equals(id) || s.departure() != null && s.departure().lead().equals(id)) {
            value.arrivalState = s.step(s.planet(), id, 0, 0, false, false, false, true, true);
            value.arriving = false;
            value.inbound = false;
            value.inboundBody = null;
            value.inboundPlanet = -1;
            value.setDirty();
        }
    }

    /** Sample nearest/arrival intent; filter the piloted fold and braking push; fold/relabel, drag,
     * switch reference, turn, skip arrival assist, cruise, radial/tangential govern, prefetch, anchor. */
    public static void step(
            ServerLevel level, dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container) {
        var bodies = spaceBodies(container);
        if (bodies.isEmpty()) {
            clearEmptyRegion(level, container);
            return;
        }
        var lead = lead(level, container);
        var value = get(level);
        var clock = GroundClock.get(level).clock;
        for (var body : bodies) ShipRecords.binding(level, body);
        advanceLead(level, container, bodies, lead, value, clock);
        settleFollowers(level, container, bodies, lead, value);
    }

    // Arrival, attitude, cruise and probes share more than six locals; keep this allocation-free
    // stretch inline.
    private static void advanceLead(
            ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            java.util.List<dev.ryanhcode.sable.sublevel.ServerSubLevel> bodies,
            dev.ryanhcode.sable.sublevel.ServerSubLevel lead,
            SpaceServer value,
            dev.starfold.sky.GroundEphemerisSave clock) {
        var model = clock.model;
        long tick = clock.tick();
        var at = lead.logicalPose().position();
        var point = new Point(at.x, at.y, at.z);
        var before = value.frame;
        var pipeline = container.physicsSystem().getPipeline();
        var velocity = pipeline.getLinearVelocity(lead, new org.joml.Vector3d()).div(20);
        var v = new Point(velocity.x, velocity.y, velocity.z);
        boolean piloted =
                Thrusters.steadied(lead, level.getGameTime())
                        && level.players().stream().anyMatch(player -> helm(player, lead));
        var prior = value.frame.drift();
        var push = value.frame.universeVector(Thrusters.push(lead, level.getGameTime()));
        var entry = value.frame.rebased(tick);
        var position = entry.universe(point, model, tick);
        int destination = Space.nearest(model, position, tick);
        var towardEntry = model.at(destination, tick).center().sub(position);
        var entryShape = PlanetShape.of(destination);
        var impulse = entry.universeVector(v);
        double entryHeight = Space.height(model, destination, entryShape, position, tick),
                entryClosing =
                        SpaceNavigation.closing(
                                towardEntry,
                                entry.universeVelocity(GroundEphemeris.ZERO, model, tick)
                                        .add(impulse.scale(piloted ? VACUUM : 1)),
                                model.at(destination, tick).velocity());
        var entryRelative =
                entry.universeVelocity(GroundEphemeris.ZERO, model, tick)
                        .add(impulse.scale(piloted ? VACUUM : 1))
                        .sub(model.at(destination, tick).velocity());
        value.inbound =
                inbound(
                        model.body(destination),
                        value.inbound
                                && value.inboundBody == lead
                                && value.inboundPlanet == destination,
                        entryClosing,
                        missHeight(towardEntry.scale(-1), entryRelative, entryShape.radius()));
        value.inboundBody = lead;
        value.inboundPlanet = destination;
        var oldArrival = value.arrivalState;
        int bits = HelmServer.bits(lead);
        boolean abort =
                (bits & (HelmCommand.UP | HelmCommand.SPRINT))
                        == (HelmCommand.UP | HelmCommand.SPRINT);
        var radialUp =
                entry.levelVector(towardEntry.scale(-1 / Math.max(1e-12, towardEntry.length())));
        double remaining = Math.toDegrees(Math.acos(Math.clamp(radialUp.y(), -1, 1)));
        int lockedPlanet = lockedPlanet(level, lead);
        value.arrivalState =
                oldArrival.step(
                        destination,
                        lead.getUniqueId().toString(),
                        entryHeight,
                        entryClosing,
                        piloted
                                && align(
                                        model.body(destination),
                                        entryHeight,
                                        entryClosing,
                                        value.inbound),
                        piloted && entryHeight < model.body(destination).hullIn() + 300,
                        abort,
                        false,
                        remaining < .1,
                        lockedPlanet);
        boolean arriving = value.arrivalState.latched();
        boolean wasArriving = value.arriving;
        value.arriving = arriving;
        double blend = value.arrivalState.blend();
        push = blendPush(push, towardEntry, blend);
        if (v.length() > 1e-9) {
            absorbLeadImpulse(value, tick, v, towardEntry, blend, piloted);
            for (var body : bodies)
                pipeline.addLinearAndAngularVelocity(
                        body,
                        new org.joml.Vector3d(-v.x() * 20, -v.y() * 20, -v.z() * 20),
                        new org.joml.Vector3d());
            subtractEntityImpulse(level, bodies, v);
        }
        if (!piloted) dragFrame(value, level, container, bodies, lead, tick);
        else value.clearHoldTurn();
        value.setDirty();
        var universe = value.frame.universe(point, model, tick);
        int nearest = Space.nearest(model, universe, tick);
        if (nearer(model, universe, value.frame.planet(), nearest, tick)) {
            var old = value.frame;
            value.frame = old.toward(nearest, model, tick);
            prior = prior.add(value.frame.drift().sub(old.drift()));
        }
        if (Thrusters.steadied(lead, level.getGameTime())) {
            float pitch = 0, roll = 0;
            double mouseYaw = 0, mousePitch = 0;
            for (var player : level.players()) {
                var wish = wishes.get(player);
                if (wish == null) continue;
                if (wish.body() != lead
                        || !fresh(wish.tick(), level.getGameTime())
                        || !helm(player, lead)) {
                    wishes.remove(player);
                    continue;
                }
                pitch = wish.pitch();
                roll = wish.roll();
                mouseYaw = wish.mouseYaw();
                mousePitch = wish.mousePitch();
                wishes.put(player, new Wish(lead, pitch, roll, 0, 0, wish.tick()));
                break;
            }
            var shape = PlanetShape.of(nearest);
            double height = Space.height(model, nearest, shape, universe, tick),
                    closing =
                            SpaceNavigation.closing(
                                    model.at(nearest, tick).center().sub(universe),
                                    value.frame.universeVelocity(GroundEphemeris.ZERO, model, tick),
                                    model.at(nearest, tick).velocity());
            if (piloted && !arriving) roll += HelmCommand.roll(HelmServer.bits(lead));
            var up = lead.logicalPose().orientation().transform(new org.joml.Vector3d(0, 1, 0));
            var turn =
                    arriving
                            ? Space.Frame.turn(
                                    value.frame,
                                    model,
                                    tick,
                                    point,
                                    Math.toRadians(ALIGN_TURN)
                                            / 20
                                            * ArrivalState.turnScale(blend, remaining))
                            : attitude(
                                    value.frame,
                                    model,
                                    tick,
                                    point,
                                    false,
                                    true,
                                    HelmServer.forward(lead),
                                    new Point(up.x, up.y, up.z),
                                    pitch,
                                    roll,
                                    mouseYaw,
                                    mousePitch);
            if (Boolean.getBoolean("starfold.passbyProbe"))
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE ARRIVE_PHASE game {} phase {} blend {} notice {} remaining {} turn {} entryHeight {} entryClosing {} start {} planet {} departure {}",
                                level.getGameTime(),
                                value.arrivalState.phase(),
                                blend,
                                value.arrivalState.seconds(),
                                remaining,
                                Math.toDegrees(turn.angle()),
                                entryHeight,
                                entryClosing,
                                align(
                                        model.body(destination),
                                        entryHeight,
                                        entryClosing,
                                        value.inbound),
                                destination,
                                value.arrivalState.departure());
            if (turn.x != 0 || turn.y != 0 || turn.z != 0) {
                var old = value.frame;
                value.frame = old.turned(turn, point);
                value.setDirty();
                value.relabelTurn(level, container, bodies, lead, old);
            }
        }
        Point targetVelocity = null;
        if (Thrusters.steadied(lead, level.getGameTime()))
            for (var player : level.players())
                if (helm(player, lead)) {
                    targetVelocity = targetVelocity(player, model, value.frame.planet(), tick);
                    var shape = PlanetShape.of(nearest);
                    double height = Space.height(model, nearest, shape, universe, tick),
                            closing =
                                    SpaceNavigation.closing(
                                            model.at(nearest, tick).center().sub(universe),
                                            value.frame.universeVelocity(
                                                    GroundEphemeris.ZERO, model, tick),
                                            model.at(nearest, tick).velocity());
                    if (!arriving) {
                        var f = value.frame.rebased(tick);
                        var forward = HelmServer.forward(lead);
                        var nose = f.universeVector(forward);
                        value.frame =
                                new Space.Frame(
                                        f.rotation(),
                                        f.shift(),
                                        f.planet(),
                                        f.offset(),
                                        assist(
                                                f.drift(),
                                                nose,
                                                Thrusters.forward(
                                                        lead, level.getGameTime(), forward),
                                                targetVelocity),
                                        tick);
                        value.setDirty();
                    }
                    break;
                }
        if (piloted) {
            var f = value.frame.rebased(tick);
            var drift =
                    cruise(
                            prior,
                            f.drift(),
                            push,
                            Thrusters.boosted(lead, level.getGameTime()),
                            targetVelocity,
                            SpaceSpeedServer.caps(lead));
            var shape = PlanetShape.of(nearest);
            double height = Space.height(model, nearest, shape, universe, tick);
            var toward = model.at(nearest, tick).center().sub(universe);
            var relative =
                    model.at(f.planet(), tick)
                            .velocity()
                            .add(drift)
                            .sub(model.at(nearest, tick).velocity());
            value.inbound =
                    inbound(
                            model.body(nearest),
                            value.inbound,
                            SpaceNavigation.closing(toward, relative, GroundEphemeris.ZERO),
                            missHeight(toward.scale(-1), relative, shape.radius()));
            var ungoverned = drift;
            double miss = missHeight(toward.scale(-1), relative, shape.radius());
            var limited = govern(model.body(nearest), drift, toward, relative, height, true);
            drift = drift.add(limited.sub(drift).scale(blend));
            if (Boolean.getBoolean("starfold.passbyProbe"))
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE PASSBY game {} nearest {} ref {} height {} miss {} inbound {} arriving {} governed {} before {} speed {} delta {} closing {}",
                                level.getGameTime(),
                                nearest,
                                f.planet(),
                                height,
                                miss,
                                value.inbound,
                                arriving,
                                blend > 0,
                                relative.length() * 20,
                                relative.add(drift.sub(ungoverned)).length() * 20,
                                drift.sub(ungoverned).length() * 20,
                                SpaceNavigation.closing(toward, relative, GroundEphemeris.ZERO));
            value.frame =
                    new Space.Frame(f.rotation(), f.shift(), f.planet(), f.offset(), drift, tick);
            value.setDirty();
        }
        if (ArrivalProbe.ENABLED && level.getGameTime() % 20 == 0) {
            var f = value.frame;
            var toward = model.at(nearest, tick).center().sub(universe);
            double h = Space.height(model, nearest, PlanetShape.of(nearest), universe, tick),
                    closing =
                            SpaceNavigation.closing(
                                    toward,
                                    f.universeVelocity(GroundEphemeris.ZERO, model, tick),
                                    model.at(nearest, tick).velocity());
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE ARRIVAL_MODE game {} nearest {} piloted {} arriving {} height {} speed {} closing {} impulse {} kept {} push {} assistSkipped {}",
                            level.getGameTime(),
                            nearest,
                            piloted,
                            arriving,
                            h,
                            f.universeVelocity(GroundEphemeris.ZERO, model, tick)
                                            .sub(model.at(nearest, tick).velocity())
                                            .length()
                                    * 20,
                            closing,
                            impulse,
                            arrivalPush(impulse, towardEntry, arriving),
                            push,
                            arriving);
        }
        SpaceApproach.update(level, container, lead);
        var delta = anchor(point);
        if (delta == null) {
            if (!oldArrival.equals(value.arrivalState)
                    || wasArriving != value.arriving
                    || !before.rebased(tick).equals(value.frame)) {
                value.epoch = tick;
                for (var player : level.players()) if (Space.in(player)) send(player, true);
            }
            return;
        }
        shiftRegion(level, container, bodies, value, delta, tick);
    }

    private static int lockedPlanet(
            ServerLevel level, dev.ryanhcode.sable.sublevel.ServerSubLevel lead) {
        int lockedPlanet = -1;
        for (var player : level.players())
            if (helm(player, lead)) {
                var target = targets.get(player);
                if (target != null && validTarget(player, target)) lockedPlanet = target.planet();
                break;
            }
        return lockedPlanet;
    }

    private static void subtractEntityImpulse(
            ServerLevel level,
            java.util.List<dev.ryanhcode.sable.sublevel.ServerSubLevel> bodies,
            Point v) {
        for (var entity : regionEntities(level))
            if (Space.in(entity)) {
                var body = SableFrames.aboard(entity);
                if (body != null && bodies.contains(body) && SableFrames.tracked(entity, body))
                    continue;
                entity.setDeltaMovement(entity.getDeltaMovement().subtract(v.x(), v.y(), v.z()));
                if (entity instanceof net.minecraft.server.level.ServerPlayer player)
                    player.hurtMarked = true;
            }
    }

    private static double dragFactor(ServerLevel level) {
        return 1
                / (1
                        + dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData
                                        .getUniversalDrag(level)
                                / 20);
    }

    public static Point carriedSpin(ServerLevel level, java.util.UUID hull) {
        var value = get(level);
        return hull.equals(value.turningLead) ? value.holdTurn : GroundEphemeris.ZERO;
    }

    private void clearHoldTurn() {
        holdTurn = GroundEphemeris.ZERO;
        turningLead = null;
    }

    private static void dragFrame(
            SpaceServer value,
            ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            java.util.List<dev.ryanhcode.sable.sublevel.ServerSubLevel> bodies,
            dev.ryanhcode.sable.sublevel.ServerSubLevel lead,
            long tick) {
        var held = ShipRecords.hold(level, lead);
        if (held == null) {
            coastLead(value, level, container, bodies, lead, tick);
            return;
        }
        var model = GroundClock.get(level).clock.model;
        var at = lead.logicalPose().position();
        var point = new Point(at.x, at.y, at.z);
        var anchor = lead.logicalPose().rotationPoint();
        var plot = new Point(anchor.x(), anchor.y(), anchor.z());
        var target = ShipParking.target(model, held, plot, tick);
        var next = ShipParking.target(model, held, plot, tick + 1);
        if (!lead.getUniqueId().equals(value.turningLead)) value.clearHoldTurn();
        value.turningLead = lead.getUniqueId();
        var pipeline = container.physicsSystem().getPipeline();
        var impulse = pipeline.getAngularVelocity(lead, new org.joml.Vector3d()).div(20);
        var spin =
                value.holdTurn.add(
                        value.frame.universeVector(new Point(impulse.x, impulse.y, impulse.z)));
        var motion =
                new Space.Motion(
                        value.frame.universe(point, model, tick),
                        value.frame.universeOrientation(
                                new Quaterniond(lead.logicalPose().orientation())),
                        value.frame.universeVelocity(GroundEphemeris.ZERO, model, tick),
                        new org.joml.Vector3d(spin.x(), spin.y(), spin.z()));
        var settled = ShipParking.settle(motion, target, next);
        value.frame = ShipParking.settleFrame(value.frame, model, settled.velocity(), tick);
        value.holdTurn = spinPoint(settled);
        pipeline.addLinearAndAngularVelocity(lead, new org.joml.Vector3d(), impulse.mul(-20));
        var old = value.frame;
        value.frame = ShipParking.turnFrame(old, value.holdTurn, point);
        if (old != value.frame) value.relabelTurn(level, container, bodies, lead, old);
    }

    private static void coastLead(
            SpaceServer value,
            ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            java.util.List<dev.ryanhcode.sable.sublevel.ServerSubLevel> bodies,
            dev.ryanhcode.sable.sublevel.ServerSubLevel lead,
            long tick) {
        // A helm without powered stabilization still uses the ordinary region drag, not body hold.
        if (ShipRecords.piloted(level, lead)) {
            value.clearHoldTurn();
            value.frame = value.frame.dragged(dragFactor(level), tick);
            return;
        }
        var model = GroundClock.get(level).clock.model;
        var at = lead.logicalPose().position();
        var point = new Point(at.x, at.y, at.z);
        var universe = value.frame.universe(point, model, tick);
        int body = ShipParking.nearestBody(model, universe, tick);
        double factor = dragFactor(level);
        var target = ShipParking.coastTarget(model, body, universe, tick);
        value.frame = ShipParking.coastFrame(value.frame, model, body, point, factor, tick);
        if (!lead.getUniqueId().equals(value.turningLead)) value.clearHoldTurn();
        value.turningLead = lead.getUniqueId();
        var angular = spinPoint(target);
        if (angular.equals(GroundEphemeris.ZERO)) {
            value.clearHoldTurn();
            return;
        }
        var pipeline = container.physicsSystem().getPipeline();
        var impulse = pipeline.getAngularVelocity(lead, new org.joml.Vector3d()).div(20);
        value.holdTurn =
                ShipParking.decay(
                        value.holdTurn.add(
                                value.frame.universeVector(
                                        new Point(impulse.x, impulse.y, impulse.z))),
                        angular,
                        spinPoint(
                                ShipParking.nextCoastTarget(
                                        model,
                                        body,
                                        value.frame,
                                        point,
                                        GroundEphemeris.ZERO,
                                        tick)),
                        factor);
        pipeline.addLinearAndAngularVelocity(lead, new org.joml.Vector3d(), impulse.mul(-20));
        var old = value.frame;
        value.frame = ShipParking.turnFrame(old, value.holdTurn, point);
        if (old != value.frame) value.relabelTurn(level, container, bodies, lead, old);
    }

    private static Point spinPoint(Space.Motion motion) {
        var spin = motion.spin();
        return new Point(spin.x, spin.y, spin.z);
    }

    /** Only coordinate relabelling: no parked pose is imposed on a hull. */
    private void relabelTurn(
            ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            java.util.List<dev.ryanhcode.sable.sublevel.ServerSubLevel> bodies,
            dev.ryanhcode.sable.sublevel.ServerSubLevel lead,
            Space.Frame old) {
        var clock = GroundClock.get(level).clock;
        for (var body : bodies)
            if (body != lead) {
                var p = body.logicalPose().position();
                var motion = SableFrames.motion(container, body, new Point(p.x, p.y, p.z));
                SableFrames.move(
                        level,
                        container,
                        body,
                        frame.level(
                                old.universe(motion, clock.model, clock.tick()),
                                clock.model,
                                clock.tick()),
                        true);
            }
    }

    private static void settleFollowers(
            ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            java.util.List<dev.ryanhcode.sable.sublevel.ServerSubLevel> bodies,
            dev.ryanhcode.sable.sublevel.ServerSubLevel lead,
            SpaceServer value) {
        var clock = GroundClock.get(level).clock;
        var pipeline = container.physicsSystem().getPipeline();
        for (var body : bodies) {
            if (body == lead || ShipRecords.piloted(level, body)) continue;
            var held = ShipRecords.hold(level, body);
            if (held == null) {
                coastFollower(level, container, body, value.frame);
                continue;
            }
            var at = body.logicalPose().position();
            var point = new Point(at.x, at.y, at.z);
            var anchor = body.logicalPose().rotationPoint();
            var plot = new Point(anchor.x(), anchor.y(), anchor.z());
            var target =
                    value.frame.level(
                            ShipParking.target(clock.model, held, plot, clock.tick()),
                            clock.model,
                            clock.tick());
            var next =
                    value.frame.level(
                            ShipParking.target(clock.model, held, plot, clock.tick() + 1),
                            clock.model,
                            clock.tick() + 1);
            var motion = SableFrames.motion(container, body, point);
            var settled = ShipParking.settle(motion, target, next);
            var linear = settled.velocity().sub(motion.velocity()).scale(20);
            var angular = settled.spin().sub(motion.spin()).mul(20);
            pipeline.addLinearAndAngularVelocity(
                    body, new org.joml.Vector3d(linear.x(), linear.y(), linear.z()), angular);
        }
    }

    private static void coastFollower(
            ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body,
            Space.Frame frame) {
        var clock = GroundClock.get(level).clock;
        var at = body.logicalPose().position();
        var point = new Point(at.x, at.y, at.z);
        var universe = frame.universe(point, clock.model, clock.tick());
        int nearest = ShipParking.nearestBody(clock.model, universe, clock.tick());
        var target =
                frame.level(
                        ShipParking.coastTarget(clock.model, nearest, universe, clock.tick()),
                        clock.model,
                        clock.tick());
        double factor = dragFactor(level);
        var motion = SableFrames.motion(container, body, point);
        var next = target;
        if (target.spin().lengthSquared() != 0) {
            var velocity = ShipParking.decay(motion.velocity(), target.velocity(), factor);
            next =
                    frame.level(
                            ShipParking.nextCoastTarget(
                                    clock.model, nearest, frame, point, velocity, clock.tick()),
                            clock.model,
                            clock.tick() + 1);
        }
        var settled = ShipParking.coast(motion, target, next, factor);
        var linear = settled.velocity().sub(motion.velocity()).scale(20);
        var angular = settled.spin().sub(motion.spin()).mul(20);
        container
                .physicsSystem()
                .getPipeline()
                .addLinearAndAngularVelocity(
                        body, new org.joml.Vector3d(linear.x(), linear.y(), linear.z()), angular);
    }

    private static void shiftRegion(
            ServerLevel level,
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            java.util.List<dev.ryanhcode.sable.sublevel.ServerSubLevel> bodies,
            SpaceServer value,
            Point delta,
            long tick) {
        value.frame = value.frame.shifted(delta);
        value.epoch = tick;
        value.setDirty();
        for (var body : bodies) {
            var p = body.logicalPose().position();
            var motion = SableFrames.motion(container, body, new Point(p.x, p.y, p.z));
            SableFrames.move(
                    level,
                    container,
                    body,
                    new Space.Motion(
                            motion.position().add(delta),
                            motion.orientation(),
                            motion.velocity(),
                            motion.spin()),
                    true);
        }
        for (var player : level.players()) if (Space.in(player)) send(player);
    }

    private static java.util.ArrayList<dev.ryanhcode.sable.sublevel.ServerSubLevel> spaceBodies(
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container) {
        var bodies = new java.util.ArrayList<dev.ryanhcode.sable.sublevel.ServerSubLevel>();
        for (var body : container.getAllSubLevels()) {
            var at = body.logicalPose().position();
            if (!body.isRemoved() && Space.holds(at.x, at.z)) bodies.add(body);
        }
        return bodies;
    }

    private static void clearEmptyRegion(
            ServerLevel level, dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container) {
        var value = get(level);
        value.inbound = false;
        value.arriving = false;
        value.inboundBody = null;
        value.inboundPlanet = -1;
        value.arrivalState = ArrivalState.empty();
        value.clearHoldTurn();
        value.setDirty();
        SpaceApproach.update(level, container, null);
    }

    private static void absorbLeadImpulse(
            SpaceServer value,
            long tick,
            Point v,
            Point towardEntry,
            double blend,
            boolean piloted) {
        var f = value.frame.rebased(tick);
        value.frame =
                new Space.Frame(
                        f.rotation(),
                        f.shift(),
                        f.planet(),
                        f.offset(),
                        gain(
                                f.drift(),
                                blendPush(f.universeVector(v), towardEntry, blend),
                                piloted,
                                0),
                        tick);
    }

    /** Use the same occupied-body order for accepting a wish and consuming it. */
    private static dev.ryanhcode.sable.sublevel.ServerSubLevel lead(
            ServerLevel level, dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container) {
        dev.ryanhcode.sable.sublevel.ServerSubLevel first = null;
        for (var player : level.players()) {
            var pilot = SableFrames.aboard(player);
            if (pilot != null && helm(player, pilot)) return pilot;
        }
        for (var body : container.getAllSubLevels()) {
            var at = body.logicalPose().position();
            if (body.isRemoved() || !Space.holds(at.x, at.z)) continue;
            if (first == null) first = body;
            for (var player : level.players()) if (SableFrames.aboard(player) == body) return body;
        }
        return first;
    }

    /** A region observer still needs the lead's labels while other hulls may sleep. */
    public static boolean retainLead(
            ServerLevel level, dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        return container != null
                && level.players().stream().anyMatch(Space::in)
                && lead(level, container) == body;
    }

    /** The server's controller session owns pilot identity and forward, not player yaw. */
    private static boolean helm(
            net.minecraft.server.level.ServerPlayer player,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        return Space.in(player) && atHelm(player, body);
    }

    public static boolean atHelm(
            net.minecraft.server.level.ServerPlayer player,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        return HelmServer.pilot(player, body);
    }

    public static void releaseHelm(net.minecraft.server.level.ServerPlayer player) {
        wishes.remove(player);
        targets.remove(player);
    }

    private static void wish(
            net.minecraft.server.level.ServerPlayer player, SpacePackets.Attitude packet) {
        if (!Float.isFinite(packet.pitch())
                || !Float.isFinite(packet.roll())
                || !Float.isFinite(packet.mouseYaw())
                || !Float.isFinite(packet.mousePitch())
                || !Space.in(player)) {
            wishes.remove(player);
            return;
        }
        var container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                        player.serverLevel());
        if (!(container
                instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer server)) {
            wishes.remove(player);
            return;
        }
        var lead = lead(player.serverLevel(), server);
        if (lead == null || !helm(player, lead)) {
            wishes.remove(player);
            return;
        }
        var old = wishes.get(player);
        boolean pending =
                old != null
                        && old.body() == lead
                        && fresh(old.tick(), player.level().getGameTime());
        double my = (pending ? old.mouseYaw() : 0) + packet.mouseYaw(),
                mp = (pending ? old.mousePitch() : 0) + packet.mousePitch();
        wishes.put(
                player,
                new Wish(
                        lead,
                        Math.clamp(packet.pitch(), -1, 1),
                        Math.clamp(packet.roll(), -1, 1),
                        my,
                        mp,
                        player.level().getGameTime()));
    }

    /** Gyros keep Level yaw still only while those turning thrusters are used as frame roll. */
    public static boolean rollControl(
            net.minecraft.world.level.Level world,
            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        if (!(world instanceof ServerLevel level) || !PlanetLevels.is(level)) return false;
        var at = body.logicalPose().position();
        if (!Space.holds(at.x, at.z) || !Thrusters.steadied(body, level.getGameTime()))
            return false;
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (!(container instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer server)
                || lead(level, server) != body
                || level.players().stream().noneMatch(player -> helm(player, body))) return false;
        var clock = GroundClock.get(level).clock;
        var model = clock.model;
        long tick = clock.tick();
        var f = frame(level);
        var universe = f.universe(new Point(at.x, at.y, at.z), model, tick);
        int nearest = Space.nearest(model, universe, tick);
        var shape = PlanetShape.of(nearest);
        return !get(level).arriving;
    }

    private static boolean validTarget(
            net.minecraft.server.level.ServerPlayer player, Target target) {
        if (player.serverLevel() != target.level()
                || !Space.in(player)
                || !helm(player, target.body())
                || Thrusters.scopeTier(target.body()) < 2
                || !knownTarget(
                        target.planet(), GroundClock.get(target.level()).clock.model.count()))
            return false;
        var container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(target.level());
        return container instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer server
                && lead(target.level(), server) == target.body();
    }

    public static int markerPlanet(net.minecraft.server.level.ServerPlayer player) {
        var target = targets.get(player);
        if (target != null && validTarget(player, target)) return target.planet();
        var value = get(player.serverLevel());
        return value.arriving ? value.inboundPlanet : -1;
    }

    static boolean knownTarget(int planet, int count) {
        return planet >= 0 && planet < count;
    }

    private static void target(net.minecraft.server.level.ServerPlayer player, int planet) {
        targets.remove(player);
        if (!Space.in(player)
                || !knownTarget(planet, GroundClock.get(player.serverLevel()).clock.model.count()))
            return;
        var container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                        player.serverLevel());
        if (!(container instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer server))
            return;
        var body = lead(player.serverLevel(), server);
        if (body != null && helm(player, body) && Thrusters.scopeTier(body) >= 2) {
            targets.put(player, new Target(player.serverLevel(), body, planet));
            var value = get(player.serverLevel());
            value.arrivalState = value.arrivalState.lock(planet, body.getUniqueId().toString());
            value.setDirty();
        }
    }

    private static Point targetVelocity(
            net.minecraft.server.level.ServerPlayer player,
            GroundEphemeris.Model model,
            int reference,
            long tick) {
        var target = targets.get(player);
        if (target == null) return null;
        if (!validTarget(player, target)) {
            targets.remove(player);
            return null;
        }
        return model.at(target.planet(), tick).velocity().sub(model.at(reference, tick).velocity());
    }

    static boolean fresh(long sent, long now) {
        return now >= sent && now - sent <= WISH_TICKS;
    }

    /** Closing is radial blocks/second; equality leaves attitude free. The floor also covers retreat.
     * 12v lead leaves about 7v after the 4s notice + 1s blend, before the 6(v-150)
     * governor boundary. Arrival directly projects velocity onto its envelope (not BRAKE-limited),
     * so the existing linear lead remains sufficient at 2400; no quadratic lead is needed. */
    static boolean align(GroundEphemeris.Body body, double height, double closing) {
        return align(body, height, closing, true);
    }

    static boolean align(
            GroundEphemeris.Body body, double height, double closing, boolean inbound) {
        return height < body.hullIn() + 300
                || inbound && closing > 0 && height - body.hullIn() < closing * ALIGN_TIME;
    }

    /** Straight-line closest approach in the nearest planet's velocity frame; recession has no future miss. */
    static double missHeight(Point position, Point relative, double radius) {
        double speed2 = relative.dot(relative), dot = position.dot(relative);
        return speed2 > 0 && dot < 0
                ? position.add(relative.scale(-dot / speed2)).length() - radius
                : Double.POSITIVE_INFINITY;
    }

    static boolean inbound(
            GroundEphemeris.Body body, boolean previous, double closing, double miss) {
        return closing > 0
                && (previous ? miss <= body.hullIn() + MISS_OUT : miss < body.hullIn() + MISS_IN);
    }

    static boolean governed(GroundEphemeris.Body body, double height, boolean inbound) {
        return inbound || height < body.hullIn() + 300;
    }

    static Point govern(
            GroundEphemeris.Body body,
            Point drift,
            Point toward,
            Point relative,
            double height,
            boolean inbound) {
        if (!governed(body, height, inbound)) return drift;
        var arrived = arrival(body, drift, toward, relative, height);
        return near(body, arrived, toward, relative.add(arrived.sub(drift)), height);
    }

    /** Inverse ship rotation: nose up brings old +Y ahead; roll right brings old right upward. */
    static Quaterniond attitude(
            Space.Frame frame,
            GroundEphemeris.Model model,
            double tick,
            Point pivot,
            double height,
            double closing,
            boolean steadied,
            double yaw,
            double pitch,
            double roll) {
        return attitude(
                frame, model, tick, pivot, height, closing, steadied, yaw, pitch, roll, 0, 0);
    }

    static Quaterniond attitude(
            Space.Frame frame,
            GroundEphemeris.Model model,
            double tick,
            Point pivot,
            double height,
            double closing,
            boolean steadied,
            double yaw,
            double pitch,
            double roll,
            double mouseYaw,
            double mousePitch) {
        return attitude(
                frame,
                model,
                tick,
                pivot,
                align(
                        model.body(Space.nearest(model, frame.universe(pivot, model, tick), tick)),
                        height,
                        closing),
                steadied,
                yaw,
                pitch,
                roll,
                mouseYaw,
                mousePitch);
    }

    public static Quaterniond attitude(
            Space.Frame frame,
            GroundEphemeris.Model model,
            double tick,
            Point pivot,
            boolean arriving,
            boolean steadied,
            double yaw,
            double pitch,
            double roll,
            double mouseYaw,
            double mousePitch) {
        if (!steadied) return new Quaterniond();
        if (arriving)
            return Space.Frame.turn(frame, model, tick, pivot, Math.toRadians(ALIGN_TURN) / 20);
        if (!Double.isFinite(yaw)
                || !Double.isFinite(pitch)
                || !Double.isFinite(roll)
                || !Double.isFinite(mouseYaw)
                || !Double.isFinite(mousePitch)) return new Quaterniond();
        double a = Math.toRadians(yaw),
                p = Math.clamp(pitch, -1, 1),
                r = Math.clamp(roll, -1, 1),
                length = Math.max(1, Math.hypot(p, r));
        p *= PILOT_TURN / 20 / length;
        r *= PILOT_TURN / 20 / length;
        // Mouse pitch is vanilla's down-positive angle. R maps universe to Level: inverse nose yaw
        // is +Y.
        double scale =
                Math.min(1, (MOUSE_TURN / 20) / Math.max(1e-300, Math.hypot(mouseYaw, mousePitch)));
        p -= mousePitch * scale;
        double x = Math.cos(a) * p + Math.sin(a) * r,
                y = mouseYaw * scale,
                z = Math.sin(a) * p - Math.cos(a) * r;
        length = Math.sqrt(x * x + y * y + z * z);
        return length == 0
                ? new Quaterniond()
                : new Quaterniond()
                        .rotationAxis(Math.toRadians(length), x / length, y / length, z / length);
    }

    /** Production axes are the controller bow and hull up carried by the body's pose, never pilot look. */
    public static Quaterniond attitude(
            Space.Frame frame,
            GroundEphemeris.Model model,
            double tick,
            Point pivot,
            boolean arriving,
            boolean steadied,
            Point forward,
            Point up,
            double pitch,
            double roll,
            double mouseYaw,
            double mousePitch) {
        if (!steadied) return new Quaterniond();
        if (arriving)
            return Space.Frame.turn(frame, model, tick, pivot, Math.toRadians(ALIGN_TURN) / 20);
        if (!Double.isFinite(pitch)
                || !Double.isFinite(roll)
                || !Double.isFinite(mouseYaw)
                || !Double.isFinite(mousePitch)) return new Quaterniond();
        double p = Math.clamp(pitch, -1, 1),
                r = Math.clamp(roll, -1, 1),
                length = Math.max(1, Math.hypot(p, r));
        p *= PILOT_TURN / 20 / length;
        r *= PILOT_TURN / 20 / length;
        double scale =
                Math.min(1, (MOUSE_TURN / 20) / Math.max(1e-300, Math.hypot(mouseYaw, mousePitch)));
        p -= mousePitch * scale;
        var f = new org.joml.Vector3d(forward.x(), forward.y(), forward.z());
        var u = new org.joml.Vector3d(up.x(), up.y(), up.z());
        var axis = new org.joml.Vector3d(f).cross(u).mul(-p).fma(-r, f).fma(mouseYaw * scale, u);
        length = axis.length();
        return length == 0
                ? new Quaterniond()
                : new Quaterniond()
                        .rotationAxis(
                                Math.toRadians(length),
                                axis.x / length,
                                axis.y / length,
                                axis.z / length);
    }

    /** Only forward thrust steers drift onto the unit nose. Keep speed unless flying sideways or backward. */
    static Point assist(Point drift, Point nose, boolean forward) {
        if (!forward) return drift;
        double along = drift.dot(nose);
        var parallel = nose.scale(along);
        var turned = parallel.add(drift.sub(parallel).scale(1 / (1 + ASSIST / 20)));
        return along > 0 ? turned.scale(drift.length() / turned.length()) : turned;
    }

    /** Assist and cruise use the lock's centre velocity offset; arrival stays nearest-relative. */
    static Point assist(Point drift, Point nose, boolean forward, Point targetVelocity) {
        if (!forward) return drift;
        if (targetVelocity == null || targetVelocity.length() == 0)
            return assist(drift, nose, true);
        return assist(drift.sub(targetVelocity), nose, true).add(targetVelocity);
    }

    /** Arrival keeps only signed radial thrust; outside it return the exact original vector. */
    public static Point blendPush(Point impulse, Point toward, double blend) {
        return impulse.add(arrivalPush(impulse, toward, true).sub(impulse).scale(blend));
    }

    public static Point arrivalPush(Point impulse, Point toward, boolean arriving) {
        if (!arriving) return impulse;
        double length = toward.length();
        if (length == 0) return GroundEphemeris.ZERO;
        var radial = toward.scale(1 / length);
        return radial.scale(impulse.dot(radial));
    }

    /** Velocities are blocks/tick. Fold gain changes drift only, never the hull's Level relabelling. */
    static Point gain(Point drift, Point impulse, boolean piloted, double drag) {
        var next = drift.add(impulse.scale(piloted ? VACUUM : 1));
        return piloted ? next : next.scale(1 / (1 + drag / 20));
    }

    /** Assist has already run. Reject new excess; existing excess sheds exponentially at SHED/second. */
    static Point cruise(
            Point before, Point drift, Point push, boolean boosted, SpaceSpeed.Caps caps) {
        double old = before.length(), speed = drift.length();
        boolean braking = old > BRAKE_FLOOR / 20 && push.dot(before.scale(1 / old)) < -.5;
        double brake = caps.brake();
        if (braking) {
            if (drift.dot(before) <= 0 || speed <= brake / 400) return GroundEphemeris.ZERO;
            drift = drift.scale((speed - brake / 400) / speed);
            speed = drift.length();
        }
        double limit = caps.active(boosted) / 20,
                allowed = limit + Math.max(0, old - limit) / (1 + SHED / 20);
        return speed > allowed ? drift.scale(allowed / speed) : drift;
    }

    /** Cap, shedding and opposing-thrust braking all use the same target-relative drift. */
    static Point cruise(
            Point before,
            Point drift,
            Point push,
            boolean boosted,
            Point targetVelocity,
            SpaceSpeed.Caps caps) {
        if (targetVelocity == null || targetVelocity.length() == 0)
            return cruise(before, drift, push, boosted, caps);
        return cruise(before.sub(targetVelocity), drift.sub(targetVelocity), push, boosted, caps)
                .add(targetVelocity);
    }

    /** Only nearest-planet closing is governed; reference velocity and tangential drift are untouched.
     * This is a kinematic safety cap, not the assisted manual brake. Tracking it requires v/6 b/s².
     * At a discrete crossing the prior cap is at most ARRIVE/(1-1/(20*ARRIVE_TIME)). */
    static double arrivalLimit(GroundEphemeris.Body body, double height) {
        return ARRIVE + Math.max(0, height - body.hullIn()) / ARRIVE_TIME;
    }

    static double nearLimit(GroundEphemeris.Body body, double height) {
        return ARRIVE + Math.max(0, height - body.hullIn()) / NEAR_TIME;
    }

    static Point arrival(
            GroundEphemeris.Body body, Point drift, Point toward, Point relative, double height) {
        double length = toward.length();
        if (length == 0) return drift;
        var radial = toward.scale(1 / length);
        double closing = relative.dot(radial), limit = arrivalLimit(body, height) / 20;
        return closing > limit ? drift.sub(radial.scale(closing - limit)) : drift;
    }

    /** After radial governing, spend the whole-speed budget on radial motion first; trim only tangent. */
    static Point near(
            GroundEphemeris.Body body, Point drift, Point toward, Point relative, double height) {
        double length = toward.length();
        if (length == 0) return drift;
        var radial = toward.scale(1 / length);
        double along = relative.dot(radial), limit = nearLimit(body, height) / 20;
        var tangent = relative.sub(radial.scale(along));
        double speed = tangent.length(),
                allowed = Math.sqrt(Math.max(0, limit * limit - along * along));
        return speed > allowed ? drift.add(tangent.scale(allowed / speed).sub(tangent)) : drift;
    }

    static boolean nearer(
            GroundEphemeris.Model model, Point universe, int current, int nearest, double tick) {
        return nearest != current
                && universe.sub(model.at(current, tick).center()).length()
                                - universe.sub(model.at(nearest, tick).center()).length()
                        > Space.HYSTERESIS;
    }

    public static Point anchor(Point point) {
        var delta = Space.ORIGIN.sub(point);
        return Math.abs(delta.x()) > Space.ANCHOR_REACH
                        || Math.abs(delta.y()) > Space.ANCHOR_REACH
                        || Math.abs(delta.z()) > Space.ANCHOR_REACH
                ? delta
                : null;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        var q = frame.rotation();
        var p = frame.shift();
        tag.putDouble("qx", q.x);
        tag.putDouble("qy", q.y);
        tag.putDouble("qz", q.z);
        tag.putDouble("qw", q.w);
        tag.putDouble("x", p.x());
        tag.putDouble("y", p.y());
        tag.putDouble("z", p.z());
        tag.putInt("planet", frame.planet());
        tag.putLong("epoch", epoch);
        var o = frame.offset();
        var w = frame.drift();
        tag.putDouble("ox", o.x());
        tag.putDouble("oy", o.y());
        tag.putDouble("oz", o.z());
        tag.putDouble("wx", w.x());
        tag.putDouble("wy", w.y());
        tag.putDouble("wz", w.z());
        tag.putLong("originEpoch", frame.epoch());
        tag.putBoolean("arrivalLatched", arrivalState.latched());
        tag.putDouble("arrivalHeight", arrivalState.entryHeight());
        tag.putInt("arrivalPlanet", arrivalState.planet());
        tag.putString("arrivalLead", arrivalState.lead());
        var d = arrivalState.departure();
        tag.remove("departurePlanet");
        tag.remove("departureLead");
        tag.remove("departureTicks");
        if (d != null) {
            tag.putInt("departurePlanet", d.planet());
            tag.putString("departureLead", d.lead());
        }
        return tag;
    }
}
