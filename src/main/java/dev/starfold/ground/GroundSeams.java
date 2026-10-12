package dev.starfold.ground;

import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetChart;
import dev.starfold.chart.PlanetOrbit;
import dev.starfold.flight.GroundDynamics;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetShape;
import dev.starfold.render.GroundViewServer;
import dev.starfold.sky.GroundClock;
import dev.starfold.space.Space;
import dev.starfold.space.SpaceServer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.BiFunction;

/** Native owner transport after vanilla travel. No independent block/entity copy is created. */
public final class GroundSeams {
    private static java.util.function.Function<Level, Iterable<Entity>> clientEntities =
            l -> java.util.List.of();

    public static void clientEntities(java.util.function.Function<Level, Iterable<Entity>> reader) {
        clientEntities = reader;
    }

    public static Iterable<Entity> entities(Level level) {
        return level instanceof net.minecraft.server.level.ServerLevel server
                ? server.getAllEntities()
                : clientEntities.apply(level);
    }

    private static BiFunction<Level, BlockPos, net.minecraft.world.level.block.entity.BlockEntity>
            clientEntity = (l, p) -> null;

    public static void clientEntity(
            BiFunction<Level, BlockPos, net.minecraft.world.level.block.entity.BlockEntity>
                    reader) {
        clientEntity = reader;
    }

    public static net.minecraft.world.level.block.entity.BlockEntity clientBlockEntity(
            Level level, BlockPos pos) {
        return clientEntity.apply(level, pos);
    }

    public record LightQuery(BlockPos pos, net.minecraft.world.level.LightLayer layer) {}

    private static BiFunction<Level, LightQuery, Integer> clientLight = (l, p) -> null;

    public static void clientLight(BiFunction<Level, LightQuery, Integer> reader) {
        clientLight = reader;
    }

    public static Integer clientBrightness(
            Level level, BlockPos pos, net.minecraft.world.level.LightLayer layer) {
        return clientLight.apply(level, new LightQuery(pos, layer));
    }

    private static BiFunction<Level, BlockPos, BlockState> clientRead = (l, p) -> null;

    public static void clientRead(BiFunction<Level, BlockPos, BlockState> reader) {
        clientRead = reader;
    }

    public static BlockState clientState(Level level, BlockPos owner) {
        return clientRead.apply(level, owner);
    }

    // Physics may use a complete server snapshot, never the generated LOD fallback.
    private static BiFunction<Level, BlockPos, BlockState> clientCollisionRead = (l, p) -> null;

    public static void clientCollisionRead(BiFunction<Level, BlockPos, BlockState> reader) {
        clientCollisionRead = reader;
    }

    public static BlockState clientCollisionState(Level level, BlockPos owner) {
        return clientCollisionRead.apply(level, owner);
    }

    /** Client: is this client-coordinate cell inside a solid corner mask? */
    private static java.util.function.BiPredicate<Level, BlockPos> clientMask = (l, p) -> false;

    public static void clientMask(java.util.function.BiPredicate<Level, BlockPos> reader) {
        clientMask = reader;
    }

    public static boolean clientMasked(Level level, BlockPos pos) {
        return clientMask.test(level, pos);
    }

    /** Client: a canonical pose's feet in client coordinates, or null if not drawn in the observer's chart. */
    private static BiFunction<Integer, GroundTopology.Pose, Point> clientPlane =
            (planet, pose) -> null;

    public static void clientPlane(BiFunction<Integer, GroundTopology.Pose, Point> reader) {
        clientPlane = reader;
    }

    public static Point clientFeet(int planet, GroundTopology.Pose pose) {
        return clientPlane.apply(planet, pose);
    }

    public static boolean inChart(BlockPos pos) {
        return inChart(PlanetShape.STANDARD, pos);
    }

    public static boolean inChart(PlanetShape shape, BlockPos pos) {
        var cell = GroundTopology.cell(pos);
        return shape.inChart(cell.u(), cell.v());
    }

    /** A place in a chart's halo and the same place in the chart that owns it. Everything near it is carried by the
     *  same rigid motion: a quarter turn about the vertical and a shift. */
    public record Transport(Point from, Point to, int turns) {
        public Point apply(Point place) {
            return to.add(ChartLabSpace.rotate(place.sub(from), turns));
        }

        public Point vector(Point vector) {
            return ChartLabSpace.rotate(vector, turns);
        }

        public BlockPos halo() {
            return BlockPos.containing(from.x(), from.y(), from.z());
        }

        public BlockPos owner() {
            return BlockPos.containing(to.x(), to.y(), to.z());
        }
    }

    /** How what stands at a place is carried to the chart that owns the place; null where its own chart owns it. */
    public static Transport transport(Level level, Point feet) {
        var access = PlanetAccess.of(level);
        if (!access.inChart(BlockPos.containing(feet.x(), feet.y(), feet.z()))) return null;
        var chart = access.chart(feet);
        var shape = access.shape(feet.x(), feet.z());
        if (shape.owned(chart.x(), chart.z())) return null;
        if (chart.y() + .9 < GroundTopology.LOWEST || chart.y() + .9 > GroundTopology.MAX_HEIGHT)
            return null;
        var route =
                GroundTopology.canonical(
                        shape,
                        CubeSurfaceTopology.Face.values()[access.face(feet.x())],
                        chart.add(new Point(0, .9, 0)));
        return new Transport(
                feet,
                access.feet(access.storagePlanet(feet.x(), feet.z()), route.pose()),
                route.turns());
    }

    /** From over one planet to over another. A chart reaches up without end, but a place is reckoned from the planet
     *  nearest to it: whatever climbs past the half-way point is carried into the other planet's chart, where it is in
     *  the universe, heading and moving as it was. A rotation of any angle, where a seam has quarter turns. */
    public record Passage(
            int planet,
            Point from,
            Point to,
            org.joml.Quaterniond turn,
            java.util.function.UnaryOperator<Point> motion) {
        private Point turned(Point v) {
            var r = turn.transform(new org.joml.Vector3d(v.x(), v.y(), v.z()));
            return new Point(r.x, r.y, r.z);
        }

        public Point apply(Point place) {
            return to.add(turned(place.sub(from)));
        }

        /** A velocity in the old chart as the same motion through the universe in the new one. */
        public Point vector(Point chartVelocity) {
            return motion.apply(chartVelocity);
        }

        /** Something that only spins or points: turned, not carried along by either planet. */
        public Point spin(Point axis) {
            return turned(axis);
        }

        public float yaw(float yaw) {
            double r = Math.toRadians(yaw);
            var f = turned(new Point(-Math.sin(r), 0, Math.cos(r)));
            return Math.hypot(f.x(), f.z()) < 1e-6
                    ? yaw
                    : (float) Math.toDegrees(Math.atan2(-f.x(), f.z()));
        }
    }

    /** Below this height over its planet nothing is asked whether another planet is nearer: none is, and every mob that moves would ask. */
    public static final double PASSAGE = 2048;

    public static Passage passage(Level level, Point feet) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server)
                || !GroundServer.is(level)) return null;
        var access = PlanetAccess.of(level);
        if (!access.inChart(BlockPos.containing(feet.x(), feet.y(), feet.z()))
                || access.chart(feet).y() < PASSAGE) return null;
        var clock = GroundClock.get(server).clock;
        var model = clock.model;
        int here = access.storagePlanet(feet.x(), feet.z());
        if (model.count() < 2 || here >= model.count()) return null;
        long tick = clock.tick();
        var pose = access.body(feet);
        var shape = PlanetShape.of(here);
        var leaving = model.at(here, tick);
        var local = shape.position(pose);
        var world = leaving.world(local);
        int there = GroundDynamics.select(model, world, here, tick);
        if (there == here) return null;
        var arriving = model.at(there, tick);
        var other = PlanetShape.of(there);
        var relative = arriving.local(world);
        double height = relative.length() - other.radius();
        if (height > GroundOrbit.LIMIT || height < GroundSpace.MIN + 1) return null;
        var next = GroundTopology.locate(other, relative);
        var turn =
                PlanetOrbit.frame(other, next)
                        .rotation()
                        .conjugate()
                        .mul(arriving.rotation().conjugate())
                        .mul(leaving.rotation())
                        .mul(PlanetOrbit.frame(shape, pose).rotation())
                        .normalize();
        return new Passage(
                there,
                feet,
                access.feet(there, next),
                turn,
                v ->
                        GroundTopology.chartVelocity(
                                other,
                                next,
                                arriving.localVelocity(
                                        world,
                                        leaving.worldVelocity(
                                                local,
                                                GroundTopology.planetVelocity(shape, pose, v)))));
    }

    /** Something carried, or moving by itself, that has gone over to another planet's chart. A player's client is told
     *  where it now is: the planet under them is another one, and their view of it begins afresh. */
    public static void carry(Entity entity, Passage p) {
        var at = p.apply(new Point(entity.getX(), entity.getY(), entity.getZ()));
        var old = entity.getDeltaMovement();
        var v = p.vector(new Point(old.x, old.y, old.z));
        float yaw = p.yaw(entity.getYRot());
        if (entity instanceof ServerPlayer player) {
            GroundViewServer.remove(player);
            player.connection.teleport(at.x(), at.y(), at.z(), yaw, player.getXRot());
            player.setDeltaMovement(v.x(), v.y(), v.z());
            GroundFlightServer.site(player);
        } else {
            entity.moveTo(at.x(), at.y(), at.z(), yaw, entity.getXRot());
            entity.setYHeadRot(yaw);
            entity.setDeltaMovement(v.x(), v.y(), v.z());
        }
    }

    /** A carried player is relabelled by the body's atomic payload, not a vanilla teleport/accept cycle. Sable
     *  already supplies tracked motion; sending a hurt impulse would give the pilot the ship's speed twice. */
    public static void carryBody(
            Entity entity, Point at, Point velocity, float yaw, boolean tracked, Transport seam) {
        CARRIED_AT.put(entity, entity.level().getGameTime());
        if (entity instanceof ServerPlayer player) {
            GroundViewServer.remove(player);
            if (seam == null) ObserverFrames.relocate(player, at);
            else ObserverFrames.cross(player, seam.halo(), seam.owner(), seam.turns());
            player.moveTo(at.x(), at.y(), at.z(), yaw, player.getXRot());
            player.connection.resetPosition();
        } else entity.moveTo(at.x(), at.y(), at.z(), yaw, entity.getXRot());
        entity.setYHeadRot(yaw);
        entity.setDeltaMovement(velocity.x(), velocity.y(), velocity.z());
        if (!tracked) entity.hurtMarked = true;
        entity.fallDistance = 0;
        if (entity instanceof net.minecraft.world.entity.Mob mob) {
            if (seam == null) mob.getNavigation().stop();
            else carryPath(mob, at.x(), at.z());
        }
        if (entity instanceof ServerPlayer player) {
            SpaceServer.sync(player);
            player.serverLevel().getChunkSource().move(player);
            if (!SpaceServer.in(player)
                    && PlanetAccess.of(player.level())
                            .inChart(BlockPos.containing(at.x(), at.y(), at.z())))
                GroundFlightServer.site(player);
        }
    }

    // A move-tail callback must not undo a body's relabel even if contact tracking is cleared in
    // that step.
    private static final java.util.Map<Entity, Long> CARRIED_AT = new java.util.WeakHashMap<>();
    private static final java.util.List<java.util.function.Predicate<Entity>> CARRIED =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Another mod's way of carrying an entity that is not riding: a body it stands on decides for it, as a vehicle would. */
    public static void carriedBy(java.util.function.Predicate<Entity> carried) {
        CARRIED.add(carried);
    }

    public static boolean carried(Entity entity) {
        if (entity.isPassenger()
                || java.util.Objects.equals(CARRIED_AT.get(entity), entity.level().getGameTime()))
            return true;
        for (var c : CARRIED) if (c.test(entity)) return true;
        return false;
    }

    /** Whatever is carried goes with what carries it: the root of a riding stack alone decides when the stack changes
     *  chart, and every rider goes with it by the same transport. True when the entity was moved to another chart. */
    public static boolean moved(Entity entity) {
        if (entity.level().isClientSide
                || !GroundServer.is(entity.level())
                || GroundFlightServer.controlled(entity)
                || carried(entity)) return false;
        var t = transport(entity.level(), new Point(entity.getX(), entity.getY(), entity.getZ()));
        if (t == null) {
            var passage =
                    passage(entity.level(), new Point(entity.getX(), entity.getY(), entity.getZ()));
            if (passage == null) return false;
            // The root goes, and takes its riders with it; a rider who steers is told where the
            // vehicle now is.
            carry(entity, passage);
            for (var rider : entity.getIndirectPassengers()) {
                rider.setYRot(passage.yaw(rider.getYRot()));
                if (rider instanceof ServerPlayer player) {
                    GroundViewServer.remove(player);
                    ObserverFrames.relocate(
                            player, new Point(player.getX(), player.getY(), player.getZ()));
                    player.connection.send(
                            new net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket(
                                    entity));
                    GroundFlightServer.site(player);
                }
            }
            return true;
        }
        var next = t.to();
        var old = entity.getDeltaMovement();
        var velocity = t.vector(new Point(old.x, old.y, old.z));
        float yaw = entity.getYRot() + t.turns() * 90;
        if (entity instanceof ServerPlayer player) {
            // The body moves to its canonical owner chart; the observer's client coordinates do not
            // change
            // (ObserverFrame re-roots), so no teleport packet is sent and nothing is reloaded.
            ObserverFrames.cross(player, t.halo(), t.owner(), t.turns());
            player.moveTo(next.x(), next.y(), next.z(), yaw, player.getXRot());
            player.setYHeadRot(yaw);
            player.connection.resetPosition();
            player.serverLevel().getChunkSource().move(player);
        } else {
            entity.teleportTo(next.x(), next.y(), next.z());
            entity.setYRot(yaw);
            entity.setYHeadRot(entity.getYHeadRot() + t.turns() * 90);
            if (entity instanceof net.minecraft.world.entity.Mob mob)
                carryPath(mob, next.x(), next.z());
        }
        entity.setDeltaMovement(velocity.x(), velocity.y(), velocity.z());
        for (var rider : entity.getIndirectPassengers()) carry(rider, t, false);
        return true;
    }

    /** Something carried by what has just been moved from a halo cell to its owner cell on another chart. What it faces
     *  and how it moves turn with the chart, and a player's client coordinates stay as they were. A vehicle places its
     *  own riders; whoever only stands on the carrier is placed here. */
    public static void carry(Entity rider, Transport t, boolean place) {
        var old = rider.getDeltaMovement();
        var velocity = t.vector(new Point(old.x, old.y, old.z));
        rider.setDeltaMovement(velocity.x(), velocity.y(), velocity.z());
        float yaw = rider.getYRot() + t.turns() * 90;
        if (place) {
            var at = t.apply(new Point(rider.getX(), rider.getY(), rider.getZ()));
            rider.moveTo(at.x(), at.y(), at.z(), yaw, rider.getXRot());
        } else rider.setYRot(yaw);
        rider.setYHeadRot(rider.getYHeadRot() + t.turns() * 90);
        if (rider instanceof ServerPlayer player) {
            ObserverFrames.cross(player, t.halo(), t.owner(), t.turns());
            player.connection.resetPosition();
            player.serverLevel().getChunkSource().move(player);
        }
    }

    /** A storage position (canonical owner, or a halo position of any chart) re-expressed in the chart of the
     * face an observer is on; unchanged when it already is in that chart, null where that chart does not show it. */
    public static BlockPos rechart(Level level, BlockPos pos, double observerX, double observerZ) {
        var access = PlanetAccess.of(level);
        if (!access.inChart(pos)
                || !access.samePlanet(pos.getX(), pos.getZ(), observerX, observerZ)
                || access.face(pos.getX()) == access.face(observerX)) return pos;
        var owner = access.address(pos).owner();
        var p =
                access.image(
                        observerX,
                        observerZ,
                        new Point(owner.getX() + .5, owner.getY(), owner.getZ() + .5));
        return p == null ? null : BlockPos.containing(p.x(), p.y(), p.z());
    }

    /** A continuous canonical point (an entity, a look target) in the chart of the face at {@code observerX}. */
    public static Point rechart(Level level, Point point, double observerX, double observerZ) {
        var access = PlanetAccess.of(level);
        var at = BlockPos.containing(point.x(), point.y(), point.z());
        if (!access.inChart(at)
                || !access.samePlanet(point.x(), point.z(), observerX, observerZ)
                || access.face(point.x()) == access.face(observerX)) return point;
        if (access.key(at.getX() >> 4, at.getZ() >> 4) < 0) {
            var block = rechart(level, at, observerX, observerZ);
            return block == null
                    ? null
                    : new Point(
                            block.getX() + point.x() - Math.floor(point.x()),
                            point.y(),
                            block.getZ() + point.z() - Math.floor(point.z()));
        }
        return access.image(observerX, observerZ, point);
    }

    /** A mob carried across a seam keeps following its path: nodes and target move into the new chart. */
    static void carryPath(net.minecraft.world.entity.Mob mob, double observerX, double observerZ) {
        var navigation = mob.getNavigation();
        var path = navigation.getPath();
        if (path == null || path.isDone()) return;
        var nodes = new java.util.ArrayList<net.minecraft.world.level.pathfinder.Node>();
        for (int i = 0; i < path.getNodeCount(); i++) {
            var n = path.getNode(i);
            var p = rechart(mob.level(), new BlockPos(n.x, n.y, n.z), observerX, observerZ);
            if (p == null) {
                navigation.stop();
                return;
            }
            nodes.add(n.cloneAndMove(p.getX(), p.getY(), p.getZ()));
        }
        var target = rechart(mob.level(), path.getTarget(), observerX, observerZ);
        if (target == null) {
            navigation.stop();
            return;
        }
        var moved = new net.minecraft.world.level.pathfinder.Path(nodes, target, path.canReach());
        moved.setNextNodeIndex(path.getNextNodeIndex());
        var access = (dev.starfold.mixin.GroundNavigationAccess) navigation;
        double speed = access.starfold$speed();
        var goal = access.starfold$target();
        if (goal != null) {
            var g = rechart(mob.level(), goal, observerX, observerZ);
            access.starfold$target(g);
        }
        navigation.moveTo(moved, speed);
    }

    public static Point image(Entity observer, double x, double y, double z) {
        if (SpaceServer.in(observer) || Space.holds(x, z)) return new Point(x, y, z);
        var access = PlanetAccess.of(observer.level());
        if (!access.samePlanet(observer.getX(), observer.getZ(), x, z)) return null;
        var canonical =
                GroundTopology.canonical(
                        access.shape(x, z),
                        CubeSurfaceTopology.Face.values()[access.face(x)],
                        access.chart(new Point(x, y, z)));
        var at = access.feet(access.storagePlanet(x, z), canonical.pose()).add(new Point(0, .9, 0));
        return access.image(observer.getX(), observer.getZ(), at);
    }

    public static net.minecraft.world.phys.AABB box(
            net.minecraft.world.phys.AABB source, double observerX, double observerZ) {
        return box(PlanetAccess.LEGACY, source, observerX, observerZ);
    }

    public static net.minecraft.world.phys.AABB box(
            Level level, net.minecraft.world.phys.AABB source, double observerX, double observerZ) {
        return box(PlanetAccess.of(level), source, observerX, observerZ);
    }

    public static net.minecraft.world.phys.AABB box(
            PlanetAccess access,
            net.minecraft.world.phys.AABB source,
            double observerX,
            double observerZ) {
        var center = source.getCenter();
        if (!access.inChart(BlockPos.containing(center))
                || !access.inChart(BlockPos.containing(observerX, 128, observerZ))) return source;
        if (!access.samePlanet(observerX, observerZ, center.x, center.z)) return null;
        int owner = access.face(center.x), root = access.face(observerX);
        var tile =
                new PlanetChart(
                                access.shape(observerX, observerZ),
                                CubeSurfaceTopology.Face.values()[root])
                        .tile(CubeSurfaceTopology.Face.values()[owner]);
        if (tile == null) return null;
        var origin = access.originAt(center.x, center.z);
        var base = access.originAt(observerX, observerZ);
        var a = base.add(tile.point(new Point(source.minX, source.minY, source.minZ).sub(origin)));
        var b = base.add(tile.point(new Point(source.maxX, source.maxY, source.maxZ).sub(origin)));
        return new net.minecraft.world.phys.AABB(
                Math.min(a.x(), b.x()),
                Math.min(a.y(), b.y()),
                Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()),
                Math.max(a.y(), b.y()),
                Math.max(a.z(), b.z()));
    }

    public static double distance(Entity observer, double x, double y, double z) {
        var access = PlanetAccess.of(observer.level());
        if (!access.inChart(observer.blockPosition())
                || !access.inChart(BlockPos.containing(x, y, z))) {
            double dx = observer.getX() - x, dy = observer.getY() - y, dz = observer.getZ() - z;
            return dx * dx + dy * dy + dz * dz;
        }
        // Block/menu centres can extend beyond the navigation height shell; use chart images
        // directly.
        var p = access.image(observer.getX(), observer.getZ(), new Point(x, y, z));
        if (p == null) return Double.POSITIVE_INFINITY;
        var d = p.sub(new Point(observer.getX(), observer.getY(), observer.getZ()));
        return d.dot(d);
    }

    private GroundSeams() {}
}
