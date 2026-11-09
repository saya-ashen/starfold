package dev.starfold.space;

import static dev.starfold.space.SpaceFlightMath.*;
import static dev.starfold.space.SpaceSessions.*;

import dev.starfold.chart.CelestialTarget;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.ArrivalState;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.GroundServer;
import dev.starfold.ground.ObserverFrames;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.DevelopmentHooks;
import dev.starfold.ship.HelmCommand;
import dev.starfold.ship.HelmServer;
import dev.starfold.ship.SableFrames;
import dev.starfold.ship.ShipParking;
import dev.starfold.ship.ShipRecords;
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

    private Space.Frame frame;
    private long epoch;
    private Point holdTurn = GroundEphemeris.ZERO;
    private java.util.UUID turningLead;
    private ArrivalState arrivalState = ArrivalState.empty();
    private dev.ryanhcode.sable.sublevel.ServerSubLevel inboundBody;
    private int inboundPlanet = -1;
    private boolean inbound, arriving;
    private static final net.minecraft.resources.ResourceLocation WEIGHTLESS =
            net.minecraft.resources.ResourceLocation.parse("starfold:space_weightless");

    public static void initialize() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                net.neoforged.bus.api.EventPriority.LOWEST,
                (dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent event) ->
                        sunPhysics(event.getPhysicsSystem(), event.getTimeStep()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                net.neoforged.bus.api.EventPriority.LOWEST,
                (dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent event) ->
                        spacePhysics(event.getPhysicsSystem(), event.getTimeStep()));
        StarfoldNetwork.playS2C(SpacePackets.Frame.TYPE, SpacePackets.Frame.CODEC);
        StarfoldNetwork.playS2C(
                dev.starfold.ship.GasPressurePacket.TYPE,
                dev.starfold.ship.GasPressurePacket.CODEC);
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
                                packet.bodyIndex()));
        SpaceSessions.initialize();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.LevelTickEvent.Pre event) -> {
                    if (event.getLevel() instanceof ServerLevel level) tick(level);
                });
    }

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

    static void send(net.minecraft.server.level.ServerPlayer player) {
        send(player, false);
    }

    private static void send(net.minecraft.server.level.ServerPlayer player, boolean turning) {
        var packet = packet(player);
        if (!SableFrames.carrying(player) && player.connection.hasChannel(SpacePackets.Frame.TYPE))
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, packet);
    }

    /** Publish current frame and authorize the personal stream without requiring a hull. */
    public static long exposureView(net.minecraft.server.level.ServerPlayer player) {
        send(player);
        return view(player);
    }

    public static long view(net.minecraft.server.level.ServerPlayer player) {
        return SpaceSessions.view(player);
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

    private java.util.function.Supplier<dev.starfold.chart.GroundEphemeris.Model> savedModel =
            () -> dev.starfold.planet.PlanetSystem.model(dev.starfold.chart.PlanetShape.count());

    public SpaceServer(Space.Frame frame) {
        this.frame = frame;
    }

    public SpaceServer(
            Space.Frame frame,
            java.util.function.Supplier<dev.starfold.chart.GroundEphemeris.Model> model) {
        this(frame);
        this.savedModel = java.util.Objects.requireNonNull(model);
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
        return load(
                tag,
                lookup,
                () ->
                        dev.starfold.planet.PlanetSystem.model(
                                dev.starfold.chart.PlanetShape.count()));
    }

    public static SpaceServer load(
            CompoundTag tag,
            HolderLookup.Provider lookup,
            java.util.function.Supplier<dev.starfold.chart.GroundEphemeris.Model> model) {
        var decoded = SpaceFrameCodec.read(tag, model);
        var value = new SpaceServer(decoded.frame(), model);
        value.epoch = decoded.epoch();
        value.arrivalState = decoded.arrival();
        value.arriving = value.arrivalState.latched();
        return value;
    }

    private static SpaceServer get(ServerLevel level) {
        var storage = level.getDataStorage();
        var saved =
                storage.computeIfAbsent(
                        new Factory<>(
                                () ->
                                        fresh(
                                                ((dev.starfold.mixin.SavedDataFileAccess) storage)
                                                        .starfold$spaceDataFile(FILE)
                                                        .exists()),
                                (tag, lookup) ->
                                        load(tag, lookup, () -> GroundClock.get(level).clock.model),
                                null),
                        FILE);
        saved.savedModel = () -> GroundClock.get(level).clock.model;
        return saved;
    }

    /** Replace a region reference without moving its local hulls. */
    public static void frame(ServerLevel level, Space.Frame frame, boolean newReference) {
        var value = get(level);
        value.frame = frame;
        value.setDirty();
        if (newReference) {
            value.epoch = frame.epoch();
            SpaceApproach.release(level);
        }
        for (var player : level.players()) if (Space.in(player)) send(player);
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
            dev.starfold.ship.GasShipDamage.release(level);
            clearEmptyRegion(level, container);
            return;
        }
        var lead = lead(level, container);
        var value = get(level);
        var clock = GroundClock.get(level).clock;
        for (var body : bodies) ShipRecords.binding(level, body);
        advanceLead(level, container, bodies, lead, value, clock);
        settleFollowers(level, container, bodies, lead, value);
        // Followers retain their own Sable velocity; constrain it after parking/coast forces.
        var pipeline = container.physicsSystem().getPipeline();
        for (var body : bodies) {
            if (body == lead) continue;
            var at = body.logicalPose().position();
            var velocity = pipeline.getLinearVelocity(body, new org.joml.Vector3d()).div(20);
            var motion = new Point(velocity.x, velocity.y, velocity.z);
            var limited =
                    dev.starfold.chart.SunBoundary.inFrame(
                            clock.model,
                            value.frame,
                            new Point(at.x, at.y, at.z),
                            motion,
                            clock.tick(),
                            sunPadding(body));
            var change = limited.sub(motion).scale(20);
            if (change.length() > 0)
                pipeline.addLinearAndAngularVelocity(
                        body,
                        new org.joml.Vector3d(change.x(), change.y(), change.z()),
                        new org.joml.Vector3d());
        }
        dev.starfold.ship.GasShipDamage.step(
                level, container, bodies, clock.model, value.frame, clock.tick());
    }

    /** Actors have already applied thrust when Sable publishes this event, before integration.
     * Recheck each substep so sustained thrust cannot creep through a tick-end velocity wall. */
    private static void sunPhysics(
            dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physics, double step) {
        var level = physics.getLevel();
        if (!PlanetLevels.is(level) || !(step > 0)) return;
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) return;
        var clock = GroundClock.get(level).clock;
        var f = frame(level);
        double span = step * 20;
        double tick = Math.max(0, clock.tick() - 1 + physics.getPartialPhysicsTick() - span);
        var pipeline = physics.getPipeline();
        for (var body : spaceBodies(container)) {
            var at = body.logicalPose().position();
            var velocity = pipeline.getLinearVelocity(body, new org.joml.Vector3d());
            var motion = new Point(velocity.x * step, velocity.y * step, velocity.z * step);
            var limited =
                    dev.starfold.chart.SunBoundary.inFrame(
                            clock.model,
                            f,
                            new Point(at.x, at.y, at.z),
                            motion,
                            tick,
                            sunPadding(body),
                            span);
            var change = limited.sub(motion).scale(1 / step);
            if (change.length() > 0)
                pipeline.addLinearAndAngularVelocity(
                        body,
                        new org.joml.Vector3d(change.x(), change.y(), change.z()),
                        new org.joml.Vector3d());
        }
    }

    /** After gravity cancellation, undo native linear damping only in space, including followers. */
    private static void spacePhysics(
            dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physics, double step) {
        var level = physics.getLevel();
        if (!PlanetLevels.is(level)) return;
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) return;
        var f = frame(level);
        var drift = f.levelVector(f.drift()).scale(20);
        double drag = dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData
                .getUniversalDrag(level) * step;
        var pipeline = physics.getPipeline();
        for (var body : spaceBodies(container)) {
            var v = pipeline.getLinearVelocity(body, new org.joml.Vector3d());
            var old = new Point(v.x, v.y, v.z);
            var restored = SpaceSpeed.undamped(old, drag);
            var relative = restored.add(drift).scale(1.0 / 20);
            var limited = SpaceSpeed.limit(relative).scale(20).sub(drift);
            var change = limited.sub(old);
            pipeline.addLinearAndAngularVelocity(body,
                    new org.joml.Vector3d(change.x(), change.y(), change.z()), new org.joml.Vector3d());
        }
    }

    /** Bounding sphere about the logical anchor covers the full rotating hull, not just its helm. */
    private static double sunPadding(dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
        var box = SableFrames.cabinBounds(body);
        if (box == null) return 0;
        var at = body.logicalPose().position();
        double x = Math.max(Math.abs(box.minX - at.x), Math.abs(box.maxX - at.x)),
                y = Math.max(Math.abs(box.minY - at.y), Math.abs(box.maxY - at.y)),
                z = Math.max(Math.abs(box.minZ - at.z), Math.abs(box.maxZ - at.z));
        return Math.sqrt(x * x + y * y + z * z);
    }

    public static java.util.function.BiFunction<
                    net.minecraft.world.entity.Entity,
                    net.minecraft.world.phys.Vec3,
                    net.minecraft.world.phys.Vec3>
            clientSunMotion = (entity, motion) -> motion;

    /** Movement is checked on the server even when it came from a vanilla position packet. */
    public static net.minecraft.world.phys.Vec3 sunMotion(
            net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.Vec3 motion) {
        if (!(entity instanceof net.minecraft.world.entity.player.Player) || !Space.in(entity))
            return motion;
        if (entity.level().isClientSide) return clientSunMotion.apply(entity, motion);
        var level = (ServerLevel) entity.level();
        var clock = GroundClock.get(level).clock;
        var limited =
                dev.starfold.chart.SunBoundary.inFrame(
                        clock.model,
                        frame(level),
                        new Point(entity.getX(), entity.getY(), entity.getZ()),
                        new Point(motion.x, motion.y, motion.z),
                        clock.tick(),
                        entity.getBbHeight());
        return new net.minecraft.world.phys.Vec3(limited.x(), limited.y(), limited.z());
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
                                        .add(impulse),
                                model.at(destination, tick).velocity());
        var entryRelative =
                entry.universeVelocity(GroundEphemeris.ZERO, model, tick)
                        .add(impulse)
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
            absorbLeadImpulse(value, tick, v, towardEntry, blend);
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
                        || !SpaceSessions.fresh(wish.tick(), level.getGameTime())
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
            if (turn.x != 0 || turn.y != 0 || turn.z != 0) {
                var old = value.frame;
                value.frame = old.turned(turn, point);
                value.setDirty();
                value.relabelTurn(level, container, bodies, lead, old);
            }
        }
        Point targetVelocity = null, guided = value.frame.drift();
        if (Thrusters.steadied(lead, level.getGameTime()))
            for (var player : level.players())
                if (helm(player, lead)) {
                    targetVelocity = targetVelocity(player, model, value.frame.planet(), tick);
                    if (!arriving) {
                        var f = value.frame.rebased(tick);
                        var forward = HelmServer.forward(lead);
                        var nose = f.universeVector(forward);
                        guided = assist(f.drift(), nose, Thrusters.forward(
                                lead, level.getGameTime(), forward), targetVelocity);
                    }
                    break;
                }
        if (piloted) {
            var f = value.frame.rebased(tick);
            var drift =
                    SpaceSpeedServer.maneuver(
                            lead, f, f.drift(), brake(prior, guided, push, targetVelocity));
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
            var limited = govern(model.body(nearest), drift, toward, relative, height, true);
            drift = drift.add(limited.sub(drift).scale(blend));
            value.frame =
                    new Space.Frame(f.rotation(), f.shift(), f.planet(), f.offset(), drift, tick);
            value.setDirty();
        }
        var bounded = value.frame.rebased(tick);
        value.frame = new Space.Frame(bounded.rotation(), bounded.shift(), bounded.planet(),
                bounded.offset(), SpaceSpeed.limit(bounded.drift()), tick);
        // Sable's lead impulse has already been folded into the moving region. Limit that
        // region's next displacement here, after cruise/arrival, without relocating the hull.
        {
            var f = value.frame.rebased(tick);
            var world = f.universe(point, model, tick);
            var carried = f.universe(point, model, tick + 1).sub(world);
            var limited =
                    dev.starfold.chart.SunBoundary.clip(
                            model, world, carried, tick, sunPadding(lead));
            if (!limited.equals(carried)) {
                value.frame =
                        new Space.Frame(
                                f.rotation(),
                                f.shift(),
                                f.planet(),
                                f.offset(),
                                f.drift().add(limited.sub(carried)),
                                tick);
                value.setDirty();
            }
        }
        if (DevelopmentHooks.arrival.enabled() && level.getGameTime() % 20 == 0) {
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
                if (target != null && target.celestial() != null && validTarget(player, target))
                    lockedPlanet =
                            target.celestial()
                                    .surfaceSlot(GroundClock.get(level).clock.model)
                                    .orElse(-1);
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
        // A helm without powered stabilization coasts rather than capturing a parked pose.
        if (ShipRecords.piloted(level, lead)) {
            value.clearHoldTurn();
            value.frame = value.frame.rebased(tick);
            return;
        }
        var model = GroundClock.get(level).clock.model;
        var at = lead.logicalPose().position();
        var point = new Point(at.x, at.y, at.z);
        var universe = value.frame.universe(point, model, tick);
        int body = ShipParking.nearestBody(model, universe, tick);
        double factor = 1;
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
        double factor = 1;
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
        long probeStart = Boolean.getBoolean("starfold.chunkPerf") ? System.nanoTime() : 0;
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
        if (probeStart != 0)
            org.slf4j.LoggerFactory.getLogger("starfold-chunk-perf")
                    .info("PROBE REANCHOR tick {} nanos {} bodies {} delta {}", tick,
                            System.nanoTime() - probeStart, bodies.size(), delta);
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
            double blend) {
        var f = value.frame.rebased(tick);
        value.frame =
                new Space.Frame(
                        f.rotation(),
                        f.shift(),
                        f.planet(),
                        f.offset(),
                        gain(f.drift(), blendPush(f.universeVector(v), towardEntry, blend)),
                        tick);
    }

    /** Use the same occupied-body order for accepting a wish and consuming it. */
    static dev.ryanhcode.sable.sublevel.ServerSubLevel lead(
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
    static boolean helm(
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
                || (target.ship() == null
                        ? !knownTarget(target.celestial().bodyIndex(), GroundClock.get(target.level()).clock.model)
                        : !dev.starfold.ship.ShipSave.get(target.level()).owns(player.getUUID(), target.ship()))) return false;
        var container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(target.level());
        return container instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer server
                && lead(target.level(), server) == target.body();
    }

    /** Read projection of the existing server lock; never a second pressure/navigation target. */
    public static int selectedBodyIndex(
            net.minecraft.server.level.ServerPlayer player, java.util.UUID hull) {
        var target = targets.get(player);
        return target != null
                        && target.level() == player.serverLevel()
                        && !target.body().isRemoved()
                        && target.body().getUniqueId().equals(hull)
                        && HelmServer.pilot(player, target.body())
                        && target.celestial() != null
                ? target.celestial().bodyIndex()
                : -1;
    }

    public static int markerPlanet(net.minecraft.server.level.ServerPlayer player) {
        var target = targets.get(player);
        if (target != null && target.celestial() != null && validTarget(player, target))
            return target.celestial()
                    .surfaceSlot(GroundClock.get(target.level()).clock.model)
                    .orElse(-1);
        var value = get(player.serverLevel());
        return value.arriving ? value.inboundPlanet : -1;
    }

    static boolean knownTarget(int bodyIndex, GroundEphemeris.Model model) {
        return CelestialNavigation.knownTarget(model, bodyIndex);
    }

    public static void target(net.minecraft.server.level.ServerPlayer player, int bodyIndex) {
        SpaceHost.get().targetCleared(player);
        targets.remove(player);
        if (!Space.in(player)) return;
        var model = GroundClock.get(player.serverLevel()).clock.model;
        if (!knownTarget(bodyIndex, model)) return;
        var container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                        player.serverLevel());
        if (!(container instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer server))
            return;
        var body = lead(player.serverLevel(), server);
        if (body != null && helm(player, body) && Thrusters.scopeTier(body) >= 2) {
            var celestial = CelestialTarget.resolve(model, bodyIndex);
            targets.put(player, new Target(player.serverLevel(), body, celestial, null));
            var value = get(player.serverLevel());
            value.arrivalState =
                    value.arrivalState.lock(
                            celestial.surfaceSlot(model).orElse(-1), body.getUniqueId().toString());
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
        var record = target.ship() == null ? null : dev.starfold.ship.ShipSave.get(player.serverLevel()).find(target.ship());
        if (target.ship() != null && record == null) { targets.remove(player); return null; }
        return (record == null ? target.celestial().frame(model, tick).velocity()
                : MapNavigation.velocity(model, record, tick)).sub(model.at(reference, tick).velocity());
    }

    public static void targetShip(net.minecraft.server.level.ServerPlayer player, java.util.UUID ship) {
        target(player, -1);
        if (!Space.in(player) || !dev.starfold.ship.ShipSave.get(player.serverLevel()).owns(player.getUUID(), ship)) return;
        var body = SableFrames.aboard(player);
        if (body != null && helm(player, body) && Thrusters.scopeTier(body) >= 2) {
            targets.put(player, new Target(player.serverLevel(), body, null, ship));
            var value = get(player.serverLevel());
            value.arrivalState = value.arrivalState.lock(-1, body.getUniqueId().toString());
            value.setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        return SpaceFrameCodec.write(tag, frame, epoch, arrivalState, savedModel);
    }
}
